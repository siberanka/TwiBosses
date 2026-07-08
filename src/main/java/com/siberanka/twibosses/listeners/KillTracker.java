package com.siberanka.twibosses.listeners;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class KillTracker
implements Listener {
    private final TwiBosses plugin;
    private final Map<String, Map<String, Integer>> vanillaKillCounts;
    private final Map<String, Map<String, Integer>> mythicKillCounts;

    public KillTracker(TwiBosses plugin) {
        this.plugin = plugin;
        this.vanillaKillCounts = new HashMap<String, Map<String, Integer>>();
        this.mythicKillCounts = new HashMap<String, Map<String, Integer>>();
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        for (String bossType : this.plugin.getConfigManager().getTrackedMobs()) {
            Optional mythicMob;
            if (!this.plugin.getConfigManager().isKillRequirementEnabled(bossType)) continue;
            String entityType = entity.getType().name();
            if (this.plugin.getConfigManager().getRequiredVanillaMobs(bossType).containsKey(entityType)) {
                this.incrementKillCount(bossType, entityType, true);
                this.checkSpawnConditions(bossType);
            }
            if (!(mythicMob = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId())).isPresent()) continue;
            String mobType = ((ActiveMob)mythicMob.get()).getMobType();
            this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.mythicmob-killed", LanguageManager.placeholders("mobtype", ((ActiveMob)mythicMob.get()).getMobType())));
            if (!this.plugin.getConfigManager().getRequiredMythicMobs(bossType).containsKey(mobType)) continue;
            this.incrementKillCount(bossType, mobType, false);
            this.checkSpawnConditions(bossType);
        }
    }

    private void incrementKillCount(String bossType, String mobType, boolean isVanilla) {
        Map<String, Map<String, Integer>> counts = isVanilla ? this.vanillaKillCounts : this.mythicKillCounts;
        counts.computeIfAbsent(bossType, k -> new HashMap<String, Integer>()).merge(mobType, 1, Integer::sum);
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.kill-count", LanguageManager.placeholders("boss", bossType, "mobtype", mobType, "count", String.valueOf(counts.get(bossType).get(mobType)))));
    }

    private void checkSpawnConditions(String bossType) {
        Map<String, Integer> requiredVanilla = this.plugin.getConfigManager().getRequiredVanillaMobs(bossType);
        Map<String, Integer> requiredMythic = this.plugin.getConfigManager().getRequiredMythicMobs(bossType);
        boolean vanillaComplete = this.checkRequirements(bossType, requiredVanilla, true);
        boolean mythicComplete = this.checkRequirements(bossType, requiredMythic, false);
        if (vanillaComplete && mythicComplete) {
            this.plugin.getHologramManager().removeHologram(bossType + "_respawn");
            this.plugin.getSpawnManager().cancelRespawnTask(bossType);
            this.plugin.getSpawnManager().spawnMob(bossType);
            this.resetKillCounts(bossType);
        }
    }

    private boolean checkRequirements(String bossType, Map<String, Integer> required, boolean isVanilla) {
        Map<String, Map<String, Integer>> counts = isVanilla ? this.vanillaKillCounts : this.mythicKillCounts;
        Map<String, Integer> currentCounts = counts.getOrDefault(bossType, new HashMap<String, Integer>());
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int current = currentCounts.getOrDefault(entry.getKey(), 0);
            if (current >= entry.getValue()) continue;
            return false;
        }
        return true;
    }

    public void resetKillCounts(String bossType) {
        this.vanillaKillCounts.remove(bossType);
        this.mythicKillCounts.remove(bossType);
    }

    public Map<String, Integer> getVanillaKillCounts(String bossType) {
        return this.vanillaKillCounts.getOrDefault(bossType, new HashMap());
    }

    public Map<String, Integer> getMythicKillCounts(String bossType) {
        return this.mythicKillCounts.getOrDefault(bossType, new HashMap());
    }
}





