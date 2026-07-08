package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.rewards.PermissionReward;
import com.siberanka.twibosses.rewards.RewardBundle;
import com.siberanka.twibosses.rewards.RewardDrop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

public class ConfigManager {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TwiBosses plugin;
    private FileConfiguration config;
    private FileConfiguration bossesConfig;

    public ConfigManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.repairConfigFile();
        this.repairBossesFile();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.bossesConfig = YamlConfiguration.loadConfiguration(this.bossesFile());
    }

    private synchronized void repairConfigFile() {
        try {
            InputStream resourceStream = this.plugin.getResource("config.yml");
            if (resourceStream == null) {
                return;
            }
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            File configFile = new File(this.plugin.getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                this.plugin.saveResource("config.yml", false);
                return;
            }
            YamlConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
            boolean updated = this.migrateLegacyConfig(diskConfig);
            updated |= this.migrateTrackedMobsToBosses(diskConfig);
            List<String> existingKeys = new ArrayList<>(diskConfig.getKeys(true));
            existingKeys.sort((left, right) -> Integer.compare(depth(right), depth(left)));
            for (String key : existingKeys) {
                if (this.isAllowedConfigPath(key, defaultConfig)) {
                    continue;
                }
                diskConfig.set(key, null);
                updated = true;
            }
            List<String> defaultKeys = new ArrayList<>(defaultConfig.getKeys(true));
            defaultKeys.sort((left, right) -> Integer.compare(depth(left), depth(right)));
            for (String key : defaultKeys) {
                Object expected = defaultConfig.get(key);
                if (this.isLegacyRewardListPath(key, diskConfig)) {
                    continue;
                }
                if (expected instanceof ConfigurationSection) {
                    if (!diskConfig.isConfigurationSection(key)) {
                        diskConfig.set(key, null);
                        diskConfig.createSection(key);
                        updated = true;
                    }
                    continue;
                }
                if (!diskConfig.contains(key) || !sameKind(diskConfig.get(key), expected)) {
                    diskConfig.set(key, expected);
                    updated = true;
                }
            }
            if (updated) {
                this.backup(configFile, "config");
                this.saveSafely(diskConfig, configFile);
                this.logInfo("logs.configuration-repaired");
            }
        }
        catch (Exception e) {
            this.logWarning("logs.configuration-repair-failed", Map.of("error", e.getMessage()));
            this.plugin.logError("logs.configuration-repair-failed", e);
        }
    }

    private synchronized void repairBossesFile() {
        try {
            InputStream resourceStream = this.plugin.getResource("bosses.yml");
            if (resourceStream == null) {
                return;
            }
            YamlConfiguration defaultBosses = YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            File bossesFile = this.bossesFile();
            if (!bossesFile.exists()) {
                this.plugin.saveResource("bosses.yml", false);
                return;
            }
            YamlConfiguration diskBosses = YamlConfiguration.loadConfiguration(bossesFile);
            boolean updated = false;
            List<String> existingKeys = new ArrayList<>(diskBosses.getKeys(true));
            existingKeys.sort((left, right) -> Integer.compare(depth(right), depth(left)));
            for (String key : existingKeys) {
                if (this.isAllowedBossesPath(key, defaultBosses)) {
                    continue;
                }
                diskBosses.set(key, null);
                updated = true;
            }
            List<String> defaultKeys = new ArrayList<>(defaultBosses.getKeys(true));
            defaultKeys.sort((left, right) -> Integer.compare(depth(left), depth(right)));
            for (String key : defaultKeys) {
                Object expected = defaultBosses.get(key);
                if (this.isLegacyRewardListPath(key, diskBosses)) {
                    continue;
                }
                if (expected instanceof ConfigurationSection) {
                    if (!diskBosses.isConfigurationSection(key)) {
                        diskBosses.set(key, null);
                        diskBosses.createSection(key);
                        updated = true;
                    }
                    continue;
                }
                if (!diskBosses.contains(key) || !sameKind(diskBosses.get(key), expected)) {
                    diskBosses.set(key, expected);
                    updated = true;
                }
            }
            if (updated) {
                this.backup(bossesFile, "bosses");
                this.saveSafely(diskBosses, bossesFile);
                this.logInfo("logs.bosses-configuration-repaired");
            }
        } catch (Exception e) {
            this.logWarning("logs.bosses-configuration-repair-failed", Map.of("error", e.getMessage()));
            this.plugin.logError("logs.bosses-configuration-repair-failed", e);
        }
    }

    private boolean migrateTrackedMobsToBosses(YamlConfiguration config) throws IOException {
        if (!config.contains("tracked-mobs")) {
            return false;
        }
        File bossesFile = this.bossesFile();
        boolean existed = bossesFile.exists();
        YamlConfiguration bosses = YamlConfiguration.loadConfiguration(bossesFile);
        ConfigurationSection sourceBosses = config.getConfigurationSection("tracked-mobs");
        boolean bossesUpdated = false;
        if (sourceBosses != null) {
            ConfigurationSection targetBosses = bosses.getConfigurationSection("tracked-mobs");
            if (targetBosses == null) {
                targetBosses = bosses.createSection("tracked-mobs");
                bossesUpdated = true;
            }
            for (String mobType : sourceBosses.getKeys(false)) {
                if (targetBosses.contains(mobType)) {
                    continue;
                }
                ConfigurationSection mobSection = sourceBosses.getConfigurationSection(mobType);
                if (mobSection != null) {
                    targetBosses.createSection(mobType, this.sectionToMap(mobSection));
                } else {
                    targetBosses.set(mobType, sourceBosses.get(mobType));
                }
                bossesUpdated = true;
            }
        } else if (!bosses.contains("tracked-mobs")) {
            bosses.set("tracked-mobs", config.get("tracked-mobs"));
            bossesUpdated = true;
        }
        if (bossesUpdated) {
            if (existed) {
                this.backup(bossesFile, "bosses");
            }
            this.saveSafely(bosses, bossesFile);
        }
        config.set("tracked-mobs", null);
        return true;
    }

    private File bossesFile() {
        return new File(this.plugin.getDataFolder(), "bosses.yml");
    }

    public void reloadConfig() {
        this.repairConfigFile();
        this.repairBossesFile();
        this.plugin.reloadConfig();
        this.config = this.plugin.getConfig();
        this.bossesConfig = YamlConfiguration.loadConfiguration(this.bossesFile());
    }

    public String getMessage(String path) {
        if (this.plugin.getLanguageManager() != null) {
            return this.plugin.getLanguageManager().get("commands." + path);
        }
        return ChatColor.translateAlternateColorCodes((char)'&', (String)this.config.getString("messages." + path, ""));
    }

    public String getLanguageCode() {
        return this.config.getString("settings.language", "tr").toLowerCase();
    }

    public boolean isActionBarEnabled() {
        return this.config.getBoolean("display.actionbar.enabled", true);
    }

    public String getActionBarFormat() {
        return this.plugin.getLanguageManager().raw("display.actionbar");
    }

    public int getActionBarInterval() {
        return this.config.getInt("display.actionbar.update-interval", 20);
    }

    public int getMaxTopEntries() {
        return this.config.getInt("display.top-list.max-entries", 5);
    }

    public String getTopListFormat() {
        return this.plugin.getLanguageManager().raw("display.top-list");
    }

    public String getDamageFormat() {
        return this.config.getString("placeholders.damage-format", "#.##");
    }

    public String getPercentageFormat() {
        return this.config.getString("placeholders.percentage-format", "#.#");
    }

    public boolean isSpawnTitleEnabled() {
        return this.config.getBoolean("display.spawn-title.enabled", true);
    }

    public String getSpawnTitleFormat() {
        return this.plugin.getLanguageManager().raw("display.spawn-title.title");
    }

    public String getSpawnSubtitleFormat() {
        return this.plugin.getLanguageManager().raw("display.spawn-title.subtitle");
    }

    public int getSpawnTitleFadeIn() {
        return this.config.getInt("display.spawn-title.fade-in", 10);
    }

    public int getSpawnTitleStay() {
        return this.config.getInt("display.spawn-title.stay", 40);
    }

    public int getSpawnTitleFadeOut() {
        return this.config.getInt("display.spawn-title.fade-out", 10);
    }

    public boolean isSpawnSoundEnabled() {
        return this.config.getBoolean("display.spawn-title.sound.enabled", true);
    }

    public String getSpawnSoundType() {
        return this.config.getString("display.spawn-title.sound.type", "ENTITY_ENDER_DRAGON_GROWL");
    }

    public float getSpawnSoundVolume() {
        return (float)this.config.getDouble("display.spawn-title.sound.volume", 1.0);
    }

    public float getSpawnSoundPitch() {
        return (float)this.config.getDouble("display.spawn-title.sound.pitch", 1.0);
    }

    public boolean isDeathTitleEnabled() {
        return this.config.getBoolean("display.death-title.enabled", true);
    }

    public String getDeathTitleFormat() {
        return this.plugin.getLanguageManager().raw("display.death-title.title");
    }

    public String getDeathSubtitleFormat() {
        return this.plugin.getLanguageManager().raw("display.death-title.subtitle");
    }

    public int getDeathTitleFadeIn() {
        return this.config.getInt("display.death-title.fade-in", 10);
    }

    public int getDeathTitleStay() {
        return this.config.getInt("display.death-title.stay", 40);
    }

    public int getDeathTitleFadeOut() {
        return this.config.getInt("display.death-title.fade-out", 10);
    }

    public boolean isDeathSoundEnabled() {
        return this.config.getBoolean("display.death-title.sound.enabled", true);
    }

    public String getDeathSoundType() {
        return this.config.getString("display.death-title.sound.type", "ENTITY_WITHER_DEATH");
    }

    public float getDeathSoundVolume() {
        return (float)this.config.getDouble("display.death-title.sound.volume", 1.0);
    }

    public float getDeathSoundPitch() {
        return (float)this.config.getDouble("display.death-title.sound.pitch", 1.0);
    }

    public String getTopDamageHeader() {
        return this.plugin.getLanguageManager().raw("display.top-damage.header");
    }

    public String getTopDamageKillerFormat() {
        return this.plugin.getLanguageManager().raw("display.top-damage.killer-format");
    }

    public String getRespawnCountdownMessage() {
        return this.plugin.getLanguageManager().raw("placeholders.respawn-countdown");
    }

    public String getTopDamageListHeader() {
        return this.plugin.getLanguageManager().raw("display.top-damage.top-header");
    }

    public String getTopDamagePlayerFormat() {
        return this.plugin.getLanguageManager().raw("display.top-damage.player-format");
    }

    public String getTopDamageFooter() {
        return this.plugin.getLanguageManager().raw("display.top-damage.footer");
    }

    public boolean isRewardBroadcastEnabled() {
        return this.config.getBoolean("display.reward-broadcast.enable", this.config.getBoolean("display.top-damage.reward-broadcast.enabled", true));
    }

    public boolean isTopDamageBroadcastEnabled() {
        return this.config.getBoolean("display.top-damage.enable", true);
    }

    public String getRewardsHeader() {
        return this.plugin.getLanguageManager().raw("display.reward-broadcast.rewards-header");
    }

    public String getRewardFormat() {
        return this.plugin.getLanguageManager().raw("display.reward-broadcast.reward-format");
    }

    public boolean isRespawnHologramEnabled() {
        return this.config.getBoolean("display.respawn-hologram.enabled", true);
    }

    public List<String> getRespawnHologramLines() {
        return this.plugin.getLanguageManager().list("display.respawn-hologram.lines", Collections.emptyMap());
    }

    public Set<String> getTrackedMobs() {
        ConfigurationSection section = this.bossesConfig.getConfigurationSection("tracked-mobs");
        return section != null ? section.getKeys(false) : Set.of();
    }

    public String getMobDisplayName(String mobType) {
        String translated = this.plugin.getLanguageManager().raw("mobs." + mobType + ".display-name");
        return translated.isBlank() ? mobType : translated;
    }

    public List<String> getMobAnnouncement(String mobType) {
        return this.plugin.getLanguageManager().list("mobs." + mobType + ".announcement", Collections.emptyMap());
    }

    public List<String> getDeathMessage(String mobType) {
        List<String> messages = this.plugin.getLanguageManager().list("mobs." + mobType + ".death-message", Collections.emptyMap());
        return messages.isEmpty() ? Collections.singletonList(this.plugin.getLanguageManager().get("display.death-message-default")) : messages;
    }

    public boolean isMobRespawnEnabled(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".respawn.enabled", true);
    }

    public int getMobRespawnTime(String mobType) {
        if (!this.isMobRespawnEnabled(mobType)) {
            return -1;
        }
        return this.bossesConfig.getInt("tracked-mobs." + mobType + ".respawn.time", 300);
    }

    public int getMobTimeoutSeconds(String mobType) {
        int value = this.bossesConfig.getInt("tracked-mobs." + mobType + ".timeout-seconds", -1);
        if (value < 0) {
            return -1;
        }
        return Math.max(1, Math.min(604_800, value));
    }

    public Map<Integer, List<String>> getAllMobRewards(String mobType) {
        HashMap<Integer, List<String>> rewards = new HashMap<Integer, List<String>>();
        String path = "tracked-mobs." + mobType + ".rewards";
        ConfigurationSection section = this.bossesConfig.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!key.startsWith("top-")) continue;
                try {
                    int pos = Integer.parseInt(key.substring(4));
                    if (section.isList(key)) {
                        rewards.put(pos, section.getStringList(key));
                    } else {
                        rewards.put(pos, section.getStringList(key + ".commands"));
                    }
                }
                catch (NumberFormatException numberFormatException) {}
            }
        }
        return rewards;
    }

    public Map<Integer, RewardBundle> getRankRewardBundles(String mobType) {
        HashMap<Integer, RewardBundle> rewards = new HashMap<>();
        String path = "tracked-mobs." + mobType + ".rewards";
        ConfigurationSection section = this.bossesConfig.getConfigurationSection(path);
        if (section == null) {
            return rewards;
        }
        int maxRanks = this.getMaxRewardRanks();
        for (String key : section.getKeys(false)) {
            if (!key.startsWith("top-")) {
                continue;
            }
            try {
                int position = Integer.parseInt(key.substring(4));
                if (position < 1 || position > maxRanks) {
                    continue;
                }
                rewards.put(position, this.readRewardBundle(path + "." + key));
            } catch (NumberFormatException ignored) {
            }
        }
        return rewards;
    }

    public boolean isParticipationRewardEnabled(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".participation-reward.enabled", false);
    }

    public double getParticipationMinDamage(String mobType) {
        return this.bossesConfig.getDouble("tracked-mobs." + mobType + ".participation-reward.min-damage", 0.0);
    }

    public List<String> getParticipationCommands(String mobType) {
        return this.bossesConfig.getStringList("tracked-mobs." + mobType + ".participation-reward.commands");
    }

    public RewardBundle getParticipationRewardBundle(String mobType) {
        return this.readRewardBundle("tracked-mobs." + mobType + ".participation-reward");
    }

    public boolean isLasthitRewardEnabled(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".lasthit-reward.enabled", false);
    }

    public double getLasthitMinDamage(String mobType) {
        return this.bossesConfig.getDouble("tracked-mobs." + mobType + ".lasthit-reward.min-damage", 0.0);
    }

    public List<String> getLasthitCommands(String mobType) {
        return this.bossesConfig.getStringList("tracked-mobs." + mobType + ".lasthit-reward.commands");
    }

    public RewardBundle getLasthitRewardBundle(String mobType) {
        return this.readRewardBundle("tracked-mobs." + mobType + ".lasthit-reward");
    }

    public boolean isPermissionRewardsEnabled(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".permission-rewards.enabled", false);
    }

    public boolean shouldStopAfterFirstPermissionReward(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".permission-rewards.stop-after-first-match", false);
    }

    public List<PermissionReward> getPermissionRewards(String mobType) {
        String path = "tracked-mobs." + mobType + ".permission-rewards.rewards";
        ConfigurationSection section = this.bossesConfig.getConfigurationSection(path);
        if (section == null) {
            return Collections.emptyList();
        }
        int maxRewards = this.getMaxPermissionRewardsPerMob();
        if (maxRewards <= 0) {
            return Collections.emptyList();
        }
        List<PermissionReward> rewards = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            if (rewards.size() >= maxRewards) {
                break;
            }
            if (!isSafePermissionRewardId(id)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                        "logs.permission-reward-invalid",
                        LanguageManager.placeholders("id", id, "reason", "invalid id")));
                continue;
            }
            String rewardPath = path + "." + id;
            String permission = this.bossesConfig.getString(rewardPath + ".permission", "");
            if (!isSafePermissionNode(permission)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                        "logs.permission-reward-invalid",
                        LanguageManager.placeholders("id", id, "reason", "invalid permission")));
                continue;
            }
            RewardBundle bundle = this.readRewardBundle(rewardPath);
            if (bundle.isEmpty()) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                        "logs.permission-reward-invalid",
                        LanguageManager.placeholders("id", id, "reason", "empty bundle")));
                continue;
            }
            rewards.add(new PermissionReward(id, permission, bundle));
        }
        return rewards;
    }

    public boolean isKillRequirementEnabled(String bossType) {
        return this.config.getBoolean("boss-spawn-conditions.mobs." + bossType + ".required-kills.enabled", false);
    }

    public Map<String, Integer> getRequiredVanillaMobs(String bossType) {
        HashMap<String, Integer> required = new HashMap<String, Integer>();
        String path = "boss-spawn-conditions.mobs." + bossType + ".required-kills.vanilla-mobs";
        ConfigurationSection section = this.config.getConfigurationSection(path);
        if (section != null) {
            for (String mobType : section.getKeys(false)) {
                required.put(mobType, section.getInt(mobType));
            }
        }
        return required;
    }

    public Map<String, Integer> getRequiredMythicMobs(String bossType) {
        HashMap<String, Integer> required = new HashMap<String, Integer>();
        String path = "boss-spawn-conditions.mobs." + bossType + ".required-kills.mythic-mobs";
        ConfigurationSection section = this.config.getConfigurationSection(path);
        if (section != null) {
            for (String mobType : section.getKeys(false)) {
                required.put(mobType, section.getInt(mobType));
            }
        }
        return required;
    }

    public int getNormalRespawnTime(String bossType) {
        return this.config.getInt("boss-spawn-conditions.mobs." + bossType + ".normal-respawn-time", 3600);
    }

    public boolean isDamageThresholdEnabled() {
        return this.config.getBoolean("damage-settings.threshold.enabled", true);
    }

    public double getMinimumDamageThreshold(String mobType) {
        String path = "damage-settings.threshold.per-mob." + mobType + ".minimum-damage";
        if (this.config.contains(path)) {
            return this.config.getDouble(path);
        }
        return this.config.getDouble("damage-settings.threshold.default.minimum-damage", 1000.0);
    }

    public String getThresholdMessage(String mobType) {
        String message = this.plugin.getLanguageManager().raw("damage.threshold.per-mob." + mobType + ".message");
        if (!message.isBlank()) {
            return message;
        }
        return this.plugin.getLanguageManager().raw("damage.threshold.default");
    }

    public boolean isMetricsEnabled() {
        return this.booleanWithLegacy("runtime.metrics.enabled", "metrics.enabled", true);
    }

    public String getServerUUID() {
        return this.stringWithLegacy("runtime.metrics.server-uuid", "metrics.server-uuid", "");
    }

    public boolean isLogStartupEnabled() {
        return this.booleanWithLegacy("runtime.metrics.log-startup", "metrics.log-startup", true);
    }

    public boolean isErrorReportingEnabled() {
        return this.booleanWithLegacy("runtime.metrics.error-reporting", "metrics.error-reporting", true);
    }

    public boolean isUpdateCheckEnabled() {
        return this.booleanWithLegacy("runtime.updates.enabled", "updates.enabled", false);
    }

    public boolean isErrorLogEnabled() {
        return this.booleanWithLegacy("runtime.logging.error-log.enabled", "logging.error-log.enabled", true);
    }

    public boolean shouldErrorLogIncludeWarnings() {
        return this.booleanWithLegacy("runtime.logging.error-log.include-warnings", "logging.error-log.include-warnings", true);
    }

    public int getErrorLogMaxSizeKb() {
        return Math.max(64, Math.min(16_384, this.intWithLegacy("runtime.logging.error-log.max-size-kb", "logging.error-log.max-size-kb", 1024)));
    }

    public int getErrorLogMaxArchives() {
        return Math.max(1, Math.min(10, this.intWithLegacy("runtime.logging.error-log.max-archives", "logging.error-log.max-archives", 3)));
    }

    public boolean isDebugLogEnabled() {
        return this.config.getBoolean("runtime.logging.debug-log.enabled", false);
    }

    public int getDebugLogMaxSizeKb() {
        return Math.max(64, Math.min(16_384, this.config.getInt("runtime.logging.debug-log.max-size-kb", 1024)));
    }

    public int getDebugLogMaxArchives() {
        return Math.max(1, Math.min(10, this.config.getInt("runtime.logging.debug-log.max-archives", 3)));
    }

    public int getMaxPlayerCommandsPerMinute() {
        return Math.max(1, this.config.getInt("security.commands.max-per-player-per-minute", 12));
    }

    public int getMaxConsoleCommandsPerMinute() {
        return Math.max(1, this.config.getInt("security.commands.max-console-per-minute", 30));
    }

    public int getMinManualSpawnIntervalSeconds() {
        return Math.max(0, this.config.getInt("security.spawn.min-manual-spawn-interval-seconds", 5));
    }

    public boolean shouldPreventDuplicateAliveBoss() {
        return this.config.getBoolean("security.spawn.prevent-duplicate-alive-boss", true);
    }

    public int getMaxActiveBossDamageSessions() {
        return Math.max(1, Math.min(2048, this.config.getInt("security.damage.max-active-boss-sessions", 256)));
    }

    public int getMaxDamagePlayersPerBoss() {
        return Math.max(1, Math.min(2048, this.config.getInt("security.damage.max-players-per-boss", 256)));
    }

    public int getMaxRewardCommandsPerRank() {
        return Math.max(1, this.config.getInt("security.rewards.max-commands-per-rank", 8));
    }

    public int getMaxRewardRanks() {
        return Math.max(1, this.config.getInt("security.rewards.max-rank-rewards", 100));
    }

    public int getMaxPermissionRewardsPerMob() {
        return Math.max(0, Math.min(64, this.config.getInt("security.rewards.max-permission-rewards-per-mob", 16)));
    }

    public int getMaxDropsPerReward() {
        return Math.max(0, this.config.getInt("security.rewards.max-drops-per-reward", 8));
    }

    public int getMaxTotalDropsPerBoss() {
        return Math.max(0, this.config.getInt("security.rewards.max-total-drops-per-boss", 96));
    }

    public int getMaxDropStackAmount() {
        return Math.max(1, Math.min(64, this.config.getInt("security.rewards.max-drop-stack-amount", 64)));
    }

    public int getMaxRewardItemIdLength() {
        return Math.max(8, Math.min(128, this.config.getInt("security.rewards.max-item-id-length", 96)));
    }

    public int getMaxBedrockProxyHitsPerSecond() {
        return Math.max(1, Math.min(40, this.intWithLegacy(
                "integrations.bedrock-visuals.limits.max-proxy-hits-per-player-per-second",
                "security.bedrock-visuals.max-proxy-hits-per-player-per-second",
                8)));
    }

    public double getMaxForwardedBedrockProxyDamage() {
        double value = this.doubleWithLegacy(
                "integrations.bedrock-visuals.limits.max-forwarded-damage",
                "security.bedrock-visuals.max-forwarded-damage",
                1000.0);
        if (!Double.isFinite(value)) {
            return 1000.0;
        }
        return Math.max(1.0, Math.min(100_000.0, value));
    }

    public boolean isPrivateDropDefault() {
        return this.config.getBoolean("security.rewards.default-private-drops", true);
    }

    public int getDefaultPickupDelayTicks() {
        return Math.max(0, Math.min(200, this.config.getInt("security.rewards.default-pickup-delay-ticks", 20)));
    }

    public int getMaxRewardCommandLength() {
        return Math.max(40, this.config.getInt("security.rewards.max-command-length", 180));
    }

    public List<String> getAllowedRewardCommandPrefixes() {
        return this.config.getStringList("security.rewards.allowed-command-prefixes");
    }

    public List<String> getBlockedRewardCommandFragments() {
        return this.config.getStringList("security.rewards.blocked-command-fragments");
    }

    public boolean isBedrockVisualsEnabled() {
        return this.booleanWithLegacy("integrations.bedrock-visuals.enabled", "bedrock-visuals.enabled", true);
    }

    public boolean isBedrockVisualEnabled(String mobType) {
        return this.isBedrockVisualsEnabled();
    }

    public EntityType getBedrockVisualEntityType(String mobType) {
        String fallback = this.config.getString("integrations.bedrock-visuals.defaults.vanilla-entity", "ZOMBIE");
        String configured = this.bossesConfig.getString("tracked-mobs." + mobType + ".bedrock-visual.vanilla-entity", fallback);
        try {
            return EntityType.valueOf(configured == null ? "ZOMBIE" : configured.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String getBedrockVisualModelMode(String mobType) {
        Object value = this.config.get("integrations.bedrock-visuals.defaults.modeled");
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        return value instanceof String string ? string.trim().toLowerCase() : "auto";
    }

    public boolean isBedrockVisualModelForced(String mobType) {
        String mode = this.getBedrockVisualModelMode(mobType);
        return "true".equals(mode) || "force".equals(mode) || "forced".equals(mode);
    }

    public boolean shouldOnlyUseBedrockVisualWhenModeled(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.only-when-modeled", true);
    }

    public boolean shouldFallbackBedrockVisualWhenModelUndetected(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.fallback-when-model-undetected", true);
    }

    public int getBedrockVisualSpawnDelayTicks(String mobType) {
        return Math.max(1, Math.min(200, this.config.getInt("integrations.bedrock-visuals.defaults.spawn-delay-ticks", 10)));
    }

    public int getBedrockVisualSyncIntervalTicks(String mobType) {
        return Math.max(1, Math.min(40, this.config.getInt("integrations.bedrock-visuals.defaults.sync-interval-ticks", 2)));
    }

    public double getBedrockVisualModelCheckRadius(String mobType) {
        return this.clampedBedrockRadius(this.config, "integrations.bedrock-visuals.defaults.model-check-radius", 4.0);
    }

    public boolean shouldHideNearbyModelParts(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.hide-nearby-model-parts", true);
    }

    public double getModelPartHideRadius(String mobType) {
        return this.clampedBedrockRadius(this.config, "integrations.bedrock-visuals.defaults.model-part-hide-radius", 4.0);
    }

    public boolean shouldForwardBedrockProxyDamage(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.forward-proxy-damage", true);
    }

    public boolean isBedrockVisualSilent(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.silent", true);
    }

    public boolean isBedrockVisualNameVisible(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.name-visible", true);
    }

    public boolean isBedrockVisualEquipmentEnabled(String mobType) {
        return this.config.getBoolean("integrations.bedrock-visuals.defaults.equipment.enabled", true);
    }

    public int getBedrockVisualVisibilityRefreshIntervalTicks() {
        return Math.max(5, Math.min(200, this.intWithLegacy(
                "integrations.bedrock-visuals.limits.visibility-refresh-interval-ticks",
                "security.bedrock-visuals.visibility-refresh-interval-ticks",
                20)));
    }

    public double getBedrockVisualVisibilityRefreshRadius() {
        double value = this.doubleWithLegacy(
                "integrations.bedrock-visuals.limits.visibility-refresh-radius",
                "security.bedrock-visuals.visibility-refresh-radius",
                128.0);
        if (!Double.isFinite(value)) {
            value = 128.0;
        }
        return Math.max(16.0, Math.min(512.0, value));
    }

    public int getMaxBedrockVisualViewersPerRefresh() {
        return Math.max(1, Math.min(500, this.intWithLegacy(
                "integrations.bedrock-visuals.limits.max-viewers-per-refresh",
                "security.bedrock-visuals.max-viewers-per-refresh",
                160)));
    }

    public int getBedrockVisualModelDetectionRetries() {
        return Math.max(0, Math.min(40, this.intWithLegacy(
                "integrations.bedrock-visuals.limits.model-detection-retries",
                "security.bedrock-visuals.model-detection-retries",
                8)));
    }

    public int getBedrockVisualModelDetectionRetryIntervalTicks() {
        return Math.max(1, Math.min(100, this.intWithLegacy(
                "integrations.bedrock-visuals.limits.model-detection-retry-interval-ticks",
                "security.bedrock-visuals.model-detection-retry-interval-ticks",
                10)));
    }

    public EquipmentItem getBedrockVisualEquipmentItem(String mobType, String slot) {
        String path = "tracked-mobs." + mobType + ".bedrock-visual.equipment." + slot;
        if (this.bossesConfig.isString(path)) {
            String item = this.bossesConfig.getString(path, "AIR");
            return new EquipmentItem("VANILLA", item == null ? "AIR" : item, 1);
        }
        ConfigurationSection section = this.bossesConfig.getConfigurationSection(path);
        if (section == null) {
            return new EquipmentItem("VANILLA", "AIR", 1);
        }
        String provider = section.getString("provider", "VANILLA");
        String item = section.getString("item", "AIR");
        int amount = Math.max(1, Math.min(this.getMaxDropStackAmount(), section.getInt("amount", 1)));
        return new EquipmentItem(provider == null ? "VANILLA" : provider, item == null ? "AIR" : item, amount);
    }

    private RewardBundle readRewardBundle(String path) {
        FileConfiguration source = this.sourceForPath(path);
        if (source.isList(path)) {
            return new RewardBundle(source.getStringList(path), Collections.emptyList(), 0.0, 0.0);
        }
        List<String> commands = source.getStringList(path + ".commands");
        List<RewardDrop> drops = this.readRewardDrops(path + ".drops");
        double minDamage = Math.max(0.0, source.getDouble(path + ".min-damage", 0.0));
        double minPercentage = Math.max(0.0, Math.min(100.0, source.getDouble(path + ".min-percentage", 0.0)));
        return new RewardBundle(commands, drops, minDamage, minPercentage);
    }

    private List<RewardDrop> readRewardDrops(String path) {
        FileConfiguration source = this.sourceForPath(path);
        if (!source.isList(path)) {
            return Collections.emptyList();
        }
        List<Map<?, ?>> rawDrops = source.getMapList(path);
        if (rawDrops.isEmpty()) {
            return Collections.emptyList();
        }
        int maxDrops = this.getMaxDropsPerReward();
        if (maxDrops <= 0) {
            return Collections.emptyList();
        }
        List<RewardDrop> drops = new ArrayList<>();
        for (Map<?, ?> rawDrop : rawDrops) {
            if (drops.size() >= maxDrops) {
                break;
            }
            String provider = stringValue(rawDrop.get("provider"), "VANILLA").toUpperCase();
            String item = stringValue(rawDrop.get("item"), "");
            if (!this.plugin.getSecurityGuard().isSafeRewardItem(provider, item)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                        "logs.reward-drop-invalid",
                        LanguageManager.placeholders("provider", provider, "item", item)));
                continue;
            }
            int amount = clampInt(rawDrop.get("amount"), 1, 1, this.getMaxDropStackAmount());
            double chance = clampDouble(rawDrop.get("chance"), 1.0, 0.0, 1.0);
            double amountPerPercent = clampDouble(rawDrop.get("amount-per-percent"), 0.0, 0.0, this.getMaxDropStackAmount());
            int maxAmount = clampInt(rawDrop.get("max-amount"), this.getMaxDropStackAmount(), 1, this.getMaxDropStackAmount());
            boolean privateDrop = booleanValue(rawDrop.get("private"), this.isPrivateDropDefault());
            boolean dropAtBoss = booleanValue(rawDrop.get("drop-at-boss"), true);
            int pickupDelay = clampInt(rawDrop.get("pickup-delay-ticks"), this.getDefaultPickupDelayTicks(), 0, 200);
            boolean glow = booleanValue(rawDrop.get("glow"), false);
            drops.add(new RewardDrop(provider, item, amount, chance, amountPerPercent, maxAmount, privateDrop, dropAtBoss, pickupDelay, glow));
        }
        return drops;
    }

    private double clampedBedrockRadius(FileConfiguration source, String path, double fallback) {
        double value = source.getDouble(path, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return Math.max(0.0, Math.min(16.0, value));
    }

    public int getMaxWebhookContentLength() {
        return Math.max(1, this.intWithLegacy("integrations.webhooks.limits.max-content-length", "security.webhooks.max-content-length", 512));
    }

    public int getMaxWebhookFieldLength() {
        return Math.max(1, this.intWithLegacy("integrations.webhooks.limits.max-field-length", "security.webhooks.max-field-length", 1024));
    }

    public int getWebhookConnectTimeoutMs() {
        return Math.max(1000, this.intWithLegacy("integrations.webhooks.limits.connect-timeout-ms", "security.webhooks.connect-timeout-ms", 4000));
    }

    public int getWebhookReadTimeoutMs() {
        return Math.max(1000, this.intWithLegacy("integrations.webhooks.limits.read-timeout-ms", "security.webhooks.read-timeout-ms", 4000));
    }

    public boolean isWebhookEnabled() {
        return this.booleanWithLegacy("integrations.webhooks.enabled", "discord.webhook.enabled", false);
    }

    public String getWebhookUrl() {
        return this.stringWithLegacy("integrations.webhooks.url", "discord.webhook.url", "");
    }

    public String getWebhookFormat(String event) {
        return this.stringWithLegacy(
                "integrations.webhooks." + event + "-format",
                "discord.webhook." + event + "-format",
                event.equals("spawn") ? "{mobname} has spawned at {location}" : "{mobname} has been defeated!");
    }

    public String getWebhookMentions(String event) {
        return this.stringWithLegacy("integrations.webhooks." + event + "-mentions", "discord.webhook." + event + "-mentions", "");
    }

    public Map<String, Object> getWebhookConfig(String mobType, String event) {
        ConfigurationSection section = this.getWebhookSection(mobType, event);
        if (section == null) {
            return Collections.emptyMap();
        }
        return section.getValues(true);
    }

    public ConfigurationSection getWebhookSection(String mobType, String event) {
        ConfigurationSection section = this.config.getConfigurationSection("integrations.webhooks.mobs." + mobType + "." + event);
        return section != null ? section : this.config.getConfigurationSection("webhooks." + mobType + "." + event);
    }

    public String getHologramProvider() {
        return this.stringWithLegacy("integrations.holograms.provider", "hologram.provider", "NONE");
    }

    public boolean isMobMovementRestricted() {
        return this.config.getBoolean("mob-settings.movement-restriction.enabled", true);
    }

    public double getMaxMobMovementDistance() {
        return this.config.getDouble("mob-settings.movement-restriction.max-distance", 7.0);
    }

    public int getMovementCheckInterval() {
        return this.config.getInt("mob-settings.movement-restriction.check-interval", 2);
    }

    public boolean shouldTeleportBack() {
        return this.config.getBoolean("mob-settings.movement-restriction.teleport-back", true);
    }

    public boolean isHealthAnnouncementsEnabled() {
        return this.config.getBoolean("boss-health.announcements.enabled", true);
    }

    public String getHealthAnnouncementFormat() {
        return this.plugin.getLanguageManager().raw("boss-health.announcement");
    }

    public List<Integer> getHealthThresholds() {
        return this.config.getIntegerList("boss-health.announcements.thresholds");
    }

    public boolean isScheduledSpawnEnabled(String mobType) {
        return this.bossesConfig.getBoolean("tracked-mobs." + mobType + ".spawn-time.enable", false);
    }

    public List<String> getScheduledSpawnTimes(String mobType) {
        return this.bossesConfig.getStringList("tracked-mobs." + mobType + ".spawn-time.time");
    }

    public FileConfiguration getConfig() {
        return this.config;
    }

    public FileConfiguration getBossesConfig() {
        return this.bossesConfig;
    }

    private FileConfiguration sourceForPath(String path) {
        return path != null && path.startsWith("tracked-mobs.") ? this.bossesConfig : this.config;
    }

    private boolean migrateLegacyConfig(YamlConfiguration config) {
        boolean changed = false;
        changed |= this.copyIfPresent(config, "metrics.enabled", "runtime.metrics.enabled");
        changed |= this.copyIfPresent(config, "metrics.log-startup", "runtime.metrics.log-startup");
        changed |= this.copyIfPresent(config, "metrics.error-reporting", "runtime.metrics.error-reporting");
        changed |= this.copyIfPresent(config, "metrics.server-uuid", "runtime.metrics.server-uuid");
        changed |= this.copyIfPresent(config, "updates.enabled", "runtime.updates.enabled");
        changed |= this.copyIfPresent(config, "logging.error-log", "runtime.logging.error-log");
        changed |= this.copyIfPresent(config, "hologram.provider", "integrations.holograms.provider");
        changed |= this.copyIfPresent(config, "bedrock-visuals.enabled", "integrations.bedrock-visuals.enabled");
        if (config.contains("integrations.bedrock-visuals.enabled")
                && !config.contains("integrations.bedrock-visuals.defaults")
                && !config.getBoolean("integrations.bedrock-visuals.enabled", true)) {
            config.set("integrations.bedrock-visuals.enabled", true);
            changed = true;
        }
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.max-proxy-hits-per-player-per-second", "integrations.bedrock-visuals.limits.max-proxy-hits-per-player-per-second");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.max-forwarded-damage", "integrations.bedrock-visuals.limits.max-forwarded-damage");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.visibility-refresh-interval-ticks", "integrations.bedrock-visuals.limits.visibility-refresh-interval-ticks");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.visibility-refresh-radius", "integrations.bedrock-visuals.limits.visibility-refresh-radius");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.max-viewers-per-refresh", "integrations.bedrock-visuals.limits.max-viewers-per-refresh");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.model-detection-retries", "integrations.bedrock-visuals.limits.model-detection-retries");
        changed |= this.copyIfPresent(config, "security.bedrock-visuals.model-detection-retry-interval-ticks", "integrations.bedrock-visuals.limits.model-detection-retry-interval-ticks");
        changed |= this.copyIfPresent(config, "security.webhooks.max-content-length", "integrations.webhooks.limits.max-content-length");
        changed |= this.copyIfPresent(config, "security.webhooks.max-field-length", "integrations.webhooks.limits.max-field-length");
        changed |= this.copyIfPresent(config, "security.webhooks.connect-timeout-ms", "integrations.webhooks.limits.connect-timeout-ms");
        changed |= this.copyIfPresent(config, "security.webhooks.read-timeout-ms", "integrations.webhooks.limits.read-timeout-ms");
        changed |= this.copyIfPresent(config, "discord.webhook.enabled", "integrations.webhooks.enabled");
        changed |= this.copyIfPresent(config, "discord.webhook.url", "integrations.webhooks.url");
        changed |= this.copyIfPresent(config, "discord.webhook.spawn-format", "integrations.webhooks.spawn-format");
        changed |= this.copyIfPresent(config, "discord.webhook.death-format", "integrations.webhooks.death-format");
        changed |= this.copyIfPresent(config, "discord.webhook.spawn-mentions", "integrations.webhooks.spawn-mentions");
        changed |= this.copyIfPresent(config, "discord.webhook.death-mentions", "integrations.webhooks.death-mentions");
        changed |= this.copyIfPresent(config, "webhooks", "integrations.webhooks.mobs");
        return changed;
    }

    private boolean copyIfPresent(YamlConfiguration config, String legacyPath, String newPath) {
        if (!config.contains(legacyPath) || config.contains(newPath)) {
            return false;
        }
        Object value = config.get(legacyPath);
        if (value instanceof ConfigurationSection section) {
            config.createSection(newPath, this.sectionToMap(section));
        } else {
            config.set(newPath, value);
        }
        return true;
    }

    private Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> values = new HashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                values.put(key, this.sectionToMap(nested));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    private boolean isAllowedConfigPath(String path, YamlConfiguration defaults) {
        if (defaults.contains(path)) {
            return true;
        }
        return matchesBossConditionSchema(path)
                || matchesDamagePerMobSchema(path)
                || matchesIntegrationWebhookSchema(path);
    }

    private boolean isAllowedBossesPath(String path, YamlConfiguration defaults) {
        if (defaults.contains(path)) {
            return true;
        }
        return matchesTrackedMobSchema(path);
    }

    private boolean matchesTrackedMobSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2 || !"tracked-mobs".equals(parts[0])) {
            return false;
        }
        String relative = parts.length == 2 ? "" : String.join(".", List.of(parts).subList(2, parts.length));
        if (relative.isEmpty()) {
            return true;
        }
        if (Set.of(
                "respawn", "respawn.enabled", "respawn.time",
                "timeout-seconds",
                "spawn-time", "spawn-time.enable", "spawn-time.time",
                "rewards", "participation-reward", "participation-reward.enabled", "participation-reward.min-damage", "participation-reward.commands",
                "lasthit-reward", "lasthit-reward.enabled", "lasthit-reward.min-damage", "lasthit-reward.commands",
                "permission-rewards", "permission-rewards.enabled", "permission-rewards.stop-after-first-match", "permission-rewards.rewards",
                "bedrock-visual", "bedrock-visual.vanilla-entity", "bedrock-visual.equipment",
                "bedrock-visual.equipment.main-hand", "bedrock-visual.equipment.off-hand", "bedrock-visual.equipment.helmet",
                "bedrock-visual.equipment.chestplate", "bedrock-visual.equipment.leggings", "bedrock-visual.equipment.boots"
        ).contains(relative) || relative.matches("rewards\\.top-[1-9][0-9]*")) {
            return true;
        }
        return relative.matches("rewards\\.top-[1-9][0-9]*\\.(commands|drops|min-damage|min-percentage)")
                || relative.matches("participation-reward\\.(drops|min-percentage)")
                || relative.matches("lasthit-reward\\.(drops|min-percentage)")
                || relative.matches("permission-rewards\\.rewards\\.[A-Za-z0-9_-]{1,48}")
                || relative.matches("permission-rewards\\.rewards\\.[A-Za-z0-9_-]{1,48}\\.(permission|commands|drops|min-damage|min-percentage)")
                || relative.matches("bedrock-visual\\.equipment\\.(main-hand|off-hand|helmet|chestplate|leggings|boots)\\.(provider|item|amount)");
    }

    private boolean isLegacyRewardListPath(String path, YamlConfiguration diskConfig) {
        String[] parts = path.split("\\.");
        if (parts.length < 4 || !"tracked-mobs".equals(parts[0]) || !"rewards".equals(parts[2]) || !parts[3].matches("top-[1-9][0-9]*")) {
            return false;
        }
        String rankPath = String.join(".", List.of(parts).subList(0, 4));
        return diskConfig.isList(rankPath);
    }

    private boolean matchesBossConditionSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 3 || !"boss-spawn-conditions".equals(parts[0]) || !"mobs".equals(parts[1])) {
            return false;
        }
        String relative = parts.length == 3 ? "" : String.join(".", List.of(parts).subList(3, parts.length));
        return relative.isEmpty()
                || Set.of("required-kills", "required-kills.enabled", "required-kills.vanilla-mobs", "required-kills.mythic-mobs", "normal-respawn-time").contains(relative)
                || relative.startsWith("required-kills.vanilla-mobs.")
                || relative.startsWith("required-kills.mythic-mobs.");
    }

    private boolean matchesDamagePerMobSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 5
                || !"damage-settings".equals(parts[0])
                || !"threshold".equals(parts[1])
                || !"per-mob".equals(parts[2])) {
            return false;
        }
        String relative = parts.length == 4 ? "" : String.join(".", List.of(parts).subList(4, parts.length));
        return relative.isEmpty() || "minimum-damage".equals(relative);
    }

    private boolean matchesIntegrationWebhookSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 5
                || !"integrations".equals(parts[0])
                || !"webhooks".equals(parts[1])
                || !"mobs".equals(parts[2])) {
            return false;
        }
        if (!Set.of("spawn", "death").contains(parts[4])) {
            return false;
        }
        String relative = parts.length == 5 ? "" : String.join(".", List.of(parts).subList(5, parts.length));
        return relative.isEmpty()
                || Set.of("enabled", "url", "avatar-url", "embed-thumbnail").contains(relative);
    }

    private boolean booleanWithLegacy(String path, String legacyPath, boolean fallback) {
        return this.config.contains(path) ? this.config.getBoolean(path, fallback) : this.config.getBoolean(legacyPath, fallback);
    }

    private int intWithLegacy(String path, String legacyPath, int fallback) {
        return this.config.contains(path) ? this.config.getInt(path, fallback) : this.config.getInt(legacyPath, fallback);
    }

    private double doubleWithLegacy(String path, String legacyPath, double fallback) {
        return this.config.contains(path) ? this.config.getDouble(path, fallback) : this.config.getDouble(legacyPath, fallback);
    }

    private String stringWithLegacy(String path, String legacyPath, String fallback) {
        return this.config.contains(path) ? this.config.getString(path, fallback) : this.config.getString(legacyPath, fallback);
    }

    private void backup(File file, String prefix) throws IOException {
        if (!file.exists()) {
            return;
        }
        File backupFolder = new File(this.plugin.getDataFolder(), "file-backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            throw new IOException("Could not create backup folder.");
        }
        String stamp = BACKUP_TIME.format(LocalDateTime.now());
        Files.copy(file.toPath(), new File(backupFolder, prefix + "-" + stamp + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveSafely(YamlConfiguration configuration, File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        configuration.save(temp);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int depth(String path) {
        return path.isEmpty() ? 0 : path.split("\\.").length;
    }

    private static boolean sameKind(Object value, Object expected) {
        if (value == null || expected == null) {
            return value == expected;
        }
        if (expected instanceof List<?>) {
            return value instanceof List<?>;
        }
        if (expected instanceof Number) {
            return value instanceof Number;
        }
        if (expected instanceof Boolean) {
            return value instanceof Boolean;
        }
        return value instanceof String;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String string ? string.trim() : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static int clampInt(Object value, int fallback, int min, int max) {
        int result = value instanceof Number number ? number.intValue() : fallback;
        return Math.max(min, Math.min(max, result));
    }

    private static double clampDouble(Object value, double fallback, double min, double max) {
        double result = value instanceof Number number ? number.doubleValue() : fallback;
        if (!Double.isFinite(result)) {
            result = fallback;
        }
        return Math.max(min, Math.min(max, result));
    }

    private static boolean isSafePermissionRewardId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,48}");
    }

    private static boolean isSafePermissionNode(String value) {
        return value != null
                && value.length() >= 3
                && value.length() <= 128
                && value.matches("[A-Za-z0-9_.-]+")
                && !value.startsWith(".")
                && !value.endsWith(".")
                && !value.contains("..");
    }

    private void logInfo(String path) {
        if (this.plugin.getLanguageManager() == null) {
            this.plugin.getLogger().info(path);
            return;
        }
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw(path));
    }

    private void logWarning(String path, Map<String, String> placeholders) {
        if (this.plugin.getLanguageManager() == null) {
            this.plugin.getLogger().warning(path);
            return;
        }
        this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(path, placeholders));
    }

    public record EquipmentItem(String provider, String item, int amount) {
    }
}





