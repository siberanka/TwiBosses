package com.siberanka.twibosses.listeners;

import com.siberanka.twibosses.TwiBosses;
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

    public DamageTracker(TwiBosses plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Projectile projectile;
        if (!this.enabled || !(event.getEntity() instanceof LivingEntity)) {
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
        this.entityMobTypes.put(entityId, mobType);
        this.entityDamageMap.computeIfAbsent(entityId, k -> new HashMap());
        Map<UUID, Double> damageMap = this.entityDamageMap.get(entityId);
        damageMap.merge(damager.getUniqueId(), event.getFinalDamage(), Double::sum);
        this.rebuildMobAggregate(mobType);
        if (this.plugin.getConfigManager().isHealthAnnouncementsEnabled()) {
            LivingEntity entity = (LivingEntity)event.getEntity();
            double maxHealth = entity.getMaxHealth();
            double currentHealth = entity.getHealth() - event.getFinalDamage();
            double percentage = currentHealth / maxHealth * 100.0;
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
        if (mythicMob.isEmpty()) {
            return;
        }
        String mobType = ((ActiveMob)mythicMob.get()).getMobType();
        Map<UUID, Double> damageMap = this.entityDamageMap.remove(entityId);
        this.entityMobTypes.remove(entityId);
        if (damageMap == null) {
            this.rebuildMobAggregate(mobType);
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
        this.plugin.getSpawnManager().handleMobDeath(mobType, event.getEntity().getLocation());
    }

    @EventHandler
    public void onMythicMobSpawn(MythicMobSpawnEvent event) {
        String mobType = event.getMobType().getInternalName();
        if (this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
            UUID entityId = event.getEntity().getUniqueId();
            this.entityMobTypes.put(entityId, mobType);
            this.entityDamageMap.put(entityId, new HashMap());
            this.rebuildMobAggregate(mobType);
            this.announcedThresholds.put(event.getEntity().getUniqueId(), new HashSet());
            this.lastTopDamage.remove(mobType);
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
            this.entityMobTypes.put(entityId, mobType);
            this.entityDamageMap.put(entityId, new HashMap());
            this.rebuildMobAggregate(mobType);
            this.plugin.getSpawnManager().markMobSpawned(mobType, entityId);
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





