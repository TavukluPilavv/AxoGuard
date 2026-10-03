package com.axoguard.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> speedViolations = new HashMap<>();
    private final Map<UUID, Integer> cpsMap = new HashMap<>();
    private final Map<UUID, Long> lastClickTime = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AxoGuard & AxoBot Hizmetkar Modu Aktif!");

        // CPS Sıfırlayıcı (Her saniye çalışır)
        Bukkit.getScheduler().runTaskTimer(this, cpsMap::clear, 20L, 20L);
    }

    // 1. SPEED & FLY KONTROLÜ
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("axoguard.bypass") || player.getGameMode() == GameMode.CREATIVE || player.isFlying()) {
            return;
        }

        double distance = event.getFrom().distance(event.getTo());

        // Speed Hack Kontrolü
        if (distance > 0.85) {
            event.setTo(event.getFrom()); // Rubberband (Takılma Efekti)

            UUID uuid = player.getUniqueId();
            int count = speedViolations.getOrDefault(uuid, 0) + 1;
            speedViolations.put(uuid, count);

            sendOpAlert("Efendim, " + player.getName() + " isimli şüpheli Speed Hack (3x) kullanıyor! (İhlal: " + count + "/5)");

            if (count >= 5) {
                speedViolations.remove(uuid);
                player.kickPlayer(ChatColor.RED + "AxoGuard: Speed Hilesi Nedeniyle Atıldınız!");
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[AxoGuard] " + ChatColor.YELLOW + player.getName() + ChatColor.RED + " hile kullanımı nedeniyle atıldı.");
                sendOpAlert("Efendim, emriniz üzerine " + player.getName() + " isimli hileciyi sunucudan attım!");
            }
        }

        // Fly Kontrolü (Süzülme/Uçma)
        if (event.getTo().getY() > event.getFrom().getY()) {
            double yDiff = event.getTo().getY() - event.getFrom().getY();
            if (yDiff > 1.25 && !player.getLocation().getBlock().getType().isSolid()) {
                event.setTo(event.getFrom());
                sendOpAlert("Efendim, " + player.getName() + " isimli şüpheli uçmaya çalışıyor (Fly Hack)!");
            }
        }
    }

    // 2. AUTOCLICKER / CPS KONTROLÜ
    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();

            int cps = cpsMap.getOrDefault(uuid, 0) + 1;
            cpsMap.put(uuid, cps);

            if (cps > 18) {
                sendOpAlert("Efendim, " + player.getName() + " aşırı yüksek tık sayısına ulaştı (AutoClicker: " + cps + " CPS)!");
            }
        }
    }

    // 3. SHIELD BREAKER & FAST ATTACK KONTROLÜ
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            Player victim = (Player) event.getEntity();

            if (victim.isBlocking() && attacker.getInventory().getItemInMainHand().getType().name().contains("SWORD")) {
                sendOpAlert("Efendim, " + attacker.getName() + " kılıç ile kalkan kırdı/delmeye çalıştı (Shield Breaker)!");
            }
        }
    }

    // OP LARA HİZMETKAR RAPORU
    private void sendOpAlert(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("axoguard.admin")) {
                p.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "AxoBot" + ChatColor.DARK_GRAY + "] " + ChatColor.ITALIC + ChatColor.GRAY + message);
            }
        }
    }

    // 4. REPORT SİSTEMİ & GUI
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getMessage().equalsIgnoreCase("/report")) {
            event.setCancelled(true);
            openReportMenu(event.getPlayer());
        }
    }

    public void openReportMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Oyuncu Şikayet Menüsü");

        ItemStack hile = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta hileMeta = hile.getItemMeta();
        hileMeta.setDisplayName(ChatColor.GREEN + "Hile Kullanımı");
        hile.setItemMeta(hileMeta);

        ItemStack bug = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta bugMeta = bug.getItemMeta();
        bugMeta.setDisplayName(ChatColor.RED + "Bug / Açık Kullanımı");
        bug.setItemMeta(bugMeta);

        ItemStack argo = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta argoMeta = argo.getItemMeta();
        argoMeta.setDisplayName(ChatColor.YELLOW + "Argo / Hakaret");
        argo.setItemMeta(argoMeta);

        gui.setItem(11, hile);
        gui.setItem(13, bug);
        gui.setItem(15, argo);

        player.openInventory(gui);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_GRAY + "Oyuncu Şikayet Menüsü")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;

            Player player = (Player) event.getWhoClicked();
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "AxoGuard: Şikayetiniz başarıyla iletildi.");
            sendOpAlert("Efendim, yeni bir şikayet var! " + player.getName() + " bir oyuncuyu bildirdi.");
        }
    }
}
