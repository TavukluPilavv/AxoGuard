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
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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

import java.text.SimpleDateFormat;
import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

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
    
    private final Map<UUID, Integer> diamondBreakCount = new HashMap<>();
    private final Map<UUID, Long> diamondResetTimer = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        String[] cmds = {"axobot", "report", "reports", "banla", "banla-ip", "ban-ip", "banaç", "mutele", "muteaç"};
        for (String cmd : cmds) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }

        Bukkit.getScheduler().runTaskTimer(this, cpsMap::clear, 20L, 20L);
        getLogger().info("AxoGuard & AxoBot Sıfırdan Tam Sürüm Aktif!");
    }

    private void flagPlayer(Player player, String hackType) {
        if (player.hasPermission("axoguard.bypass") || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        int logCount = violations.getOrDefault(uuid, 0) + 1;
        violations.put(uuid, logCount);

        hitBlockTime.put(uuid, System.currentTimeMillis() + 3000); 

        int botId = (Math.abs(uuid.hashCode()) % 500) + 1;
        botTargets.put(botId, uuid);

        int ping = getPing(player);

        String alertMsg = ChatColor.GOLD + "AxoAC efendim, " + 
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

        if (logCount >= 15) {
            int kicks = kickCountMap.getOrDefault(uuid, 0) + 1;
            kickCountMap.put(uuid, kicks);
            violations.put(uuid, 0);

            if (kicks >= 3) {
                executeBan(player.getName(), "Console", "14g", "Hile Kullanımı (3. İhtar)");
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
        String kickReason = ChatColor.RED + "AxoGuard & AxoBot\n\n" +
                ChatColor.YELLOW + "AxoAC: Oyuncuda hile tespit edildi!\n" +
                ChatColor.RED + "Sebep: " + ChatColor.YELLOW + hackType + "\n\n" +
                ChatColor.GRAY + "Aşırı Şüpheli Davranış Nedeniyle Atıldınız!";

        player.kickPlayer(kickReason);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + player.getName() + ChatColor.RED + " hile (" + hackType + ") kullanımı nedeniyle atıldı.");
    }

    private void executeBan(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        long durationMs = parseDuration(durationStr);
        bannedPlayers.put(uuid, System.currentTimeMillis() + durationMs);
        banReasons.put(uuid, reason);

        String dateStr = new SimpleDateFormat("yyyy/MM/dd").format(new Date());

        String banScreen = ChatColor.GOLD + "Bağlantı koptu\n\n" +
                ChatColor.GOLD + "" + ChatColor.BOLD + "BU SUNUCUDAN YASAKLANDINIZ\n\n" +
                ChatColor.WHITE + "⏰ TARİH: " + ChatColor.YELLOW + dateStr + "\n" +
                ChatColor.WHITE + "🔨 YASAKLAYAN: " + ChatColor.AQUA + executor + "\n" +
                ChatColor.WHITE + "🏷 SEBEP: " + ChatColor.LIGHT_PURPLE + reason + "\n" +
                ChatColor.WHITE + "⌛ KALAN SÜRE: " + ChatColor.LIGHT_PURPLE + durationStr.toUpperCase() + "\n\n" +
                ChatColor.AQUA + "İTİRAZ EDEBİLİRSİNİZ:\n" +
                ChatColor.WHITE + "discord.gg/axocraft";

        if (target != null && target.isOnline()) {
            target.kickPlayer(banScreen);
        }

        Bukkit.broadcastMessage(ChatColor.GRAY + "ÖLDÜRÜLDÜ.");
        Bukkit.broadcastMessage(ChatColor.GRAY + "" + ChatColor.BOLD + "SÜRELİ YASAKLAMA BİLDİRİMİ");
        Bukkit.broadcastMessage(ChatColor.GRAY + "🗡 YASAKLANAN " + ChatColor.RED + targetName);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🔨 YASAKLAYAN " + ChatColor.AQUA + executor);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🏷 SEBEP " + ChatColor.LIGHT_PURPLE + reason);
        Bukkit.broadcastMessage(ChatColor.GRAY + "⌛ SÜRE " + ChatColor.LIGHT_PURPLE + durationStr.toUpperCase());
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

    private void executeMute(String targetName, String executor, String durationStr, String reason) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) return;

        long durationMs = parseDuration(durationStr);
        mutedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + durationMs);

        String muteId = Integer.toHexString((int) (Math.random() * 0xFFFFFF)).toUpperCase();

        Bukkit.broadcastMessage(ChatColor.GRAY + "" + ChatColor.BOLD + "SÜRELİ MUTE BİLDİRİMİ");
        Bukkit.broadcastMessage(ChatColor.GRAY + "ⓘ ID " + muteId);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🎙 MUTELENEN " + ChatColor.RED + targetName);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🔨 MUTELENEN " + ChatColor.AQUA + executor);
        Bukkit.broadcastMessage(ChatColor.GRAY + "🏷 SEBEP " + ChatColor.LIGHT_PURPLE + reason);
        Bukkit.broadcastMessage(ChatColor.GRAY + "⌛ SÜRE " + ChatColor.LIGHT_PURPLE + durationStr.toUpperCase());

        target.sendMessage(ChatColor.RED + "Sohbet yetkiniz " + durationStr + " süreliğine kısıtlandı!");
    }

    private long parseDuration(String input) {
        try {
            input = input.toLowerCase();
            long val = Long.parseLong(input.replaceAll("[^0-9]", ""));
            if (input.endsWith("g") || input.endsWith("gün")) {
                return val * 24 * 60 * 60 * 1000L;
            } else if (input.endsWith("s") || input.endsWith("saat")) {
                return val * 60 * 60 * 1000L;
            } else if (input.endsWith("d") || input.endsWith("dka") || input.endsWith("dakika")) {
                return val * 60 * 1000L;
            }
        } catch (Exception ignored) {}
        return 1 * 24 * 60 * 60 * 1000L;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (mutedPlayers.containsKey(uuid)) {
            if (System.currentTimeMillis() < mutedPlayers.get(uuid)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Şu anda mutelisiniz! Sohbete yazı yazamazsınız.");
                return;
            } else {
                mutedPlayers.remove(uuid);
            }
        }

        if (activeBotSession.contains(uuid) && player.isOp()) {
            event.setCancelled(true);
            String msg = event.getMessage();
            player.sendMessage(ChatColor.GOLD + "[Sen] " + ChatColor.WHITE + msg);
            
            Bukkit.getScheduler().runTask(this, () -> {
                String botReply;
                String lower = msg.toLowerCase();
                if (lower.contains("hile") || lower.contains("vuruyor") || lower.contains("aura")) {
                    botReply = "AxoBot: Efendim, hareketleri gayet şüpheli, bence de hile açmış!";
                } else if (lower.contains("temiz") || lower.contains("değil")) {
                    botReply = "AxoBot: Anladım efendim, takibi sürdürüyorum.";
                } else {
                    botReply = "AxoBot: Emredersiniz efendim, izlemeye devam ediyorum!";
                }
                player.sendMessage(ChatColor.AQUA + botReply);
            });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("axoguard.bypass") || player.getGameMode() == GameMode.CREATIVE) return;

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
                if (count >= 6) {
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
                event.setCancelled(true);
                return;
            }

            if (event.getEntity() instanceof Player || event.getEntity() instanceof IronGolem) {
                LivingEntity victim = (LivingEntity) event.getEntity();
                double distance = attacker.getLocation().distance(victim.getLocation());

                if (distance > 4.2 && attacker.getGameMode() != GameMode.CREATIVE) {
                    flagPlayer(attacker, "Hitbox / Reach");
                }
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("axoguard.bypass") || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        double distance = from.distance(to);

        if (distance > 1.35 && !player.isFlying() && player.getVelocity().getY() >= -0.5) {
            event.setTo(from);
            flagPlayer(player, "Speed");
        }

        if (to.getY() - from.getY() > 1.55 && !player.isFlying() && !player.getLocation().getBlock().getType().isSolid()) {
            event.setTo(from);
            flagPlayer(player, "Fly");
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        int cps = cpsMap.getOrDefault(uuid, 0) + 1;
        cpsMap.put(uuid, cps);

        if (cps > 25) {
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

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Böyle bir komut bulunamadı!");
            return true;
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

        if (cmd.equals("banla-ip")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /banla-ip <oyuncu> <süre> <sebep>");
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
            bannedPlayers.remove(targetUuid);
            banReasons.remove(targetUuid);
            player.sendMessage(ChatColor.GREEN + args[0] + " oyuncusunun banı açıldı.");
            return true;
        }

        if (cmd.equals("mutele")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /mutele <oyuncu> <süre> <sebep>");
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
            if (target != null) {
                mutedPlayers.remove(target.getUniqueId());
                player.sendMessage(ChatColor.GREEN + target.getName() + " oyuncusunun mutesi açıldı.");
                target.sendMessage(ChatColor.GREEN + "Muteniz kaldırıldı, yazabilirsiniz.");
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
                        player.sendMessage(ChatColor.YELLOW + "AxoBot: Efendim, izlemeye başladık. Sohbete yazarak benimle konuşabilirsiniz.");
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
                meta.setLore(Arrays.asList(ChatColor.AQUA + "AxoBot ile Beraber İzle"));
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
                player.sendMessage(ChatColor.AQUA + "AxoBot: Efendim, " + target.getName() + " oyuncusunu izlemeye başladık. Sohbete yazarak benimle konuşabilirsiniz.");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("banla") || cmd.equals("banla-ip") || cmd.equals("mutele")) {
            if (args.length == 2) return Arrays.asList("1g", "3g", "7g", "1s", "12s", "30d");
        } else if (cmd.equals("axobot")) {
            if (args.length == 1) return Collections.singletonList("izle");
        }
        return Collections.emptyList();
    }
}
