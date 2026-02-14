package ru.benya0449.meteor;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main extends JavaPlugin implements Listener, CommandExecutor {

    private static final String METADATA_KEY = "MeteorisBN_MeteorChest";
    private static final Random RANDOM = new Random();

    private final Map<Location, ArmorStand> holograms = new HashMap<>();
    private final Map<Location, BukkitTask> despawnTimers = new HashMap<>();

    private Set<String> allowedWorlds = new HashSet<>();
    private int autoSpawnChancePercent;
    private int minX, maxX, minZ, maxZ;
    private int minItems, maxItems;
    private String chestName;
    private String messageSpawn;
    private String messageFound;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("meteor").setExecutor(this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (String worldName : allowedWorlds) {
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) continue;

                    if (!isNightTime(w)) continue;

                    if (RANDOM.nextInt(100) < autoSpawnChancePercent) {
                        spawnMeteorChest(w);
                    }
                }
            }
        }.runTaskTimer(this, 20L * 30, 20L * 20);

        getLogger().info("MeteorisBN запущен. Разрешённые миры: " + allowedWorlds);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<Location, ArmorStand> entry : holograms.entrySet()) {
            ArmorStand as = entry.getValue();
            if (as != null && !as.isDead() && as.isValid()) {
                as.remove();
            }
        }
        holograms.clear();

        for (BukkitTask task : despawnTimers.values()) {
            if (task != null) task.cancel();
        }
        despawnTimers.clear();

        getLogger().info("MeteorisBN отключён, все таймеры и голограммы очищены.");
    }

    private void loadConfigValues() {
        reloadConfig();

        List<String> worlds = getConfig().getStringList("allowed-worlds");
        allowedWorlds.clear();
        allowedWorlds.addAll(worlds);

        autoSpawnChancePercent = getConfig().getInt("auto-spawn-chance-percent", 8);

        minX = getConfig().getInt("spawn-min-x", -400);
        maxX = getConfig().getInt("spawn-max-x", 400);
        minZ = getConfig().getInt("spawn-min-z", -400);
        maxZ = getConfig().getInt("spawn-max-z", 400);

        minItems = getConfig().getInt("min-items", 4);
        maxItems = getConfig().getInt("max-items", 9);

        chestName = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("chest-name", "&6&lМетеоритный тайник"));

        messageSpawn = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.spawn", "&aМетеорит упал в мире %world% на x:%x% y:%y% z:%z%!"));

        messageFound = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.found", "&6[Метеорит] &e%player% нашёл тайник на x:%x% y:%y% z:%z%!"));
    }

    private boolean isNightTime(World world) {
        if (world == null) return false;
        long time = world.getTime();
        return time >= 13000 && time <= 23000;
    }

    private void updateHologramName(ArmorStand as, int secondsLeft) {
        if (as == null || as.isDead()) return;

        int minutes = secondsLeft / 60;
        int secs = secondsLeft % 60;
        String timeStr = String.format("%d:%02d", minutes, secs);

        String prefix = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("hologram-timer-prefix", "&eОсталось: "));

        as.setCustomName(prefix + timeStr);
    }

    public void setupSpecialChest(Block block) {
        if (block == null) return;

        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return;

        final Location loc = block.getLocation().clone();  // ключ в мапе — обязательно clone

        if (block.hasMetadata(METADATA_KEY)) return;

        block.setMetadata(METADATA_KEY, new FixedMetadataValue(this, true));

        final ArmorStand hologram;
        if (getConfig().getBoolean("hologram-enabled", true)) {
            Location holoLoc = loc.clone().add(0.5, 1.3, 0.5);

            ArmorStand temp = null;
            try {
                temp = (ArmorStand) loc.getWorld().spawnEntity(holoLoc, EntityType.ARMOR_STAND);
                temp.setVisible(false);
                temp.setGravity(false);
                temp.setInvulnerable(true);
                temp.setMarker(true);
                temp.setSmall(true);
                temp.setBasePlate(false);
                temp.setArms(false);
                temp.setCustomNameVisible(true);

                updateHologramName(temp, getConfig().getInt("despawn-time-seconds", 600));
            } catch (Exception e) {
                getLogger().warning("Не удалось создать голограмму на " + loc);
            }
            hologram = temp;
        } else {
            hologram = null;
        }

        holograms.put(loc, hologram);

        final int totalSeconds = Math.max(30, getConfig().getInt("despawn-time-seconds", 600));
        final AtomicInteger remaining = new AtomicInteger(totalSeconds);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Block current = loc.getBlock();
                if (!current.hasMetadata(METADATA_KEY)) {
                    cancelAndCleanup(loc, hologram);
                    return;
                }

                int secLeft = remaining.decrementAndGet();

                if (secLeft <= 0) {
                    String worldName = loc.getWorld().getName();
                    String despawnMsg = getConfig().getString("despawn-message",
                                    "&cМетеоритный тайник на %x%, %y%, %z% в мире %world% затерялся и пропал!")
                            .replace("%x%", String.valueOf(loc.getBlockX()))
                            .replace("%y%", String.valueOf(loc.getBlockY()))
                            .replace("%z%", String.valueOf(loc.getBlockZ()))
                            .replace("%world%", worldName);

                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', despawnMsg));

                    current.setType(Material.AIR);

                    cancelAndCleanup(loc, hologram);
                } else {
                    if (hologram != null && !hologram.isDead() && hologram.isValid()) {
                        updateHologramName(hologram, secLeft);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);

        despawnTimers.put(loc, task);
    }

    private void cancelAndCleanup(Location loc, ArmorStand hologram) {
        BukkitTask oldTask = despawnTimers.remove(loc);
        if (oldTask != null) oldTask.cancel();

        if (hologram != null && !hologram.isDead() && hologram.isValid()) {
            hologram.remove();
        }
        holograms.remove(loc);

        loc.getBlock().removeMetadata(METADATA_KEY, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("meteor")) return false;

        if (args.length == 0 || !args[0].equalsIgnoreCase("spawn")) {
            sender.sendMessage(ChatColor.YELLOW + "Использование: /meteor spawn [мир]");
            return true;
        }

        World targetWorld;
        if (args.length >= 2) {
            targetWorld = Bukkit.getWorld(args[1]);
            if (targetWorld == null) {
                sender.sendMessage(ChatColor.RED + "Мир " + args[1] + " не найден.");
                return true;
            }
        } else if (sender instanceof Player) {
            targetWorld = ((Player) sender).getWorld();
        } else {
            sender.sendMessage(ChatColor.RED + "Укажи мир: /meteor spawn <мир>");
            return true;
        }

        if (!allowedWorlds.contains(targetWorld.getName())) {
            sender.sendMessage(ChatColor.RED + "В этом мире метеориты запрещены.");
            return true;
        }

        spawnMeteorChest(targetWorld);
        sender.sendMessage(ChatColor.YELLOW + "Метеорит заспавнен в мире " + targetWorld.getName());
        return true;
    }

    private void spawnMeteorChest(World world) {
        Location loc = getRandomSurfaceLocation(world);
        if (loc == null) {
            getLogger().warning("Не удалось найти подходящее место для метеорита в мире " + world.getName());
            return;
        }

        Block block = loc.getBlock();
        if (block.hasMetadata(METADATA_KEY)) return;

        block.setType(Material.CHEST);
        Chest chestState = (Chest) block.getState();
        if (chestState == null) {
            getLogger().warning("Не удалось получить состояние сундука на " + loc);
            return;
        }

        chestState.setCustomName(chestName);
        chestState.update(true);

        fillChestWithLoot(chestState.getInventory());

        setupSpecialChest(block);

        String msg = messageSpawn
                .replace("%world%", world.getName())
                .replace("%x%", String.valueOf(loc.getBlockX()))
                .replace("%y%", String.valueOf(loc.getBlockY()))
                .replace("%z%", String.valueOf(loc.getBlockZ()));

        Bukkit.broadcastMessage(msg);
    }

    private Location getRandomSurfaceLocation(World world) {
        int attempts = 0;
        final int MAX_ATTEMPTS = 20;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            int x = minX + RANDOM.nextInt(maxX - minX + 1);
            int z = minZ + RANDOM.nextInt(maxZ - minZ + 1);

            int y = world.getHighestBlockYAt(x, z);
            if (y < 2) continue;

            Block surface = world.getBlockAt(x, y, z);
            Material mat = surface.getType();

            if (mat.name().contains("WATER") || mat.name().contains("LAVA")) continue;
            if (!surface.isEmpty()) continue;

            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void fillChestWithLoot(Inventory inv) {
        inv.clear();

        ConfigurationSection lootSec = getConfig().getConfigurationSection("loot");
        if (lootSec == null || lootSec.getKeys(false).isEmpty()) {
            getLogger().warning("Секция loot в конфиге пуста или отсутствует!");
            return;
        }

        List<ItemStack> candidates = new ArrayList<>();

        for (String matKey : lootSec.getKeys(false)) {
            ConfigurationSection itemSec = lootSec.getConfigurationSection(matKey);
            if (itemSec == null) continue;

            int chance = itemSec.getInt("chance", 0);
            if (chance <= 0 || RANDOM.nextInt(100) >= chance) continue;

            Material mat = Material.matchMaterial(matKey.toUpperCase(Locale.ROOT));
            if (mat == null || !mat.isItem()) continue;

            int min = Math.max(1, itemSec.getInt("amount-min", 1));
            int max = Math.max(min, itemSec.getInt("amount-max", min));
            int amount = min + RANDOM.nextInt(max - min + 1);

            candidates.add(new ItemStack(mat, amount));
        }

        int targetCount = minItems + RANDOM.nextInt(maxItems - minItems + 1);
        targetCount = Math.min(targetCount, candidates.size());

        if (candidates.isEmpty()) return;

        Collections.shuffle(candidates);
        for (int i = 0; i < targetCount; i++) {
            inv.addItem(candidates.get(i));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled()) return;

        Block b = e.getBlock();
        Material type = b.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return;
        if (!b.hasMetadata(METADATA_KEY)) return;

        Player p = e.getPlayer();
        Location loc = b.getLocation();  // без clone
        World w = loc.getWorld();

        String msg = messageFound
                .replace("%player%", p.getName())
                .replace("%world%", w.getName())
                .replace("%x%", String.valueOf(loc.getBlockX()))
                .replace("%y%", String.valueOf(loc.getBlockY()))
                .replace("%z%", String.valueOf(loc.getBlockZ()));

        Bukkit.broadcastMessage(msg);

        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        w.spawnParticle(Particle.EXPLOSION_LARGE, loc, 8, 0.6, 0.6, 0.6, 0.02);

        ArmorStand holo = holograms.remove(loc);
        if (holo != null && !holo.isDead() && holo.isValid()) {
            holo.remove();
        }

        BukkitTask task = despawnTimers.remove(loc);
        if (task != null) {
            task.cancel();
        }

        b.removeMetadata(METADATA_KEY, this);
    }

    @EventHandler
    public void onChestOpen(PlayerInteractEvent e) {
        if (e.isCancelled()) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;           // ← только основная рука

        Block b = e.getClickedBlock();
        if (b == null) return;

        Material type = b.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return;
        if (!b.hasMetadata(METADATA_KEY)) return;

        Player p = e.getPlayer();
        Location loc = b.getLocation();  // без clone
        World w = loc.getWorld();

        String msg = messageFound
                .replace("%player%", p.getName())
                .replace("%world%", w.getName())
                .replace("%x%", String.valueOf(loc.getBlockX()))
                .replace("%y%", String.valueOf(loc.getBlockY()))
                .replace("%z%", String.valueOf(loc.getBlockZ()));

        Bukkit.broadcastMessage(msg);

        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        w.spawnParticle(Particle.EXPLOSION_LARGE, loc, 8, 0.6, 0.6, 0.6, 0.02);

        ArmorStand holo = holograms.remove(loc);
        if (holo != null && !holo.isDead() && holo.isValid()) {
            holo.remove();
        }

        BukkitTask task = despawnTimers.remove(loc);
        if (task != null) {
            task.cancel();
        }

        b.removeMetadata(METADATA_KEY, this);
    }
}