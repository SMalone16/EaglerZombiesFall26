package org.pawling.eaglerzombies;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.Vector;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Entirely server-authoritative infection. A non-colliding zombie avatar follows
 * an invisible player as a cross-version visual disguise (no client skin packets).
 */
public final class ZombieInfection implements Listener {
    public static final NamespacedKey ANTIDOTE = new NamespacedKey("luckychests", "anti_zombie");
    public static final NamespacedKey ELIXIR = new NamespacedKey("luckychests", "creative_elixir");
    private static final NamespacedKey INFECTED = new NamespacedKey("eaglerzombiesfall26", "infected");
    private static final NamespacedKey READY = new NamespacedKey("eaglercity", "undercity_ready");
    private static final NamespacedKey X = new NamespacedKey("eaglercity", "undercity_x");
    private static final NamespacedKey Y = new NamespacedKey("eaglercity", "undercity_y");
    private static final NamespacedKey Z = new NamespacedKey("eaglercity", "undercity_z");

    private final EaglerZombiesPlugin plugin;
    private final Map<UUID, Long> exposure = new HashMap<>();
    private final Map<UUID, Long> zombieSwing = new HashMap<>();
    private final Map<UUID, Long> immunity = new HashMap<>();
    private final Set<UUID> zombies = new HashSet<>();
    private final Map<UUID, Zombie> avatars = new HashMap<>();
    private final Map<UUID, PotionEffect> previousInvisibility = new HashMap<>();
    private final Map<UUID, GameMode> priorModes = new HashMap<>();
    private final Map<UUID, Long> creativeUntil = new HashMap<>();

