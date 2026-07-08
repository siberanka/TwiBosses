package com.siberanka.twibosses.placeholders;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.listeners.DamageTracker;
import com.siberanka.twibosses.manager.LanguageManager;
import com.siberanka.twibosses.utils.ColorUtils;
import com.siberanka.twibosses.utils.TimeUtils;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class MobPlaceholders
extends PlaceholderExpansion {
    private final TwiBosses plugin;
    private final DecimalFormat damageFormat;
    private final DecimalFormat percentFormat;

    public MobPlaceholders(TwiBosses plugin) {
        this.plugin = plugin;
        this.damageFormat = new DecimalFormat(plugin.getConfigManager().getDamageFormat());
        this.percentFormat = new DecimalFormat(plugin.getConfigManager().getPercentageFormat());
    }

    @NotNull
    public String getIdentifier() {
        return "twibosses";
    }

    @NotNull
    public String getAuthor() {
        return "Siberanka";
    }

    @NotNull
    public String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params.startsWith("respawn_")) {
            String mobType = params.substring(8);
            long timeLeft = this.plugin.getSpawnManager().getTimeUntilRespawn(mobType);
            if (timeLeft > 0L) {
                String message = this.plugin.getConfigManager().getRespawnCountdownMessage().replace("{mobname}", this.plugin.getConfigManager().getMobDisplayName(mobType)).replace("{time}", TimeUtils.formatTime(timeLeft / 1000L));
                return ColorUtils.colorize(message);
            }
                return this.plugin.getLanguageManager().get("placeholders.mob-ready");
        }
        if (params.endsWith("_needed")) {
            int required;
            int current;
            String mobType = params.substring(0, params.length() - 7);
            if (!this.plugin.getConfigManager().isKillRequirementEnabled(mobType)) {
                return this.plugin.getLanguageManager().raw("placeholders.not-available");
            }
            Map<String, Integer> vanillaRequired = this.plugin.getConfigManager().getRequiredVanillaMobs(mobType);
            Map<String, Integer> mythicRequired = this.plugin.getConfigManager().getRequiredMythicMobs(mobType);
            Map<String, Integer> vanillaCurrent = this.plugin.getKillTracker().getVanillaKillCounts(mobType);
            Map<String, Integer> mythicCurrent = this.plugin.getKillTracker().getMythicKillCounts(mobType);
            StringBuilder needed = new StringBuilder();
            for (Map.Entry<String, Integer> entry : vanillaRequired.entrySet()) {
                current = vanillaCurrent.getOrDefault(entry.getKey(), 0);
                required = entry.getValue();
                if (needed.length() > 0) {
                    needed.append(", ");
                }
                needed.append(entry.getKey()).append(": ").append(current).append("/").append(required);
            }
            for (Map.Entry<String, Integer> entry : mythicRequired.entrySet()) {
                current = mythicCurrent.getOrDefault(entry.getKey(), 0);
                required = entry.getValue();
                if (needed.length() > 0) {
                    needed.append(", ");
                }
                needed.append(entry.getKey()).append(": ").append(current).append("/").append(required);
            }
            return needed.length() > 0 ? needed.toString() : this.plugin.getLanguageManager().raw("placeholders.none");
        }
        if (params.endsWith("_spawned")) {
            String mobType = params.substring(0, params.length() - 8);
            boolean isSpawned = this.plugin.getSpawnManager().isMobSpawned(mobType);
            return isSpawned ? this.plugin.getLanguageManager().raw("placeholders.yes") : this.plugin.getLanguageManager().raw("placeholders.no");
        }
        if (params.endsWith("_cooldown")) {
            String mobType = params.substring(0, params.length() - 9);
            long timeLeft = this.plugin.getSpawnManager().getTimeUntilRespawn(mobType);
            if (timeLeft > 0L) {
                return TimeUtils.formatTime(timeLeft / 1000L);
            }
            return this.plugin.getLanguageManager().raw("placeholders.ready");
        }
        if (params.startsWith("top_")) {
            int position;
            String[] parts = params.split("_");
            if (parts.length != 3) {
                return "";
            }
            String mobType = parts[1];
            try {
                position = Integer.parseInt(parts[2]);
                if (position < 1 || position > 5) {
                    return "";
                }
            }
            catch (NumberFormatException e) {
                return "";
            }
            Map<UUID, Double> currentDamageMap = this.plugin.getDamageTracker().getMobDamageMap().get(mobType);
            if (currentDamageMap != null && !currentDamageMap.isEmpty()) {
                List<Map.Entry<UUID, Double>> sortedDamage = currentDamageMap.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                        .collect(Collectors.toList());
                if (position > sortedDamage.size()) {
                    return this.plugin.getLanguageManager().raw("placeholders.no-player");
                }
                Map.Entry<UUID, Double> entry = sortedDamage.get(position - 1);
                OfflinePlayer damager = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
                String name = damager.getName() != null ? damager.getName() : this.plugin.getLanguageManager().raw("general.unknown");
                double totalDamage = currentDamageMap.values().stream().mapToDouble(Double::doubleValue).sum();
                double percentage = (Double)entry.getValue() / totalDamage * 100.0;
                return this.plugin.getLanguageManager().get("placeholders.top-format", LanguageManager.placeholders("player", name, "damage", this.damageFormat.format(entry.getValue()), "percentage", this.percentFormat.format(percentage)));
            }
            List<DamageTracker.TopDamageEntry> lastTop = this.plugin.getDamageTracker().getLastTopDamage().get(mobType);
            if (lastTop != null && position <= lastTop.size()) {
                DamageTracker.TopDamageEntry entry = lastTop.get(position - 1);
                return this.plugin.getLanguageManager().get("placeholders.top-format", LanguageManager.placeholders("player", entry.getPlayerName(), "damage", this.damageFormat.format(entry.getDamage()), "percentage", this.percentFormat.format(entry.getPercentage())));
            }
            return this.plugin.getLanguageManager().raw("placeholders.no-data");
        }
        return null;
    }
}





