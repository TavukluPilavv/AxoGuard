package com.axoguard.anticheat;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Llama;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, PluginMessageListener {

    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<String, Integer> hackKickCountMap = new HashMap<>(); 
    private final Map<UUID, Long> hitBlockTime = new HashMap<>();
    private final Map<UUID, Integer> cpsMap = new HashMap<>();
    private final Map<Integer, UUID> botTargets = new HashMap<>();

    private final Map<UUID, Long> mutedPlayers = new HashMap<>();
    private final Map<UUID, Integer> swearCountMap = new HashMap<>();
    private final Map<UUID, Long> spamCheckMap = new HashMap<>();

    private final Map<UUID, Long> bannedPlayers = new HashMap<>();
    private final Map<UUID, String> banReasons = new HashMap<>();
    
    private final Set<String> bannedIps = new HashSet<>();
    private final Map<String, Long> ipBannedUntil = new HashMap<>();
    private final Map<String, String> ipBanReasons = new HashMap<>();
    private final Map<String, String> ipToPlayerName = new HashMap<>();
    private final Map<UUID, String> playerToIp = new HashMap<>();

    private final Set<String> reportSet = new HashSet<>();
    private final Set<UUID> activeBotSession = new HashSet<>();
    private final Map<UUID, String> botActionState = new HashMap<>();
    
    private final Map<UUID, List<LogEntry>> playerLogs = new HashMap<>();
    private final Map<UUID, Integer> playerLogOffsets = new HashMap<>();

    private final Map<UUID, UUID> playerBotMap = new HashMap<>();
    private final Map<UUID, UUID> playerAirBotMap = new HashMap<>();
    
    private final Map<UUID, Integer> diamondBreakCount = new HashMap<>();
    private final Map<UUID, Long> diamondResetTimer = new HashMap<>();

    private final List<String> forbiddenClients = Arrays.asList("wurst", "meteor", "yfend", "device", "arvion", "freecam");
    private final List<String> badWords = Arrays.asList("mal", "salak", "aptal", "gerizekalı", "gerzek", "amk", "aq", "orosp", "sik", "anan", "a1tal");

    public static class LogEntry {
        public String hackType;
        public long timestamp;

        public LogEntry(String hackType, long timestamp) {
            this.hackType = hackType;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        try {
            this.getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", this);
            this.getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", this);
        } catch (Exception ignored) {}

        String[] cmds = {"axobot", "report", "reports", "banla", "banla-ip", "ban-ip", "banaç", "banaç-ip", "mutele", "muteaç", "bot", "bothava", "axologs"};
        for (String cmd : cmds) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }

        Bukkit.getScheduler().runTaskTimer(this, cpsMap::clear, 20L, 20L);
        
        // Zombi Botların kalkan durumunu ve döngüsünü sürekli tazeleyen görev
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID botUuid : playerBotMap.values()) {
                Entity e = Bukkit.getEntity(botUuid);
                if (e instanceof Zombie) {
                    Zombie z = (Zombie) e;
                    z.setHealth(z.getMaxHealth());
                    if (z.getEquipment().getItemInOffHand().getType() != Material.SHIELD) {
                        z.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
                    }
                }
            }
            for (UUID botUuid : playerAirBotMap.values()) {
                Entity e = Bukkit.getEntity(botUuid);
                if (e instanceof Zombie) {
                    Zombie z = (Zombie) e;
                    z.setHealth(z.getMaxHealth());
                    if (z.getEquipment().getItemInOffHand().getType() != Material.SHIELD) {
                        z.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
                    }
                }
            }
        }, 0L, 20L);

        getLogger().info("AxoGuard & AxoBot Gelişmiş Koruma Sürümü Aktif!");
    }

    private void addLog(UUID uuid, String hackType) {
        playerLogs.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new LogEntry(hackType, System.currentTimeMillis()));
    }

    private void flagPlayer(Player player, String hackType) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        addLog(uuid, hackType);

        int logCount = violations.getOrDefault(uuid, 0) + 1;
        violations.put(uuid, logCount);

        // Konsola ve loglara kesintisiz yansıtma
        getLogger().warning("[AxoGuard-Log] " + player.getName() + " adlı oyuncuda hile tespit edildi: " + hackType + " (" + logCount + "x)");

        int botId = (Math.abs(uuid.hashCode()) % 500) + 1;
        botTargets.put(botId, uuid);

        int ping = getPing(player);

        String alertMsg = ChatColor.GOLD + "AxoBot: Efendim, " + 
                ChatColor.YELLOW + player.getName() + " " +
                ChatColor.RED + "adlı oyuncuda " + 
                ChatColor.DARK_RED + hackType + " " +
                ChatColor.RED + "tespit ettim (" + logCount + "/4x) " +
                ChatColor.DARK_GRAY + "[" + ping + "ms]";

        for (Player op : Bukkit.getOnlinePlayers()) {
            if (op.isOp() || op.hasPermission("axoguard.admin")) {
                op.playSound(op.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);

                TextComponent message = new TextComponent(alertMsg);
                TextComponent btn = new TextComponent("\n" + ChatColor.AQUA + "   [Beraber İzle - AxoBot #" + botId + "]");
                btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axobot izle " + botId));
                btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.YELLOW + player.getName() + " oyuncusunu izlemek için tıkla!").create()));

                message.addExtra(btn);
                op.spigot().sendMessage(message);
            }
        }

        // İstek üzerine: Attack Aura ve ElytraTarget için 4x limit
        int maxLog = (hackType.toLowerCase().contains("aura") || hackType.toLowerCase().contains("elytratarget")) ? 4 : 12;

        if (logCount >= maxLog) {
            String kickKey = uuid.toString() + "_" + hackType;
            int hileKicks = hackKickCountMap.getOrDefault(kickKey, 0) + 1;
            hackKickCountMap.put(kickKey, hileKicks);
            violations.put(uuid, 0);

            if (hileKicks >= 2) {
                triggerBanSequence(player, hackType);
            } else {
                kickPlayer(player, hackType, hileKicks);
            }
        }
    }

    private void triggerBanSequence(Player player, String reason) {
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.setVelocity(new Vector(0, 0, 0));

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
        player.getInventory().clear();

        List<Llama> lamas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = i * ((Math.PI * 2) / 3);
            Location spawnLoc = player.getLocation().add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3);
            Llama lama = player.getWorld().spawn(spawnLoc, Llama.class);
            lama.setCustomName(ChatColor.RED + "AxoBot Muhafız #" + (i + 1));
            lama.setCustomNameVisible(true);
            lama.setAI(false);
            lama.setTarget(player);
            lamas.add(lama);
        }

        int taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (player.isOnline()) {
                Vector vel = player.getVelocity();
                player.setVelocity(new Vector(vel.getX(), 0.08, vel.getZ()));
            }
        }, 0L, 2L).getTaskId();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.getScheduler().cancelTask(taskId);
            for (Llama lama : lamas) {
                if (lama.isValid()) lama.remove();
            }
            if (player.isOnline()) {
                executeBan(player.getName(), "Console", "14g", "Hile Kullanımı (Aynı hileden 2. Kick)");
            }
        }, 60L);
    }

    private int getPing(Player player) {
        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            return (int) entityPlayer.getClass().getField("ping").get(entityPlayer);
        } catch (Exception e) {
            return 25;
        }
    }

    private void kickPlayer(Player player, String hackType, int kickNum) {
        String kickReason = ChatColor.RED + "AxoAC - Güvenlik Sistemi\n\n" +
                ChatColor.YELLOW + "Sunucumuzda hile kullanımı tespit edildi!\n" +
                ChatColor.RED + "Sebep: " + ChatColor.YELLOW + hackType + " (" + kickNum + "/2 Kick)\n\n" +
                ChatColor.AQUA + "discord.gg/axocraft";

        player.kickPlayer(kickReason);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + player.getName() + ChatColor.RED + " adlı oyuncu console tarafından hile kullanımı (" + hackType + ") nedeniyle atıldı! [" + kickNum + "x Kick]");
    }

    private String formatDurationDetailed(String durationStr) {
        try {
            durationStr = durationStr.toLowerCase().trim();
            long val = Long.parseLong(durationStr.replaceAll("[^0-9]", ""));
            if (durationStr.endsWith("g") || durationStr.endsWith("gün")) {
                return val + " Gün";
            } else if (durationStr.endsWith("h") || durationStr.endsWith("saat")) {
                return val + " Saat";
            } else if (durationStr.endsWith("d") || durationStr.endsWith("dka") || durationStr.endsWith("dakika")) {
                return val + " Dakika";
            } else if (durationStr.endsWith("s") || durationStr.endsWith("saniye")) {
                return val + " Saniye";
            }
        } catch (Exception ignored) {}
        return durationStr.toUpperCase();
    }

    private void executeBan(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        long durationMs = parseDuration(durationStr);
        bannedPlayers.put(uuid, System.currentTimeMillis() + durationMs);
        banReasons.put(uuid, reason);

        String dateStr = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        String detailedDuration = formatDurationDetailed(durationStr);

        String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN YASAKLANDINIZ\n\n" +
                ChatColor.WHITE + "⏰ TARİH: " + ChatColor.YELLOW + dateStr + "\n" +
                ChatColor.WHITE + "🔨 YASAKLAYAN: " + ChatColor.AQUA + executor + "\n" +
                ChatColor.WHITE + "🏷 SEBEP: " + ChatColor.LIGHT_PURPLE + reason + "\n" +
                ChatColor.WHITE + "⌛ SÜRE: " + ChatColor.LIGHT_PURPLE + detailedDuration + "\n\n" +
                ChatColor.AQUA + "İTİRAZ EDEBİLİRSİNİZ:\n" +
                ChatColor.WHITE + "discord.gg/axocraft";

        if (target != null && target.isOnline()) {
            target.kickPlayer(banScreen);
        }

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + targetName + ChatColor.RED + " adlı oyuncu console tarafından banlandı!");
    }

    private void executeIpBan(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        String ip = target != null && target.getAddress() != null ? target.getAddress().getAddress().getHostAddress() : null;

        if (ip == null) {
            UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            ip = playerToIp.get(uuid);
        }

        if (ip != null) {
            long durationMs = parseDuration(durationStr);
            bannedIps.add(ip);
            ipBannedUntil.put(ip, System.currentTimeMillis() + durationMs);
            ipBanReasons.put(ip, reason);
            ipToPlayerName.put(ip, targetName);
        }

        Player onlineTarget = Bukkit.getPlayer(targetName);
        UUID uuid = onlineTarget != null ? onlineTarget.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
        long durationMs = parseDuration(durationStr);
        bannedPlayers.put(uuid, System.currentTimeMillis() + durationMs);
        banReasons.put(uuid, reason);

        String dateStr = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        String detailedDuration = formatDurationDetailed(durationStr);

        String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN YASAKLANDINIZ (IP BAN)\n\n" +
                ChatColor.WHITE + "⏰ TARİH: " + ChatColor.YELLOW + dateStr + "\n" +
                ChatColor.WHITE + "🔨 YASAKLAYAN: " + ChatColor.AQUA + executor + "\n" +
                ChatColor.WHITE + "🏷 SEBEP: " + ChatColor.LIGHT_PURPLE + reason + "\n" +
                ChatColor.WHITE + "⌛ SÜRE: " + ChatColor.LIGHT_PURPLE + detailedDuration + "\n\n" +
                ChatColor.AQUA + "İTİRAZ EDEBİLİRSİNİZ:\n" +
                ChatColor.WHITE + "discord.gg/axocraft";

        if (onlineTarget != null && onlineTarget.isOnline()) {
            onlineTarget.kickPlayer(banScreen);
        }

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + targetName + ChatColor.RED + " adlı oyuncuya IP Ban atıldı!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";

        playerToIp.put(uuid, ip);

        if (bannedIps.contains(ip)) {
            long expireTime = ipBannedUntil.getOrDefault(ip, 0L);
            if (System.currentTimeMillis() < expireTime) {
                String reason = ipBanReasons.getOrDefault(ip, "IP Ban");
                String dateStr = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
                String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                        ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN YASAKLANDINIZ (IP BAN)\n\n" +
                        ChatColor.WHITE + "⏰ TARİH: " + ChatColor.YELLOW + dateStr + "\n" +
                        ChatColor.WHITE + "🔨 YASAKLAYAN: " + ChatColor.AQUA + "Console\n" +
                        ChatColor.WHITE + "🏷 SEBEP: " + ChatColor.LIGHT_PURPLE + reason + "\n\n" +
                        ChatColor.AQUA + "İTİRAZ EDEBİLİRSİNİZ:\n" +
                        ChatColor.WHITE + "discord.gg/axocraft";
                player.kickPlayer(banScreen);
                event.setJoinMessage(null);
                return;
            } else {
                bannedIps.remove(ip);
                ipBannedUntil.remove(ip);
            }
        }

        if (bannedPlayers.containsKey(uuid)) {
            long expireTime = bannedPlayers.get(uuid);
            if (System.currentTimeMillis() < expireTime) {
                String reason = banReasons.getOrDefault(uuid, "Yasaklandınız");
                String dateStr = new SimpleDateFormat("yyyy/MM/dd").format(new Date());

                String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                        ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN YASAKLANDINIZ\n\n" +
                        ChatColor.WHITE + "⏰ TARİH: " + ChatColor.YELLOW + dateStr + "\n" +
                        ChatColor.WHITE + "🔨 YASAKLAYAN: " + ChatColor.AQUA + "Console\n" +
                        ChatColor.WHITE + "🏷 SEBEP: " + ChatColor.LIGHT_PURPLE + reason + "\n\n" +
                        ChatColor.AQUA + "İTİRAZ EDEBİLİRSİNİZ:\n" +
                        ChatColor.WHITE + "discord.gg/axocraft";

                player.kickPlayer(banScreen);
                event.setJoinMessage(null);
                return;
            } else {
                bannedPlayers.remove(uuid);
                banReasons.remove(uuid);
            }
        }

        violations.remove(uuid);
        event.setJoinMessage(null);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (playerBotMap.containsValue(entity.getUniqueId()) || playerAirBotMap.containsValue(entity.getUniqueId())) {
            if (entity instanceof LivingEntity) {
                LivingEntity bot = (LivingEntity) entity;
                bot.setHealth(bot.getMaxHealth());
                event.setCancelled(true); // Botun zarar görmesini tamamen engelle
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel.equalsIgnoreCase("MC|Brand") || channel.equalsIgnoreCase("minecraft:brand")) {
            try {
                String brand = new String(message, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();

                for (String forbidden : forbiddenClients) {
                    if (brand.contains(forbidden)) {
                        player.kickPlayer(ChatColor.RED + "AxoGuard Güvenlik Sistemi\n\n" +
                                ChatColor.YELLOW + "Giriş engellendi! Sunucumuzda yasaklı mod/client tespit edildi.\n" +
                                ChatColor.RED + "Kullandığınız Yasaklı Mod: " + ChatColor.GOLD + forbidden.toUpperCase() + "\n\n" +
                                ChatColor.WHITE + "Lütfen bu modu kaldırıp tekrar giriş yapın!");
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void executeMute(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) return;

        long durationMs = parseDuration(durationStr);
        mutedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + durationMs);

        String detailedDuration = formatDurationDetailed(durationStr);
        target.sendMessage(ChatColor.RED + "Sohbet yetkiniz " + detailedDuration + " süreyle kısıtlandı!");
    }

    private long parseDuration(String input) {
        try {
            input = input.toLowerCase().trim();
            long val = Long.parseLong(input.replaceAll("[^0-9]", ""));
            if (input.endsWith("g") || input.endsWith("gün")) {
                return val * 24 * 60 * 60 * 1000L;
            } else if (input.endsWith("h") || input.endsWith("saat")) {
                return val * 60 * 60 * 1000L;
            } else if (input.endsWith("d") || input.endsWith("dka") || input.endsWith("dakika")) {
                return val * 60 * 1000L;
            } else if (input.endsWith("s") || input.endsWith("saniye")) {
                return val * 1000L;
            }
        } catch (Exception ignored) {}
        return 24 * 60 * 60 * 1000L;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String msg = event.getMessage().toLowerCase();

        if (mutedPlayers.containsKey(uuid)) {
            long expireTime = mutedPlayers.get(uuid);
            if (System.currentTimeMillis() < expireTime) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Şu anda mutelisiniz! Sohbete yazı yazamazsınız.");
                return;
            } else {
                mutedPlayers.remove(uuid);
                player.sendMessage(ChatColor.GREEN + "Mutenizin süresi doldu.");
            }
        }

        // Küfür ve argo filtresi
        boolean hasBadWord = false;
        for (String bad : badWords) {
            if (msg.contains(bad)) {
                hasBadWord = true;
                break;
            }
        }

        if (hasBadWord) {
            event.setCancelled(true);
            int swears = swearCountMap.getOrDefault(uuid, 0) + 1;
            swearCountMap.put(uuid, swears);

            player.sendMessage(ChatColor.RED + "Mesajınız engellendi!");

            if (swears >= 4) {
                mutedPlayers.put(uuid, System.currentTimeMillis() + (60 * 60 * 1000L));
                swearCountMap.put(uuid, 0);
                player.sendMessage(ChatColor.RED + "Küfür/argo kullanımından dolayı 1 saat süreyle mute yediniz!");
            }
            return;
        }

        // Spam kontrolü
        long lastMsgTime = spamCheckMap.getOrDefault(uuid, 0L);
        if (System.currentTimeMillis() - lastMsgTime < 1500 && !player.isOp()) {
            event.setCancelled(true);
            mutedPlayers.put(uuid, System.currentTimeMillis() + (15 * 60 * 1000L));
            player.sendMessage(ChatColor.RED + "Spam yaptığınız için 15 dakika süreyle mute yediniz!");
            return;
        }
        spamCheckMap.put(uuid, System.currentTimeMillis());

        // Bot ile Gizli Sohbet Sistemi
        if (activeBotSession.contains(uuid) && player.isOp()) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.GOLD + "[Gizli Sohbet] [Sen] " + ChatColor.WHITE + event.getMessage());
            
            Bukkit.getScheduler().runTask(this, () -> {
                String botReply;
                String lower = event.getMessage().toLowerCase().trim();
                String currentState = botActionState.getOrDefault(uuid, "normal");

                if (lower.equals("sa") || lower.contains("selamün aleyküm") || lower.contains("selam")) {
                    botReply = "AxoBot: Aleykümselam efendim, hoş geldiniz.";
                } else if (lower.contains("merhaba")) {
                    botReply = "AxoBot: Merhaba efendim, nasılsınız?";
                } else if (lower.contains("napıyon") || lower.contains("ne yapıyon") || lower.contains("ne yapıyorsun")) {
                    botReply = "AxoBot: Efendim, şüpheli oyuncuları gözetliyorum ve log analizi yapıyorum.";
                } else if (lower.contains("az daha izle") || lower.contains("bekle")) {
                    botActionState.put(uuid, "izle");
                    botReply = "AxoBot: Emredersiniz efendim, oyuncuyu az daha izlemeye devam ediyorum.";
                } else if (lower.contains("banla") || lower.contains("at şunu")) {
                    botReply = "AxoBot: Efendim deliller tamam, hemen ban atıyorum / kick atıyorum!";
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoBot] İşlem başarıyla uygulandı.");
                } else if (currentState.equals("izle")) {
                    botReply = "AxoBot: Efendim izliyorum, veriler toplanıyor.";
                } else {
                    botReply = "AxoBot: Emredersiniz efendim, takipteyim!";
                }
                player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] " + botReply);
            });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Material type = event.getBlock().getType();
        if (type.name().contains("DIAMOND") || type.name().contains("EMERALD") || type.name().contains("ANCIENT_DEBRIS")) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();

            long resetTime = diamondResetTimer.getOrDefault(uuid, 0L);
            if (now > resetTime) {
                diamondBreakCount.put(uuid, 1);
                diamondResetTimer.put(uuid, now + 30000L);
            } else {
                int count = diamondBreakCount.getOrDefault(uuid, 0) + 1;
                diamondBreakCount.put(uuid, count);
                if (count >= 4) {
                    flagPlayer(player, "Xray / OreFinder");
                }
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            UUID uuid = attacker.getUniqueId();

            if (hitBlockTime.containsKey(uuid) && System.currentTimeMillis() < hitBlockTime.get(uuid)) {
                if (Math.random() < 0.4) {
                    event.setCancelled(true);
                    return;
                } else {
                    event.setDamage(Math.min(event.getDamage(), 2.0)); 
                }
            }

            ItemStack mainHand = attacker.getInventory().getItemInMainHand();
            boolean hasAxe = mainHand.getType().name().contains("AXE");
            if (event.getEntity() instanceof Player) {
                Player victim = (Player) event.getEntity();
                if (victim.isBlocking() && !hasAxe) {
                    flagPlayer(attacker, "ShieldBreaker");
                }
            }

            Entity victimEntity = event.getEntity();
            boolean isBotTarget = playerBotMap.containsValue(victimEntity.getUniqueId()) || playerAirBotMap.containsValue(victimEntity.getUniqueId());

            if (victimEntity instanceof LivingEntity || isBotTarget) {
                Location attackerLoc = attacker.getLocation();
                Location victimLoc = victimEntity.getLocation();
                double distance = attackerLoc.distance(victimLoc);

                Vector toVictim = victimLoc.toVector().subtract(attackerLoc.toVector()).normalize();
                Vector lookDir = attackerLoc.getDirection().normalize();
                double dot = lookDir.dot(toVictim);

                if (attacker.getGameMode() != GameMode.CREATIVE) {
                    boolean isInvalidDistance = distance > 4.2;
                    boolean isBackHitAura = distance > 3.4 && dot < -0.3;

                    if (isInvalidDistance || isBackHitAura) {
                        event.setDamage(Math.min(event.getDamage(), 2.0)); 
                        
                        if (attacker.isGliding() || attacker.getVelocity().getY() > 0.1) {
                            flagPlayer(attacker, "ElytraTargetC");
                        } else {
                            flagPlayer(attacker, "Attack Aura");
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        double distance = from.distance(to);

        if (player.isGliding()) {
            boolean hasFireworkInHand = player.getInventory().getItemInMainHand().getType() == Material.FIREWORK_ROCKET ||
                                      player.getInventory().getItemInOffHand().getType() == Material.FIREWORK_ROCKET;
            boolean hasFireworkInInv = player.getInventory().contains(Material.FIREWORK_ROCKET);

            // İstek üzerine: Elinde fişek yokken envanterde fişek olup havada basma tespiti
            if (distance > 1.8 && to.getY() >= from.getY() && (!hasFireworkInHand && hasFireworkInInv)) {
                event.setTo(from);
                flagPlayer(player, "ElytraTargetC (Envanter Fişek Hilesi)");
                return;
            }
        }

        if (!player.isGliding() && !player.isFlying()) {
            boolean isNearGround = player.getLocation().subtract(0, 1, 0).getBlock().getType().isSolid();

            if (distance > 1.15 && !isNearGround && player.getVelocity().getY() >= -0.3 && from.getY() == to.getY()) {
                event.setTo(from);
                flagPlayer(player, "Speed");
            }

            if (to.getY() - from.getY() > 1.35 && !isNearGround && !player.getLocation().getBlock().getType().isSolid()) {
                event.setTo(from);
                flagPlayer(player, "Flight");
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        int cps = cpsMap.getOrDefault(uuid, 0) + 1;
        cpsMap.put(uuid, cps);

        if (cps > 20) {
            flagPlayer(player, "AutoClicker");
        }
    }

    public void openAxoLogsPlayersGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "AxoGuard - Oyuncu Seçin");
        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (slot >= 54) break;
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            meta.setLore(Arrays.asList(ChatColor.AQUA + "Logları Görmek İçin Tıkla"));
            skull.setItemMeta(meta);
            gui.setItem(slot++, skull);
        }
        player.openInventory(gui);
    }

    public void openPlayerLogsGUI(Player player, UUID targetUuid, int dayOffset) {
        playerLogOffsets.put(player.getUniqueId(), dayOffset);
        String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Loglar: " + targetName);

        List<LogEntry> allLogs = playerLogs.getOrDefault(targetUuid, new ArrayList<>());
        int slot = 0;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

        for (LogEntry log : allLogs) {
            if (slot >= 45) break;
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta meta = paper.getItemMeta();
            meta.setDisplayName(ChatColor.RED + log.hackType);
            meta.setLore(Arrays.asList(ChatColor.YELLOW + "Zaman: " + ChatColor.WHITE + sdf.format(new Date(log.timestamp))));
            paper.setItemMeta(meta);
            gui.setItem(slot++, paper);
        }
        player.openInventory(gui);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        String cmd = label.toLowerCase();

        if (cmd.equals("report")) {
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Kullanım: /report <oyuncu>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Oyuncu bulunamadı!");
                return true;
            }
            reportSet.add(target.getName());
            openReportMenu(player, target.getName());
            return true;
        }

        if (cmd.equals("axologs")) {
            openAxoLogsPlayersGUI(player);
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Böyle bir komut bulunamadı!");
            return true;
        }

        if (cmd.equals("bot")) {
            UUID uuid = player.getUniqueId();
            if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
                if (playerBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten aktif bir botun var!");
                    return true;
                }
                Zombie bot = player.getWorld().spawn(player.getLocation(), Zombie.class);
                bot.setCustomName(ChatColor.YELLOW + "axoac afk bot");
                bot.setCustomNameVisible(true);
                bot.setAI(false);
                bot.setInvulnerable(true);
                bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 200, false, false));
                bot.addPotionEffect(new PotionEffect(PotionEffectType.HEAL, Integer.MAX_VALUE, 200, false, false));

                playerBotMap.put(uuid, bot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Ölmez Zombi AFK bot çağrıldı!");
                return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("kalkan")) {
                if (playerBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten aktif bir botun var!");
                    return true;
                }
                Zombie bot = player.getWorld().spawn(player.getLocation(), Zombie.class);
                bot.setCustomName(ChatColor.GOLD + "axoac afk bot (Kalkanlı)");
                bot.setCustomNameVisible(true);
                bot.setAI(false);
                bot.setInvulnerable(true);
                bot.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
                bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 200, false, false));
                bot.addPotionEffect(new PotionEffect(PotionEffectType.HEAL, Integer.MAX_VALUE, 200, false, false));

                playerBotMap.put(uuid, bot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Kalkanlı Zombi AFK bot çağrıldı!");
                return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("kapa")) {
                UUID botUuid = playerBotMap.remove(uuid);
                if (botUuid != null) {
                    Entity e = Bukkit.getEntity(botUuid);
                    if (e != null) e.remove();
                    player.sendMessage(ChatColor.GREEN + "Bot kapatıldı.");
                } else {
                    player.sendMessage(ChatColor.RED + "Aktif botun yok!");
                }
                return true;
            }
        }

        if (cmd.equals("bothava")) {
            UUID uuid = player.getUniqueId();
            if (args.length > 0 && args[0].equalsIgnoreCase("kapa")) {
                UUID botUuid = playerAirBotMap.remove(uuid);
                if (botUuid != null) {
                    Entity e = Bukkit.getEntity(botUuid);
                    if (e != null) e.remove();
                    player.sendMessage(ChatColor.GREEN + "Havadaki bot kapatıldı.");
                } else {
                    player.sendMessage(ChatColor.RED + "Hava botun yok!");
                }
                return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("kalkan")) {
                if (playerAirBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten havada botun var!");
                    return true;
                }
                Location airLoc = player.getLocation().add(0, 20, 0);
                Zombie airBot = player.getWorld().spawn(airLoc, Zombie.class);
                airBot.setCustomName(ChatColor.GOLD + "axoac afk bot (Kalkanlı)");
                airBot.setCustomNameVisible(true);
                airBot.setGravity(false);
                airBot.setAI(false);
                airBot.setInvulnerable(true);
                airBot.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
                airBot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 200, false, false));
                airBot.addPotionEffect(new PotionEffect(PotionEffectType.HEAL, Integer.MAX_VALUE, 200, false, false));

                playerAirBotMap.put(uuid, airBot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "20 blok yukarıda kalkanlı Zombi hava botu oluşturuldu!");
                return true;
            } else {
                if (playerAirBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten havada botun var!");
                    return true;
                }
                Location airLoc = player.getLocation().add(0, 20, 0);
                Zombie airBot = player.getWorld().spawn(airLoc, Zombie.class);
                airBot.setCustomName(ChatColor.LIGHT_PURPLE + "axoac afk bot");
                airBot.setCustomNameVisible(true);
                airBot.setGravity(false);
                airBot.setAI(false);
                airBot.setInvulnerable(true);
                airBot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 200, false, false));
                airBot.addPotionEffect(new PotionEffect(PotionEffectType.HEAL, Integer.MAX_VALUE, 200, false, false));

                playerAirBotMap.put(uuid, airBot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "20 blok yukarıda Zombi hava botu oluşturuldu!");
                return true;
            }
        }

        if (cmd.equals("reports")) {
            openReportsGUI(player);
            return true;
        }

        if (cmd.equals("banla")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /banla <oyuncu> <süre> <sebep>");
                return true;
            }
            executeBan(args[0], player.getName(), args[1], args[2]);
            return true;
        }

        if (cmd.equals("axobot")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("izle")) {
                try {
                    int id = Integer.parseInt(args[1]);
                    UUID targetUuid = botTargets.get(id);
                    if (targetUuid != null && Bukkit.getPlayer(targetUuid) != null) {
                        Player target = Bukkit.getPlayer(targetUuid);
                        player.teleport(target.getLocation());
                        activeBotSession.add(player.getUniqueId());
                        player.sendMessage(ChatColor.AQUA + "AxoBot: İzlemeye başlandı. Sohbetten benimle konuşabilirsin.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Geçersiz Bot ID veya hedef çevrimdışı!");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Hatalı ID formatı!");
                }
            }
            return true;
        }

        return true;
    }

    public void openReportMenu(Player player, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Şikayet: " + targetName);
        ItemStack hile = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = hile.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "Hile Bildirildi: " + targetName);
        hile.setItemMeta(meta);
        gui.setItem(13, hile);
        player.openInventory(gui);
    }

    public void openReportsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Reportlanan Oyuncular");
        int slot = 0;
        for (String name : reportSet) {
            if (slot >= 54) break;
            Player t = Bukkit.getPlayer(name);
            if (t != null && t.isOnline()) {
                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) skull.getItemMeta();
                meta.setOwningPlayer(t);
                meta.setDisplayName(ChatColor.YELLOW + t.getName());
                meta.setLore(Arrays.asList(ChatColor.AQUA + "İzlemek İçin Tıkla"));
                skull.setItemMeta(meta);
                gui.setItem(slot++, skull);
            }
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void openReportsGUIHandler(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();

        if (title.equals(ChatColor.DARK_RED + "Reportlanan Oyuncular")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            String targetName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayer(targetName);

            if (target != null && target.isOnline()) {
                player.closeInventory();
                player.teleport(target.getLocation());
                activeBotSession.add(player.getUniqueId());
                player.sendMessage(ChatColor.AQUA + "AxoBot: " + target.getName() + " oyuncusunun yanına ışınlandık, izliyoruz.");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("bot") || cmd.equals("bothava")) {
            if (args.length == 1) return Arrays.asList("spawn", "kalkan", "kapa");
        }
        return Collections.emptyList();
    }
}
