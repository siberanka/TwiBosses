package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
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

public class ConfigManager {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TwiBosses plugin;
    private FileConfiguration config;

    public ConfigManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.repairConfigFile();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
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
            boolean updated = false;
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
        }
    }

    public void reloadConfig() {
        this.repairConfigFile();
        this.plugin.reloadConfig();
        this.config = this.plugin.getConfig();
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
        ConfigurationSection section = this.config.getConfigurationSection("tracked-mobs");
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
        return this.config.getBoolean("tracked-mobs." + mobType + ".respawn.enabled", true);
    }

    public int getMobRespawnTime(String mobType) {
        if (!this.isMobRespawnEnabled(mobType)) {
            return -1;
        }
        return this.config.getInt("tracked-mobs." + mobType + ".respawn.time", 300);
    }

    public Map<Integer, List<String>> getAllMobRewards(String mobType) {
        HashMap<Integer, List<String>> rewards = new HashMap<Integer, List<String>>();
        String path = "tracked-mobs." + mobType + ".rewards";
        ConfigurationSection section = this.config.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!key.startsWith("top-")) continue;
                try {
                    int pos = Integer.parseInt(key.substring(4));
                    rewards.put(pos, section.getStringList(key));
                }
                catch (NumberFormatException numberFormatException) {}
            }
        }
        return rewards;
    }

    public boolean isParticipationRewardEnabled(String mobType) {
        return this.config.getBoolean("tracked-mobs." + mobType + ".participation-reward.enabled", false);
    }

    public double getParticipationMinDamage(String mobType) {
        return this.config.getDouble("tracked-mobs." + mobType + ".participation-reward.min-damage", 0.0);
    }

    public List<String> getParticipationCommands(String mobType) {
        return this.config.getStringList("tracked-mobs." + mobType + ".participation-reward.commands");
    }

    public boolean isLasthitRewardEnabled(String mobType) {
        return this.config.getBoolean("tracked-mobs." + mobType + ".lasthit-reward.enabled", false);
    }

    public double getLasthitMinDamage(String mobType) {
        return this.config.getDouble("tracked-mobs." + mobType + ".lasthit-reward.min-damage", 0.0);
    }

    public List<String> getLasthitCommands(String mobType) {
        return this.config.getStringList("tracked-mobs." + mobType + ".lasthit-reward.commands");
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
        return this.config.getBoolean("metrics.enabled", true);
    }

    public String getServerUUID() {
        return this.config.getString("metrics.server-uuid", "");
    }

    public boolean isLogStartupEnabled() {
        return this.config.getBoolean("metrics.log-startup", true);
    }

    public boolean isErrorReportingEnabled() {
        return this.config.getBoolean("metrics.error-reporting", true);
    }

    public boolean isUpdateCheckEnabled() {
        return this.config.getBoolean("updates.enabled", false);
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

    public int getMaxRewardCommandsPerRank() {
        return Math.max(1, this.config.getInt("security.rewards.max-commands-per-rank", 8));
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

    public int getMaxWebhookContentLength() {
        return Math.max(1, this.config.getInt("security.webhooks.max-content-length", 512));
    }

    public int getMaxWebhookFieldLength() {
        return Math.max(1, this.config.getInt("security.webhooks.max-field-length", 1024));
    }

    public int getWebhookConnectTimeoutMs() {
        return Math.max(1000, this.config.getInt("security.webhooks.connect-timeout-ms", 4000));
    }

    public int getWebhookReadTimeoutMs() {
        return Math.max(1000, this.config.getInt("security.webhooks.read-timeout-ms", 4000));
    }

    public boolean isWebhookEnabled() {
        return this.config.getBoolean("discord.webhook.enabled", false);
    }

    public String getWebhookUrl() {
        return this.config.getString("discord.webhook.url", "");
    }

    public String getWebhookFormat(String event) {
        return this.config.getString("discord.webhook." + event + "-format", event.equals("spawn") ? "{mobname} has spawned at {location}" : "{mobname} has been defeated!");
    }

    public String getWebhookMentions(String event) {
        return this.config.getString("discord.webhook." + event + "-mentions", "");
    }

    public Map<String, Object> getWebhookConfig(String mobType, String event) {
        ConfigurationSection section = this.getConfig().getConfigurationSection("webhook.mobs." + mobType + "." + event);
        if (section == null) {
            return Collections.emptyMap();
        }
        return section.getValues(true);
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
        return this.config.getBoolean("tracked-mobs." + mobType + ".spawn-time.enable", false);
    }

    public List<String> getScheduledSpawnTimes(String mobType) {
        return this.config.getStringList("tracked-mobs." + mobType + ".spawn-time.time");
    }

    public FileConfiguration getConfig() {
        return this.config;
    }

    private boolean isAllowedConfigPath(String path, YamlConfiguration defaults) {
        if (defaults.contains(path)) {
            return true;
        }
        return matchesTrackedMobSchema(path)
                || matchesBossConditionSchema(path)
                || matchesDamagePerMobSchema(path)
                || matchesWebhookSchema(path);
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
        return Set.of(
                "respawn", "respawn.enabled", "respawn.time",
                "spawn-time", "spawn-time.enable", "spawn-time.time",
                "rewards", "participation-reward", "participation-reward.enabled", "participation-reward.min-damage", "participation-reward.commands",
                "lasthit-reward", "lasthit-reward.enabled", "lasthit-reward.min-damage", "lasthit-reward.commands"
        ).contains(relative) || relative.matches("rewards\\.top-[1-9][0-9]*");
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

    private boolean matchesWebhookSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 3 || !"webhooks".equals(parts[0])) {
            return false;
        }
        if (!Set.of("spawn", "death").contains(parts[2])) {
            return false;
        }
        String relative = parts.length == 3 ? "" : String.join(".", List.of(parts).subList(3, parts.length));
        return relative.isEmpty()
                || Set.of("enabled", "url", "avatar-url", "embed-thumbnail").contains(relative);
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
}