    public ZombieInfection(EaglerZombiesPlugin plugin) { this.plugin = plugin; }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Long deadline = exposure.get(id);
            if (deadline != null) {
                if (now >= deadline) {
                    exposure.remove(id);
                    infect(player);
                } else {
                    long tenths = Math.max(1, (deadline - now + 99L) / 100L);
                    player.sendActionBar(ChatColor.DARK_RED + "INFECTION " + ChatColor.RED
                            + (tenths / 10) + "." + (tenths % 10) + "s"
                            + ChatColor.YELLOW + "  Splash an antidote!");
                }
            } else if (immunity.getOrDefault(id, 0L) > now && !zombies.contains(id)) {
                long seconds = (immunity.get(id) - now + 999L) / 1000L;
                player.sendActionBar(ChatColor.GREEN + "Zombie immunity: " + seconds + "s");
            }
            if (zombies.contains(id)) {
                Zombie avatar = avatars.get(id);
                if (avatar == null || !avatar.isValid()) {
                    spawnAvatar(player);
                } else {
                    avatar.teleport(player.getLocation());
                }
                player.sendActionBar(ChatColor.DARK_GREEN + "ZOMBIE  " + ChatColor.GREEN
                        + "Infect survivors! Find an antidote to become human.");
            }
            Long until = creativeUntil.get(id);
            if (until != null && now >= until) endCreative(player);
        }
        immunity.entrySet().removeIf(e -> e.getValue() <= now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity attacker = event.getDamager();
        if (!(attacker instanceof Zombie) && !(attacker instanceof Player player && zombies.contains(player.getUniqueId())))
            return;
        expose(victim);
    }


    private void expose(Player victim) {
        UUID id = victim.getUniqueId();
        if (zombies.contains(id) || immunity.getOrDefault(id, 0L) > System.currentTimeMillis()
                || !plugin.getConfig().getBoolean("infection.enabled", true)) return;
        long millis = Math.max(1, plugin.getConfig().getLong("infection.countdown-seconds", 7)) * 1000L;
        if (!exposure.containsKey(id)) {
            exposure.put(id, System.currentTimeMillis() + millis);
            victim.sendMessage(ChatColor.RED + "You have been bitten! Find an Anti-Zombie Splash Potion before time runs out!");
        }
    }

    // Works even on no-PvP classroom servers, where vanilla player damage is blocked
    // before EntityDamageByEntityEvent can notify other plugins.
    @EventHandler
    public void onZombieSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player zombie = event.getPlayer();
        UUID id = zombie.getUniqueId();
        if (!zombies.contains(id)) return;
        long now = System.currentTimeMillis();
        if (zombieSwing.getOrDefault(id, 0L) + 600L > now) return;
        Vector facing = zombie.getEyeLocation().getDirection().normalize();
        for (Player target : zombie.getWorld().getPlayers()) {
            if (target == zombie || zombies.contains(target.getUniqueId())
                    || zombie.getLocation().distanceSquared(target.getLocation()) > 9.0
                    || !zombie.hasLineOfSight(target)) continue;
            Vector toward = target.getEyeLocation().toVector()
                    .subtract(zombie.getEyeLocation().toVector());
            if (toward.lengthSquared() < 0.001 || facing.dot(toward.normalize()) < 0.83) continue;
            zombieSwing.put(id, now);
            expose(target);
            break;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !zombies.contains(victim.getUniqueId())) return;
        if (event.getDamager() instanceof Zombie ||
                event.getDamager() instanceof Player attacker && zombies.contains(attacker.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ItemStack item = event.getPotion().getItem();
        if (!tagged(item, ANTIDOTE)) return;
        for (org.bukkit.entity.LivingEntity target : event.getAffectedEntities()) {
            if (!(target instanceof Player player) || event.getIntensity(player) <= 0.0) continue;
            cure(player);
            // Immunity restarts at 10 seconds; repeated splashes never accumulate.
            immunity.put(player.getUniqueId(), System.currentTimeMillis()
                    + Math.max(1, plugin.getConfig().getLong("infection.immunity-seconds", 10)) * 1000L);
            player.sendActionBar(ChatColor.GREEN + "Cured! Protected for 10 seconds.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!tagged(event.getItem(), ELIXIR)) {
            if (zombies.contains(player.getUniqueId())) event.setCancelled(true);
            return;
        }
        if (zombies.contains(player.getUniqueId())) { event.setCancelled(true); return; }
        // Only the drinker and people physically in the same sealed chamber qualify.
        // No adjacent-room player, online player or entire-world gamemode change.
        Set<Player> winners = new HashSet<>();
        winners.add(player);
        if (insideChamber(player)) {
            for (Player other : player.getWorld().getPlayers())
                if (insideChamber(other)) winners.add(other);
        }
        for (Player recipient : winners) {
            UUID id = recipient.getUniqueId();
            priorModes.putIfAbsent(id, recipient.getGameMode());
            recipient.setGameMode(GameMode.CREATIVE);
            long seconds = Math.max(10, plugin.getConfig().getLong("ultimate-loot.creative-seconds", 120));
            creativeUntil.put(id, System.currentTimeMillis() + seconds * 1000L);
            recipient.sendMessage(ChatColor.LIGHT_PURPLE + "Undercity elixir: Creative Mode for " + seconds + " seconds!");
        }
    }

    private boolean insideChamber(Player player) {
        World w = player.getWorld();
        if (w.getPersistentDataContainer().getOrDefault(READY, PersistentDataType.INTEGER, 0) != 1)
            return false;
        int x = w.getPersistentDataContainer().getOrDefault(X, PersistentDataType.INTEGER, 0);
        int y = w.getPersistentDataContainer().getOrDefault(Y, PersistentDataType.INTEGER, 0);
        int z = w.getPersistentDataContainer().getOrDefault(Z, PersistentDataType.INTEGER, 0);
        return player.getLocation().getBlockX() >= x - 6 && player.getLocation().getBlockX() <= x - 4
                && player.getLocation().getBlockZ() >= z + 5 && player.getLocation().getBlockZ() <= z + 7
                && player.getLocation().getBlockY() >= y + 1 && player.getLocation().getBlockY() <= y + 2;
    }

    private static boolean tagged(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public static ItemStack antidote() {
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(PotionType.HEALING);
        meta.setDisplayName(ChatColor.GREEN + "Anti-Zombie Splash Potion");
        meta.getPersistentDataContainer().set(ANTIDOTE, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void infect(Player player) {
        UUID id = player.getUniqueId();
        if (!zombies.add(id)) return;
        player.getPersistentDataContainer().set(INFECTED, PersistentDataType.BYTE, (byte) 1);
        previousInvisibility.put(id, player.getPotionEffect(PotionEffectType.INVISIBILITY));
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false));
        spawnAvatar(player);
        player.sendMessage(ChatColor.DARK_GREEN + "You are now a zombie. Your armor and held item remain equipped.");
    }

    private void spawnAvatar(Player player) {
        removeAvatar(player.getUniqueId());
        Zombie visual = player.getWorld().spawn(player.getLocation(), Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setInvulnerable(true);
            z.setCollidable(false);
            z.setPersistent(false);
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
            z.addScoreboardTag("eagler-zombie-avatar");
        });
        avatars.put(player.getUniqueId(), visual);
    }

    private void removeAvatar(UUID id) {
        Zombie current = avatars.remove(id);
        if (current != null && current.isValid()) current.remove();
    }

    private void cure(Player player) {
        UUID id = player.getUniqueId();
        exposure.remove(id);
        if (!zombies.remove(id)) return;
        player.getPersistentDataContainer().remove(INFECTED);
        removeAvatar(id);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect previous = previousInvisibility.remove(id);
        if (previous != null) player.addPotionEffect(previous);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.sendMessage(ChatColor.GREEN + "You are human again! Health, hunger and inventory access restored.");
    }

    private void endCreative(Player player) {
        UUID id = player.getUniqueId();
        creativeUntil.remove(id);
        GameMode previous = priorModes.remove(id);
        if (previous != null) player.setGameMode(previous);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getPersistentDataContainer().has(INFECTED, PersistentDataType.BYTE)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                zombies.add(p.getUniqueId());
                previousInvisibility.put(p.getUniqueId(), p.getPotionEffect(PotionEffectType.INVISIBILITY));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false));
                spawnAvatar(p);
            }, 10L);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        exposure.remove(id);
        immunity.remove(id);
        zombies.remove(id);
        zombieSwing.remove(id);
        removeAvatar(id);
        previousInvisibility.remove(id);
        endCreative(player);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        UUID id = player.getUniqueId();
        exposure.remove(id);
        immunity.remove(id);
        zombies.remove(id);
        player.getPersistentDataContainer().remove(INFECTED);
        removeAvatar(id);
        previousInvisibility.remove(id);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }

    // Player inventory screens may still appear client-side (E key), but cannot be
    // mutated; no packet-only hacks required by older Eaglercraft clients.
    @EventHandler(ignoreCancelled = true)
    public void inventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && zombies.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && zombies.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void inventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && zombies.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent e) { if (zombies.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && zombies.contains(p.getUniqueId())) e.setCancelled(true);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) endCreative(player);
        for (UUID id : Set.copyOf(avatars.keySet())) removeAvatar(id);
    }
}
