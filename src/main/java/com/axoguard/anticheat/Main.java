package com.axoguard.anticheat;

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
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, PluginMessageListener {

    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> kickCountMap = new HashMap<>();
    private final Map<UUID, Long> hitBlockTime = new HashMap<>();
    private final Map<UUID, Integer> cpsMap = new HashMap<>();
    private final Map<Integer, UUID> botTargets = new HashMap<>();

    private final Map<UUID, Long> mutedPlayers = new HashMap<>();
    private final Map<UUID, Long> bannedPlayers = new HashMap<>();
    private final Map<UUID, String> banReasons = new HashMap<>();
    
    private final Set<String> bannedIps = new HashSet<>();
    private final Map<String, Long> ipBannedUntil = new HashMap<>();
    private final Map<String, String> ipBanReasons = new HashMap<>();

    private final Set<String> reportSet = new HashSet<>();
    private final Set<UUID> activeBotSession = new HashSet<>();
    
    private final Map<UUID, UUID> playerBotMap = new HashMap<>();
    private final Map<UUID, UUID> playerAirBotMap = new HashMap<>();
    
    private final Map<UUID, Integer> diamondBreakCount = new HashMap<>();
    private final Map<UUID, Long> diamondResetTimer = new HashMap<>();

    private final List<String> forbiddenClients = Arrays.asList("wurst", "meteor", "yfend", "device", "arvion", "freecam");

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
        getLogger().info("AxoGuard & AxoBot Gelişmiş Koruma Sürümü Aktif!");
    }

    private void flagPlayer(Player player, String hackType) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        int logCount = violations.getOrDefault(uuid, 0) + 1;
        violations.put(uuid, logCount);

        hitBlockTime.put(uuid, System.currentTimeMillis() + 3500); 

        int botId = (Math.abs(uuid.hashCode()) % 500) + 1;
        botTargets.put(botId, uuid);

        int ping = getPing(player);

        String alertMsg = ChatColor.GOLD + "AxoBot efendim, " + 
                ChatColor.YELLOW + player.getName() + " " +
                ChatColor.RED + "adlı oyuncuda " + 
                ChatColor.DARK_RED + hackType + " " +
                ChatColor.RED + "tespit ettim " +
                ChatColor.GOLD + logCount + "x " +
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

        int maxLog = hackType.toLowerCase().contains("aura") ? 8 : 13;

        if (logCount >= maxLog) {
            int kicks = kickCountMap.getOrDefault(uuid, 0) + 1;
            kickCountMap.put(uuid, kicks);
            violations.put(uuid, 0);

            if (kicks >= 3) {
                executeBan(player.getName(), "Console", "14g", "Hile Kullanımı (3 Kick / İhtar)");
            } else {
                kickPlayer(player, hackType);
            }
        }
    }

    private int getPing(Player player) {
        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            return (int) entityPlayer.getClass().getField("ping").get(entityPlayer);
        } catch (Exception e) {
            return 25;
        }
    }

    private void kickPlayer(Player player, String hackType) {
        // İstediğin özel AxoAC Kick Menüsü ekranı
        String kickReason = ChatColor.RED + "AxoAC - Güvenlik Sistemi\n\n" +
                ChatColor.YELLOW + "Sunucumuzda hile kullanımı tespit edildi!\n" +
                ChatColor.RED + "Sebep: " + ChatColor.YELLOW + hackType + "\n\n" +
                ChatColor.WHITE + "Sunucudan uzaklaştırıldınız!\n" +
                ChatColor.AQUA + "discord.gg/axocraft";

        player.kickPlayer(kickReason);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + player.getName() + ChatColor.RED + " hile (" + hackType + ") nedeniyle atıldı.");
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

        Bukkit.broadcastMessage(ChatColor.GRAY + targetName + " ÖLDÜRÜLDÜ.");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "AXOCRAFT - SÜRELİ YASAKLAMA BİLDİRİMİ");
        Bukkit.broadcastMessage(ChatColor.GRAY + "🔨 YASAKLAYAN " + ChatColor.AQUA + executor);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🗡 YASAKLANAN " + ChatColor.RED + targetName);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🏷 SEBEP " + ChatColor.LIGHT_PURPLE + reason);
        Bukkit.broadcastMessage(ChatColor.GRAY + "⌛ SÜRE " + ChatColor.GREEN + detailedDuration);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");
    }

    private void executeIpBan(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        String ip = target != null && target.getAddress() != null ? target.getAddress().getAddress().getHostAddress() : null;

        if (ip != null) {
            long durationMs = parseDuration(durationStr);
            bannedIps.add(ip);
            ipBannedUntil.put(ip, System.currentTimeMillis() + durationMs);
            ipBanReasons.put(ip, reason);
        }

        executeBan(targetName, executor, durationStr, reason);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";

        if (bannedIps.contains(ip)) {
            long expireTime = ipBannedUntil.getOrDefault(ip, 0L);
            if (System.currentTimeMillis() < expireTime) {
                String reason = ipBanReasons.getOrDefault(ip, "IP Ban");
                player.kickPlayer(ChatColor.RED + "Bu IP adresinden girişler yasaklanmıştır!\nSebep: " + reason);
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
            } else {
                bannedPlayers.remove(uuid);
                banReasons.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (playerBotMap.containsValue(entity.getUniqueId()) || playerAirBotMap.containsValue(entity.getUniqueId())) {
            event.setCancelled(true);
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).setHealth(((LivingEntity) entity).getMaxHealth());
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

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "AXOCRAFT - SÜRELİ MUTE BİLDİRİMİ");
        Bukkit.broadcastMessage(ChatColor.GRAY + "🔨 MUTELENEN YETKİLİ " + ChatColor.AQUA + executor);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🎙 MUTELENEN OYUNCU " + ChatColor.RED + targetName);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🏷 SEBEP " + ChatColor.LIGHT_PURPLE + reason);
        Bukkit.broadcastMessage(ChatColor.GRAY + "⌛ SÜRE " + ChatColor.GREEN + detailedDuration);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");

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

        if (mutedPlayers.containsKey(uuid)) {
            long expireTime = mutedPlayers.get(uuid);
            if (System.currentTimeMillis() < expireTime) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Şu anda mutelisiniz! Sohbete yazı yazamazsınız.");
                return;
            } else {
                mutedPlayers.remove(uuid);
                player.sendMessage(ChatColor.GREEN + "Mutenizin süresi doldu, artık sohbeti kullanabilirsiniz.");
            }
        }

        if (activeBotSession.contains(uuid) && player.isOp()) {
            event.setCancelled(true);
            String msg = event.getMessage();
            player.sendMessage(ChatColor.GOLD + "[Sen] " + ChatColor.WHITE + msg);
            
            Bukkit.getScheduler().runTask(this, () -> {
                String botReply;
                String lower = msg.toLowerCase().trim();
                
                if (lower.equals("sa") || lower.contains("selamün aleyküm") || lower.contains("selamun aleykum") || lower.contains("selam")) {
                    botReply = "AxoBot: Aleykümselam as napıon?";
                } else if (lower.contains("nasılsın") || lower.contains("nasıl gidiyor")) {
                    botReply = "AxoBot: Hamdolsun efendim, AxoCraft sunucusunu korumaya devam ediyorum ya siz?";
                } else if (lower.contains("hile mi") || lower.contains("vuruyor mu") || lower.contains("sence hilemi")) {
                    botReply = "AxoBot: Bilmiyorum efendim, izleyelim önce hareketlerini inceleyeyim.";
                } else if (lower.contains("ne yapıyorsun") || lower.contains("ne yapıyon")) {
                    botReply = "AxoBot: Şüpheli oyuncuları gözetliyorum efendim.";
                } else {
                    botReply = "AxoBot: Emredersiniz efendim, takipteyim!";
                }
                player.sendMessage(ChatColor.AQUA + botReply);
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
                // Vuruş engellendi yazısı tamamen kaldırıldı, sadece hasar 1 kalbe (2 HP) düşürülüyor veya pas geçiliyor
                if (Math.random() < 0.4) {
                    event.setCancelled(true);
                    return;
                } else {
                    event.setDamage(Math.min(event.getDamage(), 2.0)); 
                }
            }

            if (event.getEntity() instanceof Player || event.getEntity() instanceof IronGolem) {
                LivingEntity victim = (LivingEntity) event.getEntity();
                double distance = attacker.getLocation().distance(victim.getLocation());

                // Attack Aura ve Reach (uzaktan vurma) koruması (3.8 blok üstü reach/aura sayılır)
                if (distance > 3.8 && attacker.getGameMode() != GameMode.CREATIVE) {
                    flagPlayer(attacker, "Attack Aura / Reach");
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

        if (distance > 1.15 && !player.isFlying() && player.getVelocity().getY() >= -0.3 && from.getY() == to.getY()) {
            event.setTo(from);
            flagPlayer(player, "Speed");
        }

        // Flight açıldığında takılacak ve doğrudan AxoAC Kick Menüsü ile atılacak
        if (to.getY() - from.getY() > 1.35 && !player.isFlying() && !player.getLocation().getBlock().getType().isSolid()) {
            event.setTo(from);
            flagPlayer(player, "Flight / Elytra Move");
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
                player.sendMessage(ChatColor.RED + "Böyle bir oyuncu bulunamadı / oyunda yok!");
                return true;
            }
            reportSet.add(target.getName());
            openReportMenu(player, target.getName());
            return true;
        }

        if (cmd.equals("axologs")) {
            player.sendMessage(ChatColor.DARK_RED + "=== AXOGUARD HİLE İHLAL LİSTESİ ===");
            if (violations.isEmpty()) {
                player.sendMessage(ChatColor.GREEN + "Şu anda aktif ihlal logu bulunmuyor.");
            } else {
                for (Map.Entry<UUID, Integer> entry : violations.entrySet()) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    String name = p != null ? p.getName() : Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    player.sendMessage(ChatColor.YELLOW + "- " + name + ": " + ChatColor.RED + entry.getValue() + " ihlal puanı");
                }
            }
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Böyle bir komut bulunamadı!");
            return true;
        }

        // /bot spawn ve /bot kapa (Zırh askısı yerine tam Steve / Zombie NPC formunda, ölümsüz ve vurulabilir)
        if (cmd.equals("bot")) {
            UUID uuid = player.getUniqueId();
            if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
                if (playerBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten aktif bir AxoBot'un var! Önce /bot kapa yazmalısın.");
                    return true;
                }
                Zombie bot = player.getWorld().spawn(player.getLocation(), Zombie.class);
                bot.setCustomName(ChatColor.AQUA + "AxoBot");
                bot.setCustomNameVisible(true);
                bot.setBaby(false);
                bot.setInvulnerable(false);
                bot.setAI(false); // Hareket etmez, sabit durur (NPC gibi)
                playerBotMap.put(uuid, bot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Steve görünümlü AxoBot NPC çağrıldı!");
                return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("kapa")) {
                UUID botUuid = playerBotMap.remove(uuid);
                if (botUuid != null) {
                    for (Entity e : player.getWorld().getEntities()) {
                        if (e.getUniqueId().equals(botUuid)) {
                            e.remove();
                            break;
                        }
                    }
                    player.sendMessage(ChatColor.GREEN + "AxoBot kapatıldı.");
                } else {
                    player.sendMessage(ChatColor.RED + "Aktif bir botun bulunmuyor!");
                }
                return true;
            }
        }

        // /bothava ve /bothava kapa (20 blok yukarıda sabit duran Steve NPC Hava Botu)
        if (cmd.equals("bothava")) {
            UUID uuid = player.getUniqueId();
            if (args.length > 0 && args[0].equalsIgnoreCase("kapa")) {
                UUID botUuid = playerAirBotMap.remove(uuid);
                if (botUuid != null) {
                    for (Entity e : player.getWorld().getEntities()) {
                        if (e.getUniqueId().equals(botUuid)) {
                            e.remove();
                            break;
                        }
                    }
                    player.sendMessage(ChatColor.GREEN + "Havadaki AxoBot kapatıldı.");
                } else {
                    player.sendMessage(ChatColor.RED + "Aktif bir hava botun bulunmuyor!");
                }
                return true;
            } else {
                if (playerAirBotMap.containsKey(uuid)) {
                    player.sendMessage(ChatColor.RED + "Zaten havada aktif bir botun var!");
                    return true;
                }
                Location airLoc = player.getLocation().add(0, 20, 0);
                Zombie airBot = player.getWorld().spawn(airLoc, Zombie.class);
                airBot.setCustomName(ChatColor.LIGHT_PURPLE + "AxoBot");
                airBot.setCustomNameVisible(true);
                airBot.setBaby(false);
                airBot.setInvulnerable(false);
                airBot.setAI(false);
                airBot.setGravity(false);
                playerAirBotMap.put(uuid, airBot.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "20 blok yukarıda Hava Botu NPC oluşturuldu!");
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
            Player targetCheck = Bukkit.getPlayer(args[0]);
            if (targetCheck == null && Bukkit.getOfflinePlayer(args[0]).getName() == null) {
                player.sendMessage(ChatColor.RED + "Bu isimde bir oyuncu bulunamadı!");
                return true;
            }
            executeBan(args[0], player.getName(), args[1], args[2]);
            return true;
        }

        if (cmd.equals("banla-ip")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /banla-ip <oyuncu> <süre> <sebep>");
                return true;
            }
            Player targetCheck = Bukkit.getPlayer(args[0]);
            if (targetCheck == null) {
                player.sendMessage(ChatColor.RED + "Bu oyuncu çevrimiçi değil veya bulunamadı!");
                return true;
            }
            executeIpBan(args[0], player.getName(), args[1], args[2]);
            return true;
        }

        if (cmd.equals("ban-ip")) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /ban-ip <ip>");
                return true;
            }
            bannedIps.remove(args[0]);
            ipBannedUntil.remove(args[0]);
            player.sendMessage(ChatColor.GREEN + args[0] + " IP adresinin banı açıldı.");
            return true;
        }

        if (cmd.equals("banaç")) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /banaç <oyuncu>");
                return true;
            }
            UUID targetUuid = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
            if (!bannedPlayers.containsKey(targetUuid)) {
                player.sendMessage(ChatColor.RED + "Bu oyuncunun aktif bir banı bulunmuyor!");
                return true;
            }
            bannedPlayers.remove(targetUuid);
            banReasons.remove(targetUuid);
            player.sendMessage(ChatColor.GREEN + args[0] + " oyuncusunun banı açıldı.");
            return true;
        }

        if (cmd.equals("banaç-ip")) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /banaç-ip <ip>");
                return true;
            }
            String ip = args[0];
            if (!bannedIps.contains(ip)) {
                player.sendMessage(ChatColor.RED + "Bu IP adresine ait aktif bir ban bulunmuyor!");
                return true;
            }
            bannedIps.remove(ip);
            ipBannedUntil.remove(ip);
            ipBanReasons.remove(ip);
            player.sendMessage(ChatColor.GREEN + ip + " IP adresinin banı başarıyla kaldırıldı.");
            return true;
        }

        if (cmd.equals("mutele")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /mutele <oyuncu> <süre> <sebep>");
                return true;
            }
            Player targetCheck = Bukkit.getPlayer(args[0]);
            if (targetCheck == null) {
                player.sendMessage(ChatColor.RED + "Mutelenecek oyuncu oyunda bulunamadı!");
                return true;
            }
            executeMute(args[0], player.getName(), args[1], args[2]);
            return true;
        }

        if (cmd.equals("muteaç")) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /muteaç <oyuncu>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[0]).getUniqueId();

            if (!mutedPlayers.containsKey(targetUuid)) {
                player.sendMessage(ChatColor.RED + "Bu oyuncunun aktif bir mutesi bulunmuyor!");
                return true;
            }

            mutedPlayers.remove(targetUuid);

            Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");
            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "AXOCRAFT - MUTE KALDIRILDI BİLDİRİMİ");
            Bukkit.broadcastMessage(ChatColor.GRAY + "🔨 YETKİLİ " + ChatColor.AQUA + player.getName());
            Bukkit.broadcastMessage(ChatColor.GRAY + "🎙 MUTESİ AÇILAN " + ChatColor.RED + args[0]);
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "---------------------------------------------");

            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.GREEN + "Muteniz yetkili tarafından kaldırıldı, artık yazabilirsiniz.");
            }
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
                        player.sendMessage(ChatColor.AQUA + "AxoBot #" + id + " ile " + target.getName() + " yanına ışınlandınız.");
                        player.sendMessage(ChatColor.YELLOW + "AxoBot: Efendim, Aleykümselam as napıon? İzlemeye başladık, sohbete yazarak benimle konuşabilirsiniz.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Geçersiz Bot ID veya hedef oyunda değil!");
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
        ItemStack hile = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = hile.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "Hile Bildir");
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
                meta.setLore(Arrays.asList(ChatColor.AQUA + "AxoBot ile Yanına Işınlan ve İzle"));
                skull.setItemMeta(meta);
                gui.setItem(slot++, skull);
            }
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();

        if (title.startsWith(ChatColor.DARK_GRAY + "Şikayet:")) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "AxoGuard: Şikayetiniz iletildi, report listesine yönlendiriliyorsunuz.");
            openReportsGUI(player);
            return;
        }

        if (title.equals(ChatColor.DARK_RED + "Reportlanan Oyuncular")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            String targetName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayer(targetName);

            if (target != null && target.isOnline()) {
                player.closeInventory();
                player.teleport(target.getLocation());
                activeBotSession.add(player.getUniqueId());
                player.sendMessage(ChatColor.AQUA + "AxoBot: Aleykümselam as napıon? " + target.getName() + " oyuncusunun yanına ışınlandık, izlemeye başlıyoruz.");
            } else {
                player.sendMessage(ChatColor.RED + "Bu oyuncu artık oyunda değil!");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("banla") || cmd.equals("banla-ip") || cmd.equals("mutele")) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                return Arrays.asList("1g", "3g", "7g", "12h", "1h", "1d", "30s");
            } else if (args.length == 3) {
                return Arrays.asList("Attack_Aura", "Flight_Elytra", "Speed", "Hile_Kullanımı", "Küfür/Hakaret", "Aşırı_Spam");
            }
        } else if (cmd.equals("muteaç")) {
            if (args.length == 1) {
                List<String> mutedNames = new ArrayList<>();
                for (UUID uuid : mutedPlayers.keySet()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) mutedNames.add(p.getName());
                    else mutedNames.add(Bukkit.getOfflinePlayer(uuid).getName());
                }
                return mutedNames.stream()
                        .filter(Objects::nonNull)
                        .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (cmd.equals("banaç")) {
            if (args.length == 1) {
                List<String> bannedNames = new ArrayList<>();
                for (UUID uuid : bannedPlayers.keySet()) {
                    bannedNames.add(Bukkit.getOfflinePlayer(uuid).getName());
                }
                return bannedNames.stream()
                        .filter(Objects::nonNull)
                        .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (cmd.equals("banaç-ip")) {
            if (args.length == 1) {
                return new ArrayList<>(bannedIps).stream()
                        .filter(ip -> ip.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (cmd.equals("axobot")) {
            if (args.length == 1) return Collections.singletonList("izle");
        } else if (cmd.equals("bot")) {
            if (args.length == 1) return Arrays.asList("spawn", "kapa");
        } else if (cmd.equals("bothava")) {
            if (args.length == 1) return Collections.singletonList("kapa");
        }

        return Collections.emptyList();
    }
}
