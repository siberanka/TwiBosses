package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.utils.ColorUtils;
import com.siberanka.twibosses.utils.TimeUtils;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class SpawnManager {
    private final TwiBosses plugin;
    private final Map<String, Location> spawnLocations;
    private File spawnFile;
    private FileConfiguration spawnConfig;
    private File dataFile;
    private YamlConfiguration dataConfig;
    private final Map<String, Long> respawnTimes;
    private final Map<String, BukkitTask> respawnTasks;
    private final Map<String, BukkitTask> announcementTasks;
    private final Map<String, BukkitTask> movementCheckers;
    private final Map<UUID, BukkitTask> timeoutTasks;
    private final Map<String, Boolean> spawnedStatus = new HashMap<String, Boolean>();
    private final Map<String, UUID> spawnedEntityIds = new HashMap<String, UUID>();
    private BukkitTask scheduledSpawnTask;

    public SpawnManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.spawnLocations = new HashMap<String, Location>();
        this.respawnTimes = new HashMap<String, Long>();
        this.respawnTasks = new HashMap<String, BukkitTask>();
        this.announcementTasks = new HashMap<String, BukkitTask>();
        this.movementCheckers = new HashMap<String, BukkitTask>();
        this.timeoutTasks = new HashMap<UUID, BukkitTask>();
        this.loadDataFile();
        this.loadSpawnLocations();
        this.startScheduledSpawnTask();
    }

    private void loadDataFile() {
        this.dataFile = new File(this.plugin.getDataFolder(), "data.yml");
        if (!this.dataFile.exists()) {
            try {
                this.dataFile.createNewFile();
            }
            catch (IOException e) {
                this.plugin.getLogger().severe(this.plugin.getLanguageManager().raw("logs.data-create-failed", LanguageManager.placeholders("error", e.getMessage())));
                this.plugin.logError("logs.data-create-failed", e);
            }
        }
        this.dataConfig = YamlConfiguration.loadConfiguration((File)this.dataFile);
    }

    private void loadSpawnLocations() {
        this.spawnFile = new File(this.plugin.getDataFolder(), "spawns.yml");
        if (!this.spawnFile.exists()) {
            this.plugin.saveResource("spawns.yml", false);
        }
        this.spawnConfig = YamlConfiguration.loadConfiguration((File)this.spawnFile);
        ConfigurationSection spawnsSection = this.spawnConfig.getConfigurationSection("spawns");
        if (spawnsSection != null) {
            for (String mobType : spawnsSection.getKeys(false)) {
                ConfigurationSection mobSection = spawnsSection.getConfigurationSection(mobType);
                if (mobSection == null) continue;
                org.bukkit.World world = this.plugin.getServer().getWorld(mobSection.getString("world", ""));
                if (world == null) {
                    this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.spawn-world-missing", LanguageManager.placeholders("mobtype", mobType)));
                    continue;
                }
                Location loc = new Location(world, mobSection.getDouble("x"), mobSection.getDouble("y"), mobSection.getDouble("z"), (float)mobSection.getDouble("yaw"), (float)mobSection.getDouble("pitch"));
                this.spawnLocations.put(mobType, loc);
                long savedRespawnUntil = this.dataConfig.getLong("respawn-timers." + mobType, -1L);
                if (savedRespawnUntil <= 0L) continue;
                this.respawnTimes.put(mobType, savedRespawnUntil);
                this.spawnedStatus.put(mobType, false);
            }
        }
    }

    private void saveRespawnTime(String mobType, long epochMs) {
        this.dataConfig.set("respawn-timers." + mobType, (Object)epochMs);
        try {
            this.dataConfig.save(this.dataFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe(this.plugin.getLanguageManager().raw("logs.respawn-save-failed", LanguageManager.placeholders("mobtype", mobType, "error", e.getMessage())));
            this.plugin.logError("logs.respawn-save-failed:" + mobType, e);
        }
    }

    private void clearRespawnTime(String mobType) {
        this.dataConfig.set("respawn-timers." + mobType, null);
        try {
            this.dataConfig.save(this.dataFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe(this.plugin.getLanguageManager().raw("logs.respawn-clear-failed", LanguageManager.placeholders("mobtype", mobType, "error", e.getMessage())));
            this.plugin.logError("logs.respawn-clear-failed:" + mobType, e);
        }
    }

    public void saveSpawnLocation(String mobType, Location location) {
        if (!this.isValidLocation(location)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-spawn-save", LanguageManager.placeholders("mobtype", mobType)));
            return;
        }
        this.spawnLocations.put(mobType, location);
        ConfigurationSection spawnsSection = this.spawnConfig.createSection("spawns." + mobType);
        spawnsSection.set("world", (Object)location.getWorld().getName());
        spawnsSection.set("x", (Object)location.getX());
        spawnsSection.set("y", (Object)location.getY());
        spawnsSection.set("z", (Object)location.getZ());
        spawnsSection.set("yaw", (Object)Float.valueOf(location.getYaw()));
        spawnsSection.set("pitch", (Object)Float.valueOf(location.getPitch()));
        try {
            this.spawnConfig.save(this.spawnFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe(this.plugin.getLanguageManager().raw("logs.spawn-save-failed", LanguageManager.placeholders("mobtype", mobType, "error", e.getMessage())));
            this.plugin.logError("logs.spawn-save-failed:" + mobType, e);
        }
    }

    public void handleMobDeath(final String mobType, Location deathLocation) {
        this.handleMobDeath(mobType, deathLocation, null);
    }

    public void handleMobDeath(final String mobType, Location deathLocation, UUID entityId) {
        if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            return;
        }
        this.cleanupBossRuntime(mobType, entityId, false);
        this.scheduleRespawn(mobType);
    }

    private void scheduleRespawn(final String mobType) {
        Location spawnLoc;
        if (!this.plugin.getConfigManager().isMobRespawnEnabled(mobType)) {
            return;
        }
        int respawnTime = this.plugin.getConfigManager().getMobRespawnTime(mobType);
        if (respawnTime <= 0) {
            return;
        }
        long respawnAt = System.currentTimeMillis() + (long)respawnTime * 1000L;
        this.respawnTimes.put(mobType, respawnAt);
        this.saveRespawnTime(mobType, respawnAt);
        if (this.respawnTasks.containsKey(mobType)) {
            this.respawnTasks.get(mobType).cancel();
        }
        if (this.plugin.getConfigManager().isRespawnHologramEnabled() && (spawnLoc = this.spawnLocations.get(mobType)) != null) {
            this.removeExistingHologram(mobType);
            this.createRespawnHologram(mobType, spawnLoc.clone(), respawnTime);
        }
        BukkitTask respawnTask = new BukkitRunnable(){

            public void run() {
                SpawnManager.this.spawnMob(mobType);
                SpawnManager.this.respawnTimes.remove(mobType);
                SpawnManager.this.respawnTasks.remove(mobType);
                SpawnManager.this.removeExistingHologram(mobType);
            }
        }.runTaskLater((Plugin)this.plugin, (long)respawnTime * 20L);
        this.respawnTasks.put(mobType, respawnTask);
    }

    private void removeExistingHologram(String mobType) {
        this.plugin.getHologramManager().removeHologram(mobType + "_respawn");
    }

    private void createRespawnHologram(final String mobType, Location location, final int respawnTime) {
        if (!this.plugin.getHologramManager().isEnabled()) {
            return;
        }
        final String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        final List<String> lines = this.plugin.getConfigManager().getRespawnHologramLines();
        if (this.announcementTasks.containsKey(mobType)) {
            this.announcementTasks.get(mobType).cancel();
            this.announcementTasks.remove(mobType);
        }
        ArrayList<String> formattedLines = new ArrayList<String>();
        for (String line : lines) {
            formattedLines.add(ColorUtils.colorize(line.replace("{mobname}", mobName).replace("{time}", this.formatTime(respawnTime))));
        }
        Location hologramLoc = location.clone().add(0.0, 2.0, 0.0);
        this.plugin.getHologramManager().createHologram(mobType + "_respawn", hologramLoc, formattedLines);
        BukkitTask task = new BukkitRunnable(){
            int timeLeft;
            {
                this.timeLeft = respawnTime;
            }

            public void run() {
                if (this.timeLeft <= 0 || SpawnManager.this.spawnedStatus.getOrDefault(mobType, false).booleanValue()) {
                    this.cancel();
                    SpawnManager.this.announcementTasks.remove(mobType);
                    SpawnManager.this.plugin.getHologramManager().removeHologram(mobType + "_respawn");
                    return;
                }
                ArrayList<String> updatedLines = new ArrayList<String>();
                for (String line : lines) {
                    updatedLines.add(ColorUtils.colorize(line.replace("{mobname}", mobName).replace("{time}", SpawnManager.this.formatTime(this.timeLeft))));
                }
                SpawnManager.this.plugin.getHologramManager().updateHologram(mobType + "_respawn", updatedLines);
                --this.timeLeft;
            }
        }.runTaskTimer((Plugin)this.plugin, 0L, 20L);
        this.announcementTasks.put(mobType, task);
    }

    private String formatTime(int seconds) {
        return TimeUtils.formatTime(seconds);
    }

    public void spawnMob(String mobType) {
        Location spawnLoc = this.spawnLocations.get(mobType);
        if (spawnLoc != null) {
            if (!this.isValidLocation(spawnLoc)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-spawn-location", LanguageManager.placeholders("mobtype", mobType)));
                return;
            }
            if (this.plugin.getConfigManager().shouldPreventDuplicateAliveBoss() && this.isMobSpawned(mobType)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.duplicate-spawn-skipped", LanguageManager.placeholders("mobtype", mobType)));
                return;
            }
            this.plugin.getHologramManager().removeHologram(mobType + "_respawn");
            ActiveMob mythicMob = MythicBukkit.inst().getMobManager().spawnMob(mobType, spawnLoc);
            if (mythicMob == null || mythicMob.getEntity() == null) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.mythic-spawn-failed", LanguageManager.placeholders("mobtype", mobType)));
                return;
            }
            UUID spawnedId = mythicMob.getEntity().getUniqueId();
            this.trackSpawnedBoss(mobType, spawnedId);
            if (this.plugin.getBedrockVisualManager() != null) {
                this.plugin.getBedrockVisualManager().registerBoss(mobType, mythicMob.getEntity().getBukkitEntity());
            }
            this.respawnTimes.remove(mobType);
            this.clearRespawnTime(mobType);
            if (this.announcementTasks.containsKey(mobType)) {
                this.announcementTasks.get(mobType).cancel();
                this.announcementTasks.remove(mobType);
            }
            this.plugin.getDisplayManager().showMobSpawn(mobType, spawnLoc);
            this.plugin.getWebhookManager().sendSpawnWebhook(mobType, spawnLoc);
            if (this.plugin.getConfigManager().isMobMovementRestricted()) {
                this.startMovementChecker(mobType, spawnedId, spawnLoc);
            }
        }
    }

    public void startMovementChecker(final String mobType, final UUID mobUUID, final Location spawnLoc) {
        if (this.movementCheckers.containsKey(mobType)) {
            this.movementCheckers.get(mobType).cancel();
        }
        if (!this.isValidLocation(spawnLoc)) {
            return;
        }
        int checkInterval = Math.max(1, this.plugin.getConfigManager().getMovementCheckInterval()) * 20;
        final double maxDistance = this.plugin.getConfigManager().getMaxMobMovementDistance();
        final double maxDistanceSquared = maxDistance * maxDistance;
        final boolean shouldTeleport = this.plugin.getConfigManager().shouldTeleportBack();
        BukkitTask task = new BukkitRunnable(){

            public void run() {
                Location originalSpawn;
                Optional optionalMob = MythicBukkit.inst().getMobManager().getActiveMob(mobUUID);
                if (!optionalMob.isPresent()) {
                    this.cancel();
                    SpawnManager.this.movementCheckers.remove(mobType);
                    SpawnManager.this.spawnedStatus.put(mobType, false);
                    SpawnManager.this.spawnedEntityIds.remove(mobType);
                    return;
                }
                ActiveMob mob = (ActiveMob)optionalMob.get();
                Location currentLoc = mob.getEntity().getBukkitEntity().getLocation();
                if (currentLoc.getWorld() == null || spawnLoc.getWorld() == null || !currentLoc.getWorld().equals(spawnLoc.getWorld())) {
                    return;
                }
                double distanceSquared = currentLoc.distanceSquared(spawnLoc);
                if (distanceSquared > maxDistanceSquared && shouldTeleport && (originalSpawn = SpawnManager.this.spawnLocations.get(mobType)) != null && SpawnManager.this.isValidLocation(originalSpawn)) {
                    Location teleportLoc = originalSpawn.clone();
                    teleportLoc.setYaw(currentLoc.getYaw());
                    teleportLoc.setPitch(currentLoc.getPitch());
                    mob.getEntity().getBukkitEntity().teleport(teleportLoc);
                }
            }
        }.runTaskTimer((Plugin)this.plugin, (long)checkInterval, (long)checkInterval);
        this.movementCheckers.put(mobType, task);
    }

    public void spawnAllMobs() {
        for (final String mobType : new ArrayList<String>(this.spawnLocations.keySet())) {
            if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) continue;
            Long savedRespawnAt = this.respawnTimes.get(mobType);
            if (savedRespawnAt != null && savedRespawnAt > System.currentTimeMillis()) {
                Location spawnLoc;
                int remainingSeconds = (int)((savedRespawnAt - System.currentTimeMillis()) / 1000L);
                this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.saved-respawn-timer", LanguageManager.placeholders("mobtype", mobType, "seconds", String.valueOf(remainingSeconds))));
                if (this.plugin.getConfigManager().isRespawnHologramEnabled() && (spawnLoc = this.spawnLocations.get(mobType)) != null) {
                    this.createRespawnHologram(mobType, spawnLoc.clone(), remainingSeconds);
                }
                BukkitTask respawnTask = new BukkitRunnable(){

                    public void run() {
                        SpawnManager.this.spawnMob(mobType);
                        SpawnManager.this.respawnTasks.remove(mobType);
                    }
                }.runTaskLater((Plugin)this.plugin, (long)remainingSeconds * 20L);
                this.respawnTasks.put(mobType, respawnTask);
                continue;
            }
            if (this.plugin.getConfigManager().isKillRequirementEnabled(mobType)) {
                this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.kill-requirement-skip", LanguageManager.placeholders("mobtype", mobType)));
                continue;
            }
            this.spawnMob(mobType);
        }
    }

    public long getTimeUntilRespawn(String mobType) {
        if (this.isMobSpawned(mobType)) {
            return 0L;
        }
        Long respawnTime = this.respawnTimes.get(mobType);
        if (respawnTime == null) {
            return 0L;
        }
        long timeLeft = respawnTime - System.currentTimeMillis();
        return Math.max(timeLeft, 0L);
    }

    public void cancelRespawnTask(String mobType) {
        BukkitTask task = this.respawnTasks.remove(mobType);
        if (task != null) {
            task.cancel();
        }
        this.respawnTimes.remove(mobType);
    }

    public void cleanup() {
        this.respawnTasks.values().forEach(BukkitTask::cancel);
        this.announcementTasks.values().forEach(BukkitTask::cancel);
        this.movementCheckers.values().forEach(BukkitTask::cancel);
        this.timeoutTasks.values().forEach(BukkitTask::cancel);
        if (this.scheduledSpawnTask != null) {
            this.scheduledSpawnTask.cancel();
        }
        this.respawnTasks.clear();
        this.announcementTasks.clear();
        this.movementCheckers.clear();
        this.timeoutTasks.clear();
        this.respawnTimes.clear();
        this.spawnedEntityIds.clear();
        this.spawnedStatus.clear();
    }

    private void startScheduledSpawnTask() {
        this.scheduledSpawnTask = new BukkitRunnable(){
            private String lastCheckedTime = "";

            public void run() {
                LocalTime now = LocalTime.now();
                String currentTime = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                if (currentTime.equals(this.lastCheckedTime)) {
                    return;
                }
                this.lastCheckedTime = currentTime;
                for (String mobType : SpawnManager.this.plugin.getConfigManager().getTrackedMobs()) {
                    List<String> scheduledTimes;
                    if (!SpawnManager.this.plugin.getConfigManager().isScheduledSpawnEnabled(mobType) || !(scheduledTimes = SpawnManager.this.plugin.getConfigManager().getScheduledSpawnTimes(mobType)).contains(currentTime)) continue;
                    SpawnManager.this.plugin.getServer().getScheduler().runTask((Plugin)SpawnManager.this.plugin, () -> {
                        SpawnManager.this.plugin.getLogger().info(SpawnManager.this.plugin.getLanguageManager().raw("logs.scheduled-spawn", LanguageManager.placeholders("mobtype", mobType, "time", currentTime)));
                        SpawnManager.this.spawnMob(mobType);
                    });
                }
            }
        }.runTaskTimerAsynchronously((Plugin)this.plugin, 0L, 20L);
    }

    public boolean deleteSpawnLocation(String mobType) {
        this.spawnLocations.remove(mobType);
        this.spawnedEntityIds.remove(mobType);
        this.spawnedStatus.remove(mobType);
        if (this.plugin.getBedrockVisualManager() != null) {
            this.plugin.getBedrockVisualManager().unregisterBossByMobType(mobType);
        }
        this.spawnConfig.set("spawns." + mobType, null);
        try {
            this.spawnConfig.save(this.spawnFile);
            return true;
        }
        catch (IOException e) {
            this.plugin.getLogger().severe(this.plugin.getLanguageManager().raw("logs.spawn-delete-failed", LanguageManager.placeholders("mobtype", mobType, "error", e.getMessage())));
            this.plugin.logError("logs.spawn-delete-failed:" + mobType, e);
            return false;
        }
    }

    public Location getSpawnLocation(String mobType) {
        return this.spawnLocations.get(mobType);
    }

    public boolean isMobSpawned(String mobType) {
        UUID entityId = this.spawnedEntityIds.get(mobType);
        if (entityId != null) {
            boolean active = MythicBukkit.inst().getMobManager().getActiveMob(entityId).isPresent();
            if (!active) {
                this.spawnedEntityIds.remove(mobType);
                this.spawnedStatus.put(mobType, false);
            }
            return active;
        }
        return this.spawnedStatus.getOrDefault(mobType, false);
    }

    public void markMobSpawned(String mobType, UUID entityId) {
        this.trackSpawnedBoss(mobType, entityId);
    }

    public void trackSpawnedBoss(String mobType, UUID entityId) {
        if (entityId == null || !this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            return;
        }
        this.spawnedStatus.put(mobType, true);
        this.spawnedEntityIds.put(mobType, entityId);
        this.scheduleBossTimeout(mobType, entityId);
    }

    public KillAllResult killAllBosses(String mobTypeFilter, String worldNameFilter) {
        int matched = 0;
        int killed = 0;
        int failed = 0;
        World worldFilter = null;
        if (worldNameFilter != null && !worldNameFilter.isBlank()) {
            worldFilter = Bukkit.getWorld(worldNameFilter);
            if (worldFilter == null) {
                return new KillAllResult(0, 0, 0, true);
            }
        }
        for (ActiveMob activeMob : new ArrayList<>(MythicBukkit.inst().getMobManager().getActiveMobs())) {
            String mobType = activeMob.getMobType();
            if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
                continue;
            }
            if (mobTypeFilter != null && !mobTypeFilter.equals(mobType)) {
                continue;
            }
            Entity entity = activeMob.getEntity() == null ? null : activeMob.getEntity().getBukkitEntity();
            if (!(entity instanceof LivingEntity livingEntity) || livingEntity.isDead() || !livingEntity.isValid()) {
                continue;
            }
            if (worldFilter != null && !worldFilter.equals(livingEntity.getWorld())) {
                continue;
            }
            matched++;
            try {
                this.cancelBossTimeout(livingEntity.getUniqueId());
                livingEntity.setHealth(0.0);
                killed++;
            } catch (Throwable throwable) {
                failed++;
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.killall-failed",
                        LanguageManager.placeholders("mobtype", mobType)));
                this.plugin.logError("logs.killall-failed:" + mobType, throwable);
            }
        }
        return new KillAllResult(matched, killed, failed, false);
    }

    private void scheduleBossTimeout(String mobType, UUID entityId) {
        this.cancelBossTimeout(entityId);
        int timeoutSeconds = this.plugin.getConfigManager().getMobTimeoutSeconds(mobType);
        if (timeoutSeconds < 0) {
            return;
        }
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                SpawnManager.this.timeoutBoss(mobType, entityId);
            }
        }.runTaskLater((Plugin)this.plugin, (long)timeoutSeconds * 20L);
        this.timeoutTasks.put(entityId, task);
    }

    private void timeoutBoss(String mobType, UUID entityId) {
        this.timeoutTasks.remove(entityId);
        Optional<ActiveMob> activeMob = MythicBukkit.inst().getMobManager().getActiveMob(entityId);
        if (activeMob.isEmpty()) {
            this.cleanupBossRuntime(mobType, entityId, true);
            return;
        }
        Entity entity = activeMob.get().getEntity() == null ? null : activeMob.get().getEntity().getBukkitEntity();
        Location location = entity == null ? null : entity.getLocation();
        try {
            if (this.plugin.getBedrockVisualManager() != null) {
                this.plugin.getBedrockVisualManager().unregisterBoss(entityId);
            }
            activeMob.get().remove();
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
            this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.boss-timeout-removed",
                    LanguageManager.placeholders("mobtype", mobType, "world", location == null || location.getWorld() == null ? "unknown" : location.getWorld().getName())));
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.boss-timeout-remove-failed",
                    LanguageManager.placeholders("mobtype", mobType, "error", throwable.getClass().getSimpleName())));
            this.plugin.logError("logs.boss-timeout-remove-failed:" + mobType, throwable);
        }
        this.cleanupBossRuntime(mobType, entityId, true);
        this.scheduleRespawn(mobType);
    }

    private void cancelBossTimeout(UUID entityId) {
        if (entityId == null) {
            return;
        }
        BukkitTask task = this.timeoutTasks.remove(entityId);
        if (task != null) {
            task.cancel();
        }
    }

    private void cleanupBossRuntime(String mobType, UUID entityId, boolean clearDamageSession) {
        this.removeExistingHologram(mobType);
        BukkitTask announcementTask = this.announcementTasks.remove(mobType);
        if (announcementTask != null) {
            announcementTask.cancel();
        }
        BukkitTask movementTask = this.movementCheckers.remove(mobType);
        if (movementTask != null) {
            movementTask.cancel();
        }
        this.cancelBossTimeout(entityId);
        if (this.plugin.getBedrockVisualManager() != null) {
            if (entityId != null) {
                this.plugin.getBedrockVisualManager().unregisterBoss(entityId);
            } else {
                this.plugin.getBedrockVisualManager().unregisterBossByMobType(mobType);
            }
        }
        UUID trackedId = this.spawnedEntityIds.get(mobType);
        if (entityId == null || entityId.equals(trackedId)) {
            this.spawnedStatus.put(mobType, false);
            this.spawnedEntityIds.remove(mobType);
        }
        if (clearDamageSession && entityId != null && this.plugin.getDamageTracker() != null) {
            this.plugin.getDamageTracker().clearBossSession(entityId);
        }
    }

    private boolean isValidLocation(Location location) {
        return location != null
                && location.getWorld() != null
                && Double.isFinite(location.getX())
                && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw())
                && Float.isFinite(location.getPitch());
    }

    public record KillAllResult(int matched, int killed, int failed, boolean worldMissing) {
    }
}





