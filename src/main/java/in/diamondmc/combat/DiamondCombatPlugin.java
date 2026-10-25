package in.diamondmc.combat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerElytraBoostEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DiamondCombatPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatExpiry = new HashMap<>();
    private final Map<UUID, Long> pearlExpiry = new HashMap<>();
    private BukkitTask actionBarTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("diamondcombat") != null) {
            getCommand("diamondcombat").setExecutor(this);
            getCommand("diamondcombat").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) {
                    return List.of("help", "reload", "status", "set");
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                    return List.of(
                            "combat-duration",
                            "pearl-cooldown",
                            "pearl-enabled",
                            "elytra-disable",
                            "elytra-unequip",
                            "firework-block",
                            "logout-punish",
                            "actionbar"
                    );
                }
                return List.of();
            });
        }

        startActionBarTask();
        getLogger().info("DIAMOND Combat Ultimate enabled!");
    }

    @Override
    public void onDisable() {
        if (actionBarTask != null) {
            actionBarTask.cancel();
        }
        combatExpiry.clear();
        pearlExpiry.clear();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String message(String path) {
        return color(getConfig().getString("messages." + path, ""));
    }

    private boolean isCombatTagged(Player player) {
        Long expiry = combatExpiry.get(player.getUniqueId());
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            combatExpiry.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private long remainingSeconds(Map<UUID, Long> map, Player player) {
        Long expiry = map.get(player.getUniqueId());
        if (expiry == null) {
            return 0;
        }

        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            map.remove(player.getUniqueId());
            return 0;
        }

        return (remaining + 999) / 1000;
    }

    private void tagCombat(Player player) {
        int duration = Math.max(1, getConfig().getInt("combat.duration-seconds", 20));
        combatExpiry.put(
                player.getUniqueId(),
                System.currentTimeMillis() + duration * 1000L
        );
    }

    private Player getAttackingPlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }

        return null;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = getAttackingPlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        if (attacker.hasPermission("diamondcombat.bypass")
                && !attacker.isOp()) {
            return;
        }

        if (victim.hasPermission("diamondcombat.bypass")
                && !victim.isOp()) {
            return;
        }

        tagCombat(attacker);
        tagCombat(victim);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isCombatTagged(player)) {
            return;
        }

        String input = event.getMessage().substring(1).trim();
        if (input.isEmpty()) {
            return;
        }

        String label = input.split("\\s+")[0]
                .toLowerCase(Locale.ROOT);

        int colon = label.lastIndexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }

        List<String> allowed = getConfig().getStringList("commands.allowed")
                .stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();

        List<String> blocked = getConfig().getStringList("commands.blocked")
                .stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();

        if (allowed.contains(label)) {
            return;
        }

        if (blocked.contains("*") || blocked.contains(label)) {
            event.setCancelled(true);
            player.sendMessage(message("command-blocked"));
        }
    }

    @EventHandler
    public void onPearlInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (event.getItem() == null
                || event.getItem().getType() != Material.ENDER_PEARL) {
            return;
        }

        if (!getConfig().getBoolean("ender-pearl.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        long remaining = remainingSeconds(pearlExpiry, player);

        if (remaining > 0) {
            event.setCancelled(true);
            player.sendMessage(
                    message("pearl-cooldown")
                            .replace("{time}", String.valueOf(remaining))
            );
        }
    }

    @EventHandler
    public void onPearlLaunch(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof org.bukkit.entity.EnderPearl)) {
            return;
        }

        if (!getConfig().getBoolean("ender-pearl.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        int seconds = Math.max(0,
                getConfig().getInt("ender-pearl.cooldown-seconds", 10));

        if (seconds == 0) {
            return;
        }

        pearlExpiry.put(
                player.getUniqueId(),
                System.currentTimeMillis() + seconds * 1000L
        );

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.setCooldown(Material.ENDER_PEARL, seconds * 20);
            }
        });
    }

    private void unequipElytraSafely(Player player) {
        ItemStack chest = player.getInventory().getChestplate();

        if (chest == null || chest.getType() != Material.ELYTRA) {
            return;
        }

        int slot = player.getInventory().firstEmpty();
        if (slot < 0) {
            return;
        }

        player.getInventory().setChestplate(null);
        player.getInventory().setItem(slot, chest.clone());
        player.updateInventory();
    }

    private void enforceElytra(Player player) {
        if (!isCombatTagged(player)
                || !getConfig().getBoolean("elytra.disable-during-combat", true)) {
            return;
        }

        if (player.isGliding()) {
            player.setGliding(false);
        }

        if (getConfig().getBoolean("elytra.unequip-during-combat", false)) {
            unequipElytraSafely(player);
        }
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !event.isGliding()) {
            return;
        }

        if (isCombatTagged(player)
                && getConfig().getBoolean("elytra.disable-during-combat", true)) {
            event.setCancelled(true);
            player.sendMessage(message("elytra-blocked"));

            if (getConfig().getBoolean("elytra.unequip-during-combat", false)) {
                Bukkit.getScheduler().runTask(this,
                        () -> unequipElytraSafely(player));
            }
        }
    }

    @EventHandler
    public void onFireworkBoost(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();

        if (isCombatTagged(player)
                && getConfig().getBoolean("elytra.disable-during-combat", true)
                && getConfig().getBoolean("elytra.block-firework-boost", true)) {
            event.setCancelled(true);
            player.sendMessage(message("elytra-blocked"));
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player.isGliding() && isCombatTagged(player)) {
            enforceElytra(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (isCombatTagged(player)
                && getConfig().getBoolean("logout.punish", true)
                && getConfig().getBoolean("logout.use-normal-death", true)) {
            player.setHealth(0.0);
        }

        combatExpiry.remove(player.getUniqueId());
        pearlExpiry.remove(player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        combatExpiry.remove(player.getUniqueId());
        pearlExpiry.remove(player.getUniqueId());
    }

    private void startActionBarTask() {
        actionBarTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("actionbar.enabled", true)) {
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                long combat = remainingSeconds(combatExpiry, player);
                long pearl = remainingSeconds(pearlExpiry, player);

                boolean showCombat = getConfig()
                        .getBoolean("actionbar.show-combat-timer", true);
                boolean showPearl = getConfig()
                        .getBoolean("actionbar.show-pearl-timer", true);

                if ((!showCombat || combat <= 0) && (!showPearl || pearl <= 0)) {
                    continue;
                }

                String format = getConfig().getString(
                        "actionbar.format",
                        "&b⚔ &fCombat: &e{combat} &8| &dPearl: &e{pearl}"
                );

                String combatText = combat > 0 ? combat + "s" : "-";
                String pearlText = pearl > 0 ? pearl + "s" : "-";

                String output = format
                        .replace("{combat}", showCombat ? combatText : "-")
                        .replace("{pearl}", showPearl ? pearlText : "-");

                player.sendActionBar(color(output));
            }
        }, 0L, 10L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&b&lDIAMOND Combat Ultimate"));
            sender.sendMessage(color("&7/diamondcombat help"));
            sender.sendMessage(color("&7/diamondcombat status"));
            sender.sendMessage(color("&7/diamondcombat reload"));
            sender.sendMessage(color("&7/diamondcombat set <setting> <value>"));
            return true;
        }

        if (!sender.hasPermission("diamondcombat.admin")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(message("reloaded"));
                return true;
            }

            case "status" -> {
                sender.sendMessage(
                        message("status")
                                .replace("{combat}", String.valueOf(
                                        getConfig().getInt("combat.duration-seconds", 20)))
                                .replace("{pearl}", String.valueOf(
                                        getConfig().getInt("ender-pearl.cooldown-seconds", 10)))
                                .replace("{elytra}", String.valueOf(
                                        getConfig().getBoolean("elytra.disable-during-combat", true)))
                );
                return true;
            }

            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(message("usage"));
                    return true;
                }

                String setting = args[1].toLowerCase(Locale.ROOT);
                String value = args[2];

                switch (setting) {
                    case "combat-duration" -> {
                        Integer number = parseRange(value, 1, 3600);
                        if (number == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("combat.duration-seconds", number);
                    }

                    case "pearl-cooldown" -> {
                        Integer number = parseRange(value, 0, 3600);
                        if (number == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("ender-pearl.cooldown-seconds", number);
                    }

                    case "pearl-enabled" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("ender-pearl.enabled", bool);
                    }

                    case "elytra-disable" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("elytra.disable-during-combat", bool);
                    }

                    case "elytra-unequip" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("elytra.unequip-during-combat", bool);
                    }

                    case "firework-block" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("elytra.block-firework-boost", bool);
                    }

                    case "logout-punish" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("logout.punish", bool);
                    }

                    case "actionbar" -> {
                        Boolean bool = parseBoolean(value);
                        if (bool == null) {
                            sender.sendMessage(message("invalid-setting"));
                            return true;
                        }
                        getConfig().set("actionbar.enabled", bool);
                    }

                    default -> {
                        sender.sendMessage(message("invalid-setting"));
                        return true;
                    }
                }

                saveConfig();
                sender.sendMessage(
                        message("setting-updated")
                                .replace("{setting}", setting)
                                .replace("{value}", value)
                );
                return true;
            }

            default -> {
                sender.sendMessage(message("usage"));
                return true;
            }
        }
    }

    private Integer parseRange(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            return number >= min && number <= max ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return null;
    }
}
