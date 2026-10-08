package org.pawling.eaglerzombies;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;

/** Optional and idempotent: EaglerCity owns terrain; this plugin owns all six spawners. */
public final class UndercitySpawners {
    private final EaglerZombiesPlugin plugin;
    private static final NamespacedKey X = new NamespacedKey("eaglercity", "undercity_x");
    private static final NamespacedKey Y = new NamespacedKey("eaglercity", "undercity_y");
    private static final NamespacedKey Z = new NamespacedKey("eaglercity", "undercity_z");
    private static final NamespacedKey READY = new NamespacedKey("eaglercity", "undercity_ready");
    private static final NamespacedKey BUILT = new NamespacedKey("eaglerzombiesfall26", "undercity_spawners_version");

    public UndercitySpawners(EaglerZombiesPlugin plugin) { this.plugin = plugin; }

    public void ensure(World world) {
        if (world.getPersistentDataContainer().getOrDefault(READY, PersistentDataType.INTEGER, 0) != 1
                || world.getPersistentDataContainer().getOrDefault(BUILT, PersistentDataType.INTEGER, 0) == 1)
            return;
        int cx = world.getPersistentDataContainer().getOrDefault(X, PersistentDataType.INTEGER, 0);
        int floor = world.getPersistentDataContainer().getOrDefault(Y, PersistentDataType.INTEGER, 0);
        int cz = world.getPersistentDataContainer().getOrDefault(Z, PersistentDataType.INTEGER, 0);
        if (floor < world.getMinHeight() + 3 || floor > 230) return;
        int successes = 0;
        for (int branchZ : new int[] {-3, 1}) {
            for (int roomX : new int[] {-5, 4}) {
                if (configure(world.getBlockAt(cx + roomX, floor + 1, cz + branchZ))) successes++;
            }
        }
        for (int frontX : new int[] {-10, 9}) {
            if (configure(world.getBlockAt(cx + frontX, floor + 1, cz - 10))) successes++;
        }
        if (successes == 6) {
            world.getPersistentDataContainer().set(BUILT, PersistentDataType.INTEGER, 1);
            plugin.getLogger().info("Activated six Undercity zombie spawners in " + world.getName());
        } else {
            plugin.getLogger().warning("Some Undercity spawner positions were missing; will retry.");
        }
    }

    private boolean configure(Block block) {
        // Never overwrite an unexpected occupied block if another plugin has claimed it.
        if (block.getType() != Material.CHISELED_STONE_BRICKS && block.getType() != Material.SPAWNER)
            return false;
        block.setType(Material.SPAWNER, false);
        if (!(block.getState() instanceof CreatureSpawner spawner)) return false;
        int min = Math.max(40, plugin.getConfig().getInt("undercity-spawner.min-delay-ticks", 300));
        int max = Math.max(min, plugin.getConfig().getInt("undercity-spawner.max-delay-ticks", 500));
        spawner.setSpawnedType(EntityType.ZOMBIE);
        spawner.setMaxSpawnDelay(Math.max(max, spawner.getMinSpawnDelay()));
        spawner.setMinSpawnDelay(min);
        spawner.setMaxSpawnDelay(max);
        spawner.setDelay(min);
        spawner.setSpawnCount(1);
        spawner.setMaxNearbyEntities(Math.max(1, plugin.getConfig().getInt("undercity-spawner.max-nearby-zombies", 5)));
        spawner.setRequiredPlayerRange(Math.max(1, plugin.getConfig().getInt("undercity-spawner.required-player-range", 16)));
        spawner.setSpawnRange(2);
        spawner.update(true, false);
        return true;
    }
}
