package com.siberanka.twibosses.listeners;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import com.siberanka.twibosses.utils.ColorUtils;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

public class DamageTracker
implements Listener {
    private final TwiBosses plugin;
    private boolean enabled = true;
    private final Map<String, Map<UUID, Double>> mobDamageMap = new HashMap<String, Map<UUID, Double>>();
    private final Map<UUID, Map<UUID, Double>> entityDamageMap = new HashMap<UUID, Map<UUID, Double>>();
    private final Map<UUID, String> entityMobTypes = new HashMap<UUID, String>();
    private final Map<UUID, Set<Integer>> announcedThresholds = new HashMap<UUID, Set<Integer>>();
    private final Map<String, List<TopDamageEntry>> lastTopDamage = new HashMap<String, List<TopDamageEntry>>();
    private final Set<UUID> processedDeaths = new LinkedHashSet<UUID>();
    private long nextSessionLimitWarningAt;
    private long nextPlayerLimitWarningAt;

    public DamageTracker(TwiBosses plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Projectile projectile;
        if (!this.enabled || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (!Double.isFinite(finalDamage) || finalDamage <= 0.0) {
            return;
        }
        Optional mythicMob = MythicBukkit.inst().getMobManager().getActiveMob(event.getEntity().getUniqueId());
        if (mythicMob.isEmpty()) {
            return;
        }
        String mobType = ((ActiveMob)mythicMob.get()).getMobType();
        if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            return;
        }
        Player damager = null;
        if (event.getDamager() instanceof Player) {
            damager = (Player)event.getDamager();
        } else if (event.getDamager() instanceof Projectile && (projectile = (Projectile)event.getDamager()).getShooter() instanceof Player) {
            damager = (Player)projectile.getShooter();
        }
        if (damager == null) {
            return;
        }
        UUID entityId = event.getEntity().getUniqueId();
        if (!this.ensureDamageSession(entityId, mobType)) {
            return;
        }
        Map<UUID, Double> damageMap = this.entityDamageMap.get(entityId);
        UUID damagerId = damager.getUniqueId();
        if (!damageMap.containsKey(damagerId) && damageMap.size() >= this.plugin.getConfigManager().getMaxDamagePlayersPerBoss()) {
            this.warnPlayerLimit(mobType, this.plugin.getConfigManager().getMaxDamagePlayersPerBoss());
            return;
        }
        damageMap.merge(damagerId, finalDamage, Double::sum);
        this.rebuildMobAggregate(mobType);
        if (this.plugin.getConfigManager().isHealthAnnouncementsEnabled()) {
            LivingEntity entity = (LivingEntity)event.getEntity();
            double maxHealth = entity.getMaxHealth();
            if (!Double.isFinite(maxHealth) || maxHealth <= 0.0) {
                return;
            }
            double currentHealth = Math.max(0.0, entity.getHealth() - finalDamage);
            double percentage = Math.max(0.0, Math.min(100.0, currentHealth / maxHealth * 100.0));
            List<Integer> thresholds = this.plugin.getConfigManager().getHealthThresholds();
            Set announced = this.announcedThresholds.computeIfAbsent(entity.getUniqueId(), k -> new HashSet());
            for (Integer threshold : thresholds) {
                if (!(percentage <= (double)threshold.intValue()) || !announced.add(threshold)) continue;
                String announcement = this.plugin.getConfigManager().getHealthAnnouncementFormat().replace("{mobname}", this.plugin.getConfigManager().getMobDisplayName(mobType)).replace("{percentage}", String.format("%.1f", percentage));
                Bukkit.broadcastMessage((String)ColorUtils.colorize(announcement));
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        if (!this.markDeathProcessed(entityId)) {
            return;
        }
        Optional mythicMob = MythicBukkit.inst().getMobManager().getActiveMob(entityId);
        String mobType = (String)this.entityMobTypes.get(entityId);
        if (mythicMob.isPresent()) {
            mobType = ((ActiveMob)mythicMob.get()).getMobType();
        }
        if (mobType == null || !this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            this.entityDamageMap.remove(entityId);
            this.entityMobTypes.remove(entityId);
            this.announcedThresholds.remove(entityId);
            return;
        }
        Map<UUID, Double> damageMap = this.entityDamageMap.remove(entityId);
        this.entityMobTypes.remove(entityId);
        if (damageMap == null) {
            this.rebuildMobAggregate(mobType);
            this.announcedThresholds.remove(entityId);
            this.plugin.getSpawnManager().handleMobDeath(mobType, event.getEntity().getLocation(), entityId);
            return;
        }
        HashMap<UUID, Double> qualifiedDamage = new HashMap<UUID, Double>();
        for (Map.Entry<UUID, Double> entry2 : damageMap.entrySet()) {
            if (this.meetsThreshold(mobType, entry2.getValue())) {
                qualifiedDamage.put(entry2.getKey(), entry2.getValue());
                continue;
            }
            Player player = Bukkit.getPlayer((UUID)entry2.getKey());
            if (player == null) continue;
            String message = this.plugin.getConfigManager().getThresholdMessage(mobType).replace("{damage}", String.format("%.0f", this.plugin.getConfigManager().getMinimumDamageThreshold(mobType))).replace("{mobname}", this.plugin.getConfigManager().getMobDisplayName(mobType));
            player.sendMessage(ColorUtils.colorize(message));
        }
        double totalDamage = qualifiedDamage.values().stream().mapToDouble(Double::doubleValue).sum();
        List<TopDamageEntry> topList = qualifiedDamage.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .map(entry -> {
            Player player = Bukkit.getPlayer((UUID)((UUID)entry.getKey()));
            String name = player != null ? player.getName() : DamageTracker.this.plugin.getLanguageManager().raw("general.unknown");
            double percentage = totalDamage > 0.0 ? (Double)entry.getValue() / totalDamage * 100.0 : 0.0;
            return new TopDamageEntry((UUID)entry.getKey(), name, (Double)entry.getValue(), percentage);
        }).collect(Collectors.toList());
        this.lastTopDamage.put(mobType, topList);
        Player lasthitPlayer = event.getEntity().getKiller();
        this.announcedThresholds.remove(entityId);
        this.plugin.getRewardManager().handleMobDeath(mobType, qualifiedDamage, event.getEntity().getLocation(), lasthitPlayer);
        this.rebuildMobAggregate(mobType);
        this.plugin.getSpawnManager().handleMobDeath(mobType, event.getEntity().getLocation(), entityId);
    }

    public void clearBossSession(UUID entityId) {
        String mobType = this.entityMobTypes.remove(entityId);
        this.entityDamageMap.remove(entityId);
        this.announcedThresholds.remove(entityId);
        this.processedDeaths.remove(entityId);
        if (mobType != null) {
            this.rebuildMobAggregate(mobType);
        }
    }

    @EventHandler
    public void onMythicMobSpawn(MythicMobSpawnEvent event) {
        String mobType = event.getMobType().getInternalName();
        if (this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            UUID entityId = event.getEntity().getUniqueId();
            if (this.ensureDamageSession(entityId, mobType)) {
                this.rebuildMobAggregate(mobType);
            }
            this.plugin.getSpawnManager().trackSpawnedBoss(mobType, entityId);
            this.announcedThresholds.put(event.getEntity().getUniqueId(), new HashSet());
            this.lastTopDamage.remove(mobType);
            if (this.plugin.getBedrockVisualManager() != null) {
                this.plugin.getBedrockVisualManager().registerBoss(mobType, event.getEntity());
            }
        }
    }

    private boolean meetsThreshold(String mobType, double damage) {
        if (!this.plugin.getConfigManager().isDamageThresholdEnabled()) {
            return true;
        }
        return damage >= this.plugin.getConfigManager().getMinimumDamageThreshold(mobType);
    }

    public boolean spawnMobAtLocation(String mobType, World world, double x, double y, double z) {
        if (!this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            return false;
        }
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        if (this.plugin.getConfigManager().shouldPreventDuplicateAliveBoss() && this.plugin.getSpawnManager().isMobSpawned(mobType)) {
            return false;
        }
        Location location = new Location(world, x, y, z);
        ActiveMob spawnedMob = MythicBukkit.inst().getMobManager().spawnMob(mobType, location);
        if (spawnedMob != null) {
            UUID entityId = spawnedMob.getEntity().getUniqueId();
            if (this.ensureDamageSession(entityId, mobType)) {
                this.rebuildMobAggregate(mobType);
            }
            this.plugin.getSpawnManager().markMobSpawned(mobType, entityId);
            if (this.plugin.getBedrockVisualManager() != null) {
                this.plugin.getBedrockVisualManager().registerBoss(mobType, spawnedMob.getEntity().getBukkitEntity());
            }
            this.plugin.getDisplayManager().showMobSpawn(mobType, location);
            this.plugin.getWebhookManager().sendSpawnWebhook(mobType, location);
            if (this.plugin.getConfigManager().isMobMovementRestricted()) {
                this.plugin.getSpawnManager().startMovementChecker(mobType, spawnedMob.getEntity().getUniqueId(), location);
            }
            return true;
        }
        return false;
    }

    public Map<String, List<TopDamageEntry>> getLastTopDamage() {
        return this.lastTopDamage;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Map<UUID, Double>> getMobDamageMap() {
        return this.mobDamageMap;
    }

    public void clearData() {
        this.mobDamageMap.clear();
        this.entityDamageMap.clear();
        this.entityMobTypes.clear();
        this.announcedThresholds.clear();
        this.lastTopDamage.clear();
        this.processedDeaths.clear();
    }

    private boolean markDeathProcessed(UUID entityId) {
        boolean added = this.processedDeaths.add(entityId);
        while (this.processedDeaths.size() > 512) {
            Iterator<UUID> iterator = this.processedDeaths.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        return added;
    }

    private boolean ensureDamageSession(UUID entityId, String mobType) {
        if (this.entityDamageMap.containsKey(entityId)) {
            this.entityMobTypes.put(entityId, mobType);
            return true;
        }
        this.pruneStaleSessions();
        int limit = this.plugin.getConfigManager().getMaxActiveBossDamageSessions();
        if (this.entityDamageMap.size() >= limit) {
            this.warnSessionLimit(limit);
            return false;
        }
        this.entityMobTypes.put(entityId, mobType);
        this.entityDamageMap.put(entityId, new HashMap());
        return true;
    }

    private void pruneStaleSessions() {
        Set<String> affectedMobTypes = new HashSet<>();
        Iterator<Map.Entry<UUID, String>> iterator = this.entityMobTypes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, String> entry = iterator.next();
            if (MythicBukkit.inst().getMobManager().getActiveMob(entry.getKey()).isPresent()) {
                continue;
            }
            affectedMobTypes.add(entry.getValue());
            this.entityDamageMap.remove(entry.getKey());
            this.announcedThresholds.remove(entry.getKey());
            iterator.remove();
        }
        Iterator<UUID> damageIterator = this.entityDamageMap.keySet().iterator();
        while (damageIterator.hasNext()) {
            UUID entityId = damageIterator.next();
            if (this.entityMobTypes.containsKey(entityId)) {
                continue;
            }
            damageIterator.remove();
        }
        for (String mobType : affectedMobTypes) {
            this.rebuildMobAggregate(mobType);
        }
    }

    private void warnSessionLimit(int limit) {
        long now = System.currentTimeMillis();
        if (now < this.nextSessionLimitWarningAt) {
            return;
        }
        this.nextSessionLimitWarningAt = now + 30_000L;
        this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                "logs.damage-session-limit",
                LanguageManager.placeholders("limit", String.valueOf(limit))));
    }

    private void warnPlayerLimit(String mobType, int limit) {
        long now = System.currentTimeMillis();
        if (now < this.nextPlayerLimitWarningAt) {
            return;
        }
        this.nextPlayerLimitWarningAt = now + 30_000L;
        this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                "logs.damage-player-limit",
                LanguageManager.placeholders("mobtype", mobType, "limit", String.valueOf(limit))));
    }

    private void rebuildMobAggregate(String mobType) {
        Map<UUID, Double> aggregate = new HashMap<UUID, Double>();
        for (Map.Entry<UUID, String> entry : this.entityMobTypes.entrySet()) {
            if (!mobType.equals(entry.getValue())) {
                continue;
            }
            Map<UUID, Double> damage = this.entityDamageMap.get(entry.getKey());
            if (damage == null) {
                continue;
            }
            for (Map.Entry<UUID, Double> damageEntry : damage.entrySet()) {
                aggregate.merge(damageEntry.getKey(), damageEntry.getValue(), Double::sum);
            }
        }
        if (aggregate.isEmpty()) {
            this.mobDamageMap.remove(mobType);
        } else {
            this.mobDamageMap.put(mobType, aggregate);
        }
    }

    public static class TopDamageEntry {
        private final UUID playerId;
        private final String playerName;
        private final double damage;
        private final double percentage;

        public TopDamageEntry(UUID playerId, String playerName, double damage, double percentage) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.damage = damage;
            this.percentage = percentage;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public String getPlayerName() {
            return this.playerName;
        }

        public double getDamage() {
            return this.damage;
        }

        public double getPercentage() {
            return this.percentage;
        }
    }
}





