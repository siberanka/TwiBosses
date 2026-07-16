package com.siberanka.twibosses;

import com.siberanka.twibosses.commands.CommandManager;
import com.siberanka.twibosses.listeners.DamageTracker;
import com.siberanka.twibosses.listeners.KillTracker;
import com.siberanka.twibosses.manager.ConfigManager;
import com.siberanka.twibosses.manager.BedrockVisualManager;
import com.siberanka.twibosses.manager.DebugLogManager;
import com.siberanka.twibosses.manager.DisplayManager;
import com.siberanka.twibosses.manager.ErrorLogManager;
import com.siberanka.twibosses.manager.HologramManager;
import com.siberanka.twibosses.manager.LanguageManager;
import com.siberanka.twibosses.manager.RewardManager;
import com.siberanka.twibosses.manager.SpawnManager;
import com.siberanka.twibosses.manager.WebhookManager;
import com.siberanka.twibosses.placeholders.MobPlaceholders;
import com.siberanka.twibosses.security.SecurityGuard;
import com.siberanka.twibosses.utils.Banner;
import com.siberanka.twibosses.utils.UpdateChecker;
import com.siberanka.twibosses.utils.VersionSupport;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class TwiBosses
extends JavaPlugin {
    private DamageTracker damageTracker;
    private ConfigManager configManager;
    private CommandManager commandManager;
    private DisplayManager displayManager;
    private RewardManager rewardManager;
    private SpawnManager spawnManager;
    private WebhookManager webhookManager;
    private HologramManager hologramManager;
    private KillTracker killTracker;
    private SecurityGuard securityGuard;
    private LanguageManager languageManager;
    private ErrorLogManager errorLogManager;
    private DebugLogManager debugLogManager;
    private BedrockVisualManager bedrockVisualManager;

    public void onEnable() {
        this.errorLogManager = new ErrorLogManager(this);
        this.errorLogManager.install();
        try {
            this.enablePlugin();
        } catch (Throwable throwable) {
            String message = this.rawLogMessage("logs.plugin-enable-failed");
            this.getLogger().severe(message);
            this.logError(message, throwable);
            Bukkit.getPluginManager().disablePlugin((Plugin)this);
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(throwable);
        }
    }

    private void enablePlugin() {
        this.configManager = new ConfigManager(this);
        this.languageManager = new LanguageManager(this);
        this.errorLogManager.reload();
        this.debugLogManager = new DebugLogManager(this);
        this.debugLogManager.reload();
        if (!VersionSupport.isSupported()) {
            this.getLogger().severe(this.languageManager.raw("logs.unsupported-version"));
            this.getLogger().severe(this.languageManager.raw("logs.current-version", LanguageManager.placeholders("version", VersionSupport.getFormattedVersion())));
            this.getLogger().severe(this.languageManager.raw("logs.supported-versions"));
            this.getLogger().severe(this.languageManager.raw("logs.compatibility-information"));
            this.getLogger().severe(VersionSupport.getCompatibilityStatus());
            Bukkit.getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        Banner.display(this, this.getDescription());
        this.getLogger().info(this.languageManager.raw("logs.server-information"));
        this.getLogger().info(VersionSupport.getCompatibilityStatus());
        this.securityGuard = new SecurityGuard(this);
        if (this.configManager.isMetricsEnabled()) {
            int pluginId = 26207;
            Metrics metrics = new Metrics(this, pluginId);
            if (this.configManager.isLogStartupEnabled()) {
                this.getLogger().info(this.languageManager.raw("logs.metrics-enabled"));
            }
        }
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            this.getLogger().warning(this.languageManager.raw("logs.mythicmobs-missing"));
            Bukkit.getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.rewardManager = new RewardManager(this);
        this.bedrockVisualManager = new BedrockVisualManager(this);
        this.damageTracker = new DamageTracker(this);
        this.displayManager = new DisplayManager(this);
        this.commandManager = new CommandManager(this);
        this.spawnManager = new SpawnManager(this);
        this.webhookManager = new WebhookManager(this);
        this.hologramManager = new HologramManager(this);
        this.killTracker = new KillTracker(this);
        if (this.configManager.isUpdateCheckEnabled()) {
            new UpdateChecker(this);
        }
        this.getServer().getScheduler().runTaskLater((Plugin)this, () -> this.spawnManager.spawnAllMobs(), 100L);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MobPlaceholders(this).register();
            this.getLogger().info(this.languageManager.raw("logs.placeholderapi-enabled"));
        }
        this.getServer().getPluginManager().registerEvents((Listener)this.damageTracker, (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)this.killTracker, (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)this.bedrockVisualManager, (Plugin)this);
        this.getLogger().info(this.languageManager.raw("logs.plugin-enabled"));
    }

    public void onDisable() {
        if (this.damageTracker != null) {
            this.damageTracker.clearData();
        }
        if (this.spawnManager != null) {
            this.spawnManager.cleanup();
        }
        if (this.displayManager != null) {
            this.displayManager.cleanup();
        }
        if (this.hologramManager != null) {
            this.hologramManager.cleanup();
        }
        if (this.bedrockVisualManager != null) {
            this.bedrockVisualManager.cleanup();
        }
        if (this.securityGuard != null) {
            this.securityGuard.clear();
        }
        if (this.languageManager != null) {
            Banner.disable(this, this.getDescription());
        }
        if (this.debugLogManager != null) {
            this.debugLogManager.shutdown();
        }
        if (this.errorLogManager != null) {
            this.errorLogManager.uninstall();
        }
    }

    public void reloadPluginConfiguration() {
        this.configManager.reloadConfig();
        this.languageManager.reload();
        if (this.errorLogManager != null) {
            this.errorLogManager.reload();
        }
        if (this.debugLogManager != null) {
            this.debugLogManager.reload();
        }
        if (this.displayManager != null) {
            this.displayManager.reload();
        }
        if (this.hologramManager != null) {
            this.hologramManager.reload();
        }
        if (this.bedrockVisualManager != null) {
            this.bedrockVisualManager.reload();
        }
    }

    public DamageTracker getDamageTracker() {
        return this.damageTracker;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public DisplayManager getDisplayManager() {
        return this.displayManager;
    }

    public RewardManager getRewardManager() {
        return this.rewardManager;
    }

    public SpawnManager getSpawnManager() {
        return this.spawnManager;
    }

    public WebhookManager getWebhookManager() {
        return this.webhookManager;
    }

    public HologramManager getHologramManager() {
        return this.hologramManager;
    }

    public BedrockVisualManager getBedrockVisualManager() {
        return this.bedrockVisualManager;
    }

    public KillTracker getKillTracker() {
        return this.killTracker;
    }

    public SecurityGuard getSecurityGuard() {
        return this.securityGuard;
    }

    public LanguageManager getLanguageManager() {
        return this.languageManager;
    }

    public ErrorLogManager getErrorLogManager() {
        return this.errorLogManager;
    }

    public DebugLogManager getDebugLogManager() {
        return this.debugLogManager;
    }

    public void logError(String message, Throwable throwable) {
        if (this.errorLogManager != null) {
            this.errorLogManager.log(message, throwable);
        }
    }

    public void debug(String category, String message) {
        if (this.debugLogManager != null) {
            this.debugLogManager.log(category, message);
        }
    }

    private String rawLogMessage(String path) {
        if (this.languageManager == null) {
            return path;
        }
        String message = this.languageManager.raw(path);
        return message.isBlank() ? path : message;
    }
}






