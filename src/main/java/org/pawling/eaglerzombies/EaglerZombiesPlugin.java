package org.pawling.eaglerzombies;

import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class EaglerZombiesPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int TEMPLE_SIZE = 4;

    private File stateFile;
    private YamlConfiguration state;
    private ZombieInfection infection;
    private UndercitySpawners cavernSpawners;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadState();
        infection = new ZombieInfection(this);
        cavernSpawners = new UndercitySpawners(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(infection, this);
        getServer().getScheduler().runTaskTimer(this, infection::tick, 1L, 2L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (World world : getServer().getWorlds()) if (isEnabledWorld(world)) cavernSpawners.ensure(world);
        }, 120L, 100L);
        org.bukkit.inventory.ShapelessRecipe cureRecipe = new org.bukkit.inventory.ShapelessRecipe(
                new org.bukkit.NamespacedKey(this, "anti_zombie_splash"), ZombieInfection.antidote());
        cureRecipe.addIngredient(Material.ROTTEN_FLESH);
        cureRecipe.addIngredient(Material.MILK_BUCKET);
        cureRecipe.addIngredient(Material.GUNPOWDER);
        cureRecipe.addIngredient(Material.GLASS_BOTTLE);
        getServer().addRecipe(cureRecipe);

        if (getCommand("zombietemple") != null) {
            getCommand("zombietemple").setExecutor(this);
            getCommand("zombietemple").setTabCompleter(this);
        }

        // Existing zombies may have spawned before this plugin finished enabling.
        for (World world : getServer().getWorlds()) {
            makeExistingZombiesDaylightSafe(world);
        }

        // Paper enables normal plugins after worlds are available. One tick later gives
        // spawn chunks a chance to settle before we build around the world spawn.
        getServer().getScheduler().runTask(this, () -> {
            for (World world : getServer().getWorlds()) {
                ensureSpawnTemples(world, false);
            }
        });

        getLogger().info("EaglerZombiesFall26 enabled: zombie temples active and zombies ignore sunlight.");
    }

    @Override
    public void onDisable() {
        if (infection != null) infection.shutdown();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        getServer().getScheduler().runTask(this, () -> {
            makeExistingZombiesDaylightSafe(event.getWorld());
            ensureSpawnTemples(event.getWorld(), false);
        });
    }

    @EventHandler
    public void onZombieSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
    }

    private void makeExistingZombiesDaylightSafe(World world) {
        for (org.bukkit.entity.LivingEntity entity : world.getLivingEntities()) {
            if (entity instanceof Zombie zombie) {
                zombie.setShouldBurnInDay(false);
            }
        }
    }

    private boolean isEnabledWorld(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }

        List<String> enabledWorlds = getConfig().getStringList("enabled-worlds");
        return enabledWorlds.isEmpty() || enabledWorlds.contains(world.getName());
    }

    private void ensureSpawnTemples(World world, boolean force) {
        if (!isEnabledWorld(world)) {
            return;
        }

        int spawnX = world.getSpawnLocation().getBlockX();
        int spawnZ = world.getSpawnLocation().getBlockZ();
        String statePath = "worlds." + world.getUID();

        if (!force
                && state.getInt(statePath + ".spawn-x", Integer.MIN_VALUE) == spawnX
                && state.getInt(statePath + ".spawn-z", Integer.MIN_VALUE) == spawnZ) {
            return;
        }

        int count = clamp(getConfig().getInt("temples-per-world", 4), 1, 12);
        int distance = Math.max(8, getConfig().getInt("distance-from-spawn", 28));
        int maxY = Math.min(world.getMaxHeight() - 3, getConfig().getInt("maximum-temple-y", 252));

        List<String> generatedLocations = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            // Start north of spawn, then distribute the remaining temples evenly.
            double angle = -Math.PI / 2.0 + (Math.PI * 2.0 * i / count);
            int centerX = spawnX + (int) Math.round(Math.cos(angle) * distance);
            int centerZ = spawnZ + (int) Math.round(Math.sin(angle) * distance);

            int startX = centerX - 1;
            int startZ = centerZ - 1;
            int baseY = findTempleBaseY(world, startX, startZ);

            if (baseY > maxY || baseY <= world.getMinHeight()) {
                getLogger().warning("Skipped zombie temple near " + centerX + ", " + centerZ
                        + " because its surface Y=" + baseY + " is outside the configured range.");
                continue;
            }

            buildTemple(world, startX, baseY, startZ);
            generatedLocations.add(startX + "," + baseY + "," + startZ);
        }

        state.set(statePath + ".spawn-x", spawnX);
        state.set(statePath + ".spawn-z", spawnZ);
        state.set(statePath + ".temples", generatedLocations);
        saveState();

        getLogger().info("Generated " + generatedLocations.size() + " zombie temple(s) around "
                + world.getName() + " spawn (" + spawnX + ", " + spawnZ + ").");
    }

    private int findTempleBaseY(World world, int startX, int startZ) {
        int highestSurface = world.getMinHeight();

        for (int x = startX; x < startX + TEMPLE_SIZE; x++) {
            for (int z = startZ; z < startZ + TEMPLE_SIZE; z++) {
                int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                highestSurface = Math.max(highestSurface, surfaceY);
            }
        }

        return highestSurface + 1;
    }

    private void buildTemple(World world, int startX, int baseY, int startZ) {
        List<Material> palette = loadPalette();

        long seed = world.getSeed()
                ^ (((long) startX) << 32)
                ^ (startZ & 0xffffffffL)
                ^ 0x5A4F4D424945L;
        Random random = new Random(seed);

        // Clear a small construction envelope. This mainly removes leaves, grass,
        // snow, or other clutter when the spawn area is uneven.
        for (int x = startX; x < startX + TEMPLE_SIZE; x++) {
            for (int z = startZ; z < startZ + TEMPLE_SIZE; z++) {
                for (int y = baseY + 1; y <= Math.min(baseY + 3, world.getMaxHeight() - 1); y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        // Raise every cell to the same 4x4 base height so the temple does not float
        // on slopes. MOTION_BLOCKING_NO_LEAVES keeps trees from becoming foundations.
        for (int x = startX; x < startX + TEMPLE_SIZE; x++) {
            for (int z = startZ; z < startZ + TEMPLE_SIZE; z++) {
                int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                int fromY = Math.max(world.getMinHeight(), surfaceY + 1);
                for (int y = fromY; y <= baseY; y++) {
                    world.getBlockAt(x, y, z).setType(randomPaletteBlock(palette, random), false);
                }
            }
        }

        // Ensure the entire visible base is a varied 4x4 stone layer.
        for (int x = startX; x < startX + TEMPLE_SIZE; x++) {
            for (int z = startZ; z < startZ + TEMPLE_SIZE; z++) {
                world.getBlockAt(x, baseY, z).setType(randomPaletteBlock(palette, random), false);
            }
        }

        // A centered 2x2 upper step gives the 4x4 footprint a compact pyramid shape.
        for (int x = startX + 1; x <= startX + 2; x++) {
            for (int z = startZ + 1; z <= startZ + 2; z++) {
                world.getBlockAt(x, baseY + 1, z).setType(randomPaletteBlock(palette, random), false);
            }
        }

        // Four exposed zombie spawners sit on the outer corners of the temple.
        placeZombieSpawner(world.getBlockAt(startX, baseY + 1, startZ));
        placeZombieSpawner(world.getBlockAt(startX + 3, baseY + 1, startZ));
        placeZombieSpawner(world.getBlockAt(startX, baseY + 1, startZ + 3));
        placeZombieSpawner(world.getBlockAt(startX + 3, baseY + 1, startZ + 3));
    }

    private List<Material> loadPalette() {
        List<Material> palette = new ArrayList<>();

        for (String configured : getConfig().getStringList("structure-blocks")) {
            Material material = Material.matchMaterial(configured.toUpperCase(Locale.ROOT));
            if (material != null && material.isBlock() && !material.isAir() && material != Material.SPAWNER) {
                palette.add(material);
            }
        }

        if (palette.isEmpty()) {
            palette.add(Material.STONE_BRICKS);
            palette.add(Material.COBBLESTONE);
            palette.add(Material.MOSSY_COBBLESTONE);
        }

        return palette;
    }

    private Material randomPaletteBlock(List<Material> palette, Random random) {
        return palette.get(random.nextInt(palette.size()));
    }

    private void placeZombieSpawner(Block block) {
        block.setType(Material.SPAWNER, false);

        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            getLogger().warning("Could not configure zombie spawner at " + block.getLocation());
            return;
        }

        int minDelay = Math.max(20, getConfig().getInt("spawner.min-delay-ticks", 240));
        int maxDelay = Math.max(minDelay, getConfig().getInt("spawner.max-delay-ticks", 480));

        spawner.setSpawnedType(EntityType.ZOMBIE);

        // Adjust max once before min so custom configurations below vanilla's
        // default delay range cannot violate the Spawner API invariants.
        spawner.setMaxSpawnDelay(Math.max(maxDelay, spawner.getMinSpawnDelay()));
        spawner.setMinSpawnDelay(minDelay);
        spawner.setMaxSpawnDelay(maxDelay);
        spawner.setDelay(minDelay);

        spawner.setSpawnCount(Math.max(1, getConfig().getInt("spawner.spawn-count", 1)));
        spawner.setMaxNearbyEntities(Math.max(1, getConfig().getInt("spawner.max-nearby-zombies", 8)));
        spawner.setRequiredPlayerRange(Math.max(1, getConfig().getInt("spawner.required-player-range", 16)));
        spawner.setSpawnRange(Math.max(1, getConfig().getInt("spawner.spawn-range", 4)));
        spawner.update(true, false);
    }

    private void loadState() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }

        stateFile = new File(getDataFolder(), "state.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
    }

    private void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save state.yml: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Zombie temple testing commands must be run by a player.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("generate")) {
            ensureSpawnTemples(player.getWorld(), true);
            player.sendMessage(ChatColor.GREEN + "Regenerated zombie temples around this world's spawn.");
            return true;
        }

        if (args[0].equalsIgnoreCase("antidote")) {
            player.getInventory().addItem(ZombieInfection.antidote());
            player.sendMessage(ChatColor.GREEN + "One Anti-Zombie Splash Potion added.");
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            Block target = player.getTargetBlockExact(16);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Look at a surface block within 16 blocks and try again.");
                return true;
            }

            int baseY = target.getY() + 1;
            if (baseY + 2 >= target.getWorld().getMaxHeight()) {
                player.sendMessage(ChatColor.RED + "There is not enough vertical space here.");
                return true;
            }

            buildTemple(target.getWorld(), target.getX() - 1, baseY, target.getZ() - 1);
            player.sendMessage(ChatColor.GREEN + "Spawned a test zombie temple.");
            return true;
        }

        sendUsage(player, label);
        return true;
    }

    private void sendStatus(Player player) {
        World world = player.getWorld();
        String statePath = "worlds." + world.getUID();
        List<String> locations = state.getStringList(statePath + ".temples");

        player.sendMessage(ChatColor.DARK_GREEN + "Eagler Zombies status");
        player.sendMessage(ChatColor.YELLOW + "Daylight burning: disabled for all zombie variants");
        player.sendMessage(ChatColor.YELLOW + "Tracked spawn temples in this world: " + locations.size());

        if (!locations.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + String.join(" | ", locations));
        }
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage(ChatColor.YELLOW + "/" + label + " status");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " generate");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " spawn");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " antidote");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "generate", "spawn", "antidote");
        }
        return List.of();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
