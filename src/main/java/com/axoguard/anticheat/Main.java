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

    private boolean consoleLogsEnabled = true;
    private boolean gameLogsEnabled = true;

    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> suspicionStage = new HashMap<>();
    private final Map<String, Integer> hackKickCountMap = new HashMap<>();
    private final Map<UUID, Long> hitBlockTime = new HashMap<>();
    private final Map<UUID, Integer> cpsMap = new HashMap<>();
    private final Map<Integer, UUID> botTargets = new HashMap<>();
    private final Map<UUID, UUID> adminWatchingTarget = new HashMap<>();

    private final Map<UUID, Long> mutedPlayers = new HashMap<>();
    private final Map<UUID, Integer> swearCountMap = new HashMap<>();
    private final Map<UUID, Long> spamCheckMap = new HashMap<>();

    private final Map<UUID, Long> bannedPlayers = new HashMap<>();
    private final Map<UUID, String> banReasons = new HashMap<>();
    private final Map<UUID, String> banStartTimes = new HashMap<>();
    private final Map<UUID, String> banEndTimes = new HashMap<>();
    
    private final Set<String> bannedIps = new HashSet<>();
    private final Map<String, Long> ipBannedUntil = new HashMap<>();
    private final Map<String, String> ipBanReasons = new HashMap<>();
    private final Map<String, String> ipToPlayerName = new HashMap<>();
    private final Map<UUID, String> playerToIp = new HashMap<>();

    private final Set<String> reportSet = new HashSet<>();
    private final Set<UUID> activeBotSession = new HashSet<>();
    
    private final Map<UUID, List<LogEntry>> playerLogs = new HashMap<>();
    private final Map<UUID, Integer> playerLogOffsets = new HashMap<>();

    private final Map<UUID, UUID> playerBotMap = new HashMap<>();
    private final Map<UUID, UUID> playerAirBotMap = new HashMap<>();
    
    // Maden koruması için sayaç toleransı
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
        
        // Zombi Botların kalkan durumunu ve canını tazeleyen döngü
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
        
        // Legit vuruşları korumak için eşik kontrolü (Normal vs ve kazımalarda asla tetiklenmez)
        hitBlockTime.put(uuid, System.currentTimeMillis() + 4000);

        int stage = suspicionStage.getOrDefault(uuid, 0);
        if (stage == 0) {
            suspicionStage.put(uuid, 1);
            String preAlert = "AxoBot: Efendim, " + player.getName() + " adlı oyuncu hile (" + hackType + ") gibi görünüyor, izlemeye devam ediyorum. Log düşürmeye başlayacağım.";
            
            if (consoleLogsEnabled) {
                Bukkit.getLogger().warning("[AxoBot-Konsol] " + preAlert);
            }
            for (Player op : Bukkit.getOnlinePlayers()) {
                if ((op.isOp() || op.hasPermission("axoguard.admin")) && gameLogsEnabled) {
                    op.sendMessage(ChatColor.YELLOW + preAlert);
                }
            }
            return;
        }

        addLog(uuid, hackType);
        int logCount = violations.getOrDefault(uuid, 0) + 1;
        violations.put(uuid, logCount);

        String consoleLogMsg = "[AxoGuard-Konsol] Hile Tespit Edildi! Oyuncu: " + player.getName() + " | Tür: " + hackType + " | Sınır: " + logCount + "/4x";
        if (consoleLogsEnabled) {
            Bukkit.getLogger().severe(consoleLogMsg);
        }

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
            if ((op.isOp() || op.hasPermission("axoguard.admin")) && gameLogsEnabled) {
                op.playSound(op.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);

                TextComponent message = new TextComponent(alertMsg);
                TextComponent btn = new TextComponent("\n" + ChatColor.AQUA + "   [Beraber İzle - AxoBot #" + botId + "]");
                btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axobot izle " + botId));
                btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.YELLOW + player.getName() + " oyuncusunu izlemek için tıkla!").create()));

                message.addExtra(btn);
                op.spigot().sendMessage(message);
            }
        }

        int maxLog = 4; // İstek üzerine tüm ana hile korumaları 4x sınır

        if (logCount >= maxLog) {
            String kickKey = uuid.toString() + "_" + hackType;
            int hileKicks = hackKickCountMap.getOrDefault(kickKey, 0) + 1;
            hackKickCountMap.put(kickKey, hileKicks);
            violations.put(uuid, 0);
            suspicionStage.put(uuid, 0);

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
                executeBan(player.getName(), "console", "14g", "Hile Kullanımı (Aynı hileden 2. Kick)");
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
                ChatColor.YELLOW + "Sunucumuzdan uzaklaştırıldınız!\n" +
                ChatColor.RED + "Atan: " + ChatColor.AQUA + "console\n" +
                ChatColor.RED + "Sebep: " + ChatColor.YELLOW + hackType + " (" + kickNum + "/2 Kick)\n\n" +
                ChatColor.WHITE + "discord.gg/axocraft";

        player.kickPlayer(kickReason);
        String broadcastMsg = "[AxoGuard] " + player.getName() + " adlı oyuncu console tarafından hile kullanımı (" + hackType + ") nedeniyle atıldı! [" + kickNum + "x Kick]";
        if (consoleLogsEnabled) {
            Bukkit.getLogger().warning(broadcastMsg);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + broadcastMsg);
    }

    private String formatDurationDetailed(String durationStr) {
        try {
            durationStr = durationStr.toLowerCase().trim();
            long val = Long.parseLong(durationStr.replaceAll("[^0-9]", ""));
            if (durationStr.endsWith("g") || durationStr.endsWith("gün")) return val + " Gün";
            else if (durationStr.endsWith("h") || durationStr.endsWith("saat")) return val + " Saat";
            else if (durationStr.endsWith("d")) return val + " Dakika";
        } catch (Exception ignored) {}
        return durationStr.toUpperCase();
    }

    private void executeBan(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        long durationMs = parseDuration(durationStr);
        long endTimeMs = System.currentTimeMillis() + durationMs;

        bannedPlayers.put(uuid, endTimeMs);
        banReasons.put(uuid, reason);

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        String startTimeStr = sdf.format(new Date());
        String endTimeStr = sdf.format(new Date(endTimeMs));

        banStartTimes.put(uuid, startTimeStr);
        banEndTimes.put(uuid, endTimeStr);

        String detailedDuration = formatDurationDetailed(durationStr);

        String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN UZAKLAŞTIRILDINIZ\n\n" +
                ChatColor.WHITE + "Başlangıç: " + ChatColor.YELLOW + startTimeStr + "\n" +
                ChatColor.WHITE + "Bitiş: " + ChatColor.YELLOW + endTimeStr + "\n" +
                ChatColor.WHITE + "Süre: " + ChatColor.LIGHT_PURPLE + detailedDuration + "\n" +
                ChatColor.WHITE + "Atan: " + ChatColor.AQUA + executor + "\n" +
                ChatColor.WHITE + "Sebep: " + ChatColor.RED + reason + "\n\n" +
                ChatColor.AQUA + "discord.gg/axocraft";

        if (target != null && target.isOnline()) {
            target.kickPlayer(banScreen);
        }

        String banMsg = "[AxoGuard] " + targetName + " adlı oyuncu yasaklandı! Süre: " + detailedDuration + " | Sebep: " + reason;
        if (consoleLogsEnabled) {
            Bukkit.getLogger().severe(banMsg);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + banMsg);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";
        playerToIp.put(uuid, ip);

        // Ban kontrolü: Süre bitene kadar kesinlikle giriş yapamaz
        if (bannedPlayers.containsKey(uuid)) {
            long expireTime = bannedPlayers.get(uuid);
            if (System.currentTimeMillis() < expireTime) {
                String reason = banReasons.getOrDefault(uuid, "Hile Kullanımı");
                String startTime = banStartTimes.getOrDefault(uuid, "-");
                String endTime = banEndTimes.getOrDefault(uuid, "-");

                String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                        ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN UZAKLAŞTIRILDINIZ\n\n" +
                        ChatColor.WHITE + "Başlangıç: " + ChatColor.YELLOW + startTime + "\n" +
                        ChatColor.WHITE + "Bitiş: " + ChatColor.YELLOW + endTime + "\n" +
                        ChatColor.WHITE + "Atan: " + ChatColor.AQUA + "console\n" +
                        ChatColor.WHITE + "Sebep: " + ChatColor.RED + reason + "\n\n" +
                        ChatColor.AQUA + "discord.gg/axocraft";

                player.kickPlayer(banScreen);
                event.setJoinMessage(null);
                return;
            } else {
                bannedPlayers.remove(uuid);
                banReasons.remove(uuid);
            }
        }

        violations.remove(uuid);
        suspicionStage.remove(uuid);
        event.setJoinMessage(null);

        // Action Bar üzerinde yeşil giriş bildirimi (+ [Oyuncu])
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GREEN + "+ [" + player.getName() + "]"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        Player player = event.getPlayer();
        // Action Bar üzerinde kırmızı çıkış bildirimi (- [Oyuncu])
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "- [" + player.getName() + "]"));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (playerBotMap.containsValue(entity.getUniqueId()) || playerAirBotMap.containsValue(entity.getUniqueId())) {
            if (entity instanceof LivingEntity) {
                LivingEntity bot = (LivingEntity) entity;
                bot.setHealth(bot.getMaxHealth());
                event.setCancelled(true);
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
                        player.kickPlayer(ChatColor.RED + "Yasaklı client tespit edildi: " + forbidden);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private long parseDuration(String input) {
        try {
            input = input.toLowerCase().trim();
            long val = Long.parseLong(input.replaceAll("[^0-9]", ""));
            if (input.endsWith("g") || input.endsWith("gün")) return val * 24 * 60 * 60 * 1000L;
            else if (input.endsWith("h") || input.endsWith("saat")) return val * 60 * 60 * 1000L;
            else if (input.endsWith("d")) return val * 60 * 1000L;
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
                player.sendMessage(ChatColor.RED + "Mutelisiniz!");
                return;
            }
        }

        // AxoBot Gizli Sohbet ve Konsol Komut İşleme
        if (activeBotSession.contains(uuid) && (player.isOp() || player.hasPermission("axoguard.admin"))) {
            event.setCancelled(true);
            String rawMsg = event.getMessage();
            player.sendMessage(ChatColor.GOLD + "[Gizli Sohbet] [Sen] " + ChatColor.WHITE + rawMsg);
            
            if (consoleLogsEnabled) {
                Bukkit.getLogger().info("[AxoBot-Sohbet] " + player.getName() + " -> " + rawMsg);
            }

            Bukkit.getScheduler().runTask(this, () -> {
                String lower = rawMsg.toLowerCase().trim();
                UUID watchedUuid = adminWatchingTarget.get(uuid);
                Player watchedPlayer = watchedUuid != null ? Bukkit.getPlayer(watchedUuid) : null;

                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (lower.contains(online.getName().toLowerCase())) {
                        watchedPlayer = online;
                        break;
                    }
                }

                if (lower.contains("kickle") || lower.contains("kick at")) {
                    if (watchedPlayer != null && watchedPlayer.isOnline()) {
                        player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Emredersiniz efendim, " + watchedPlayer.getName() + " oyuncusunu kickliyorum!");
                        kickPlayer(watchedPlayer, "Yetkili Kararı (AxoBot)", 1);
                    } else {
                        player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Efendim aktif izlenen hedef yok, lütfen oyuncu adını belirtin.");
                    }
                } else if (lower.contains("banla") || lower.contains("ban at")) {
                    if (watchedPlayer != null && watchedPlayer.isOnline()) {
                        player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Anlaşıldı efendim, " + watchedPlayer.getName() + " banlanıyor!");
                        executeBan(watchedPlayer.getName(), "console", "14g", "Hile Kullanımı (AxoBot)");
                    } else {
                        player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Efendim hedef bulunamadı.");
                    }
                } else if (lower.equals("sa") || lower.contains("selam")) {
                    player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Aleykümselam efendim!");
                } else if (lower.contains("naber") || lower.contains("nasılsın")) {
                    player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: İyiyim efendim, hile avındayım.");
                } else {
                    player.sendMessage(ChatColor.AQUA + "[Gizli Sohbet] AxoBot: Emredersiniz efendim, takipteyim.");
                }
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
                if (count >= 6) { // Maden kazarken yanlış pozitifleri önlemek için eşik artırıldı
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
            Entity victimEntity = event.getEntity();

            if (attacker.getGameMode() == GameMode.CREATIVE) return;

            // Sadece hile tespit edilen oyuncu takılır ve vuruşu engellenir (Diğer oyunculara etki etmez)
            if (hitBlockTime.containsKey(uuid) && System.currentTimeMillis() < hitBlockTime.get(uuid)) {
                if (Math.random() < 0.6) {
                    event.setCancelled(true);
                    return;
                }
            }

            ItemStack mainHand = attacker.getInventory().getItemInMainHand();
            boolean hasAxe = mainHand.getType().name().contains("AXE");
            if (victimEntity instanceof Player) {
                Player victim = (Player) victimEntity;
                if (victim.isBlocking() && !hasAxe) {
                    event.setCancelled(true);
                    flagPlayer(attacker, "ShieldBreaker");
                    return;
                }
            }

            if (victimEntity instanceof LivingEntity) {
                Location attackerLoc = attacker.getLocation();
                Location victimLoc = victimEntity.getLocation();
                double distance = attackerLoc.distance(victimLoc);

                Vector toVictim = victimLoc.toVector().subtract(attackerLoc.toVector()).normalize();
                Vector lookDir = attackerLoc.getDirection().normalize();
                double dot = lookDir.dot(toVictim);

                boolean isInvalidDistance = distance > 4.2;
                boolean isBackOrSideHit = distance > 3.2 && dot < 0.35;

                if (isInvalidDistance || isBackOrSideHit) {
                    event.setCancelled(true);
                    if (attacker.isGliding() || attacker.getVelocity().getY() > 0.1) {
                        flagPlayer(attacker, "ElytraTargetC");
                    } else {
                        flagPlayer(attacker, "Attack Aura");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        double distance = from.distance(to);

        // Sadece hileli oyuncu yürürken takılır / kasılır
        if (hitBlockTime.containsKey(uuid) && System.currentTimeMillis() < hitBlockTime.get(uuid)) {
            if (Math.random() < 0.25 && distance > 0.2) {
                event.setTo(from);
                return;
            }
        }

        if (player.isGliding()) {
            boolean hasFireworkInHand = player.getInventory().getItemInMainHand().getType() == Material.FIREWORK_ROCKET ||
                                      player.getInventory().getItemInOffHand().getType() == Material.FIREWORK_ROCKET;
            boolean hasFireworkInInv = player.getInventory().contains(Material.FIREWORK_ROCKET);

            if (distance > 1.8 && to.getY() >= from.getY() && (!hasFireworkInHand && hasFireworkInInv)) {
                event.setTo(from);
                flagPlayer(player, "ElytraTargetC");
                return;
            }
        }

        if (!player.isGliding() && !player.isFlying()) {
            boolean isNearGround = player.getLocation().subtract(0, 1, 0).getBlock().getType().isSolid();
            if (distance > 1.25 && !isNearGround && player.getVelocity().getY() >= -0.3 && from.getY() == to.getY()) {
                event.setTo(from);
                flagPlayer(player, "Speed");
            }
            if (to.getY() - from.getY() > 1.45 && !isNearGround && !player.getLocation().getBlock().getType().isSolid()) {
                event.setTo(from);
                flagPlayer(player, "Flight");
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        int cps = cpsMap.getOrDefault(player.getUniqueId(), 0) + 1;
        cpsMap.put(player.getUniqueId(), cps);
        if (cps > 25) { // Gerçekçi olmayan yüksek tıklamalar (AutoClicker)
            flagPlayer(player, "AutoClicker");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase();

        if (cmd.equals("axologs")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("konsol")) {
                if (args[1].equalsIgnoreCase("aç")) {
                    consoleLogsEnabled = true;
                    sender.sendMessage(ChatColor.GREEN + "AxoLogs Konsol logları başarıyla AÇILDI.");
                    return true;
                } else if (args[1].equalsIgnoreCase("kapa")) {
                    consoleLogsEnabled = false;
                    sender.sendMessage(ChatColor.RED + "AxoLogs Konsol logları KAPATILDI.");
                    return true;
                }
            } else if (args.length >= 1) {
                if (args[0].equalsIgnoreCase("aç")) {
                    gameLogsEnabled = true;
                    sender.sendMessage(ChatColor.GREEN + "Oyuniçi AxoLogs logları AÇILDI.");
                    return true;
                } else if (args[0].equalsIgnoreCase("kapa")) {
                    gameLogsEnabled = false;
                    sender.sendMessage(ChatColor.RED + "Oyuniçi AxoLogs logları KAPATILDI.");
                    return true;
                }
            }

            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (player.isOp()) openAxoLogsPlayersGUI(player);
            return true;
        }

        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

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

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Böyle bir komut bulunamadı!");
            return true;
        }

        if (cmd.equals("bot")) {
            UUID uuid = player.getUniqueId();
            if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
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
                }
                return true;
            }
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
                        adminWatchingTarget.put(player.getUniqueId(), target.getUniqueId());
                        player.sendMessage(ChatColor.AQUA + "AxoBot: " + target.getName() + " oyuncusunu izliyoruz. Sohbetten bana komut verebilirsin.");
                    }
                } catch (NumberFormatException ignored) {}
            }
            return true;
        }

        return true;
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
            skull.setItemMeta(meta);
            gui.setItem(slot++, skull);
        }
        player.openInventory(gui);
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && sender instanceof Player) return Collections.emptyList();
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("axologs")) {
            if (args.length == 1) return Arrays.asList("aç", "kapa", "konsol");
            if (args.length == 2 && args[0].equalsIgnoreCase("konsol")) return Arrays.asList("aç", "kapa");
        }
        return Collections.emptyList();
    }
}
