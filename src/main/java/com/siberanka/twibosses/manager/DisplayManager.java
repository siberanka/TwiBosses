package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.utils.ColorUtils;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class DisplayManager {
    private final TwiBosses plugin;
    private DecimalFormat damageFormat;
    private DecimalFormat percentageFormat;
    private BukkitTask actionBarTask;

    public DisplayManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.damageFormat = new DecimalFormat(plugin.getConfigManager().getDamageFormat());
        this.percentageFormat = new DecimalFormat(plugin.getConfigManager().getPercentageFormat());
        this.startActionBarTask();
    }

    private void startActionBarTask() {
        if (!this.plugin.getConfigManager().isActionBarEnabled()) {
            return;
        }
        this.actionBarTask = new BukkitRunnable(){

            public void run() {
                DisplayManager.this.updateActionBars();
            }
        }.runTaskTimer((Plugin)this.plugin, 0L, (long)Math.max(1, this.plugin.getConfigManager().getActionBarInterval()));
    }

    public void updateActionBar(Player player) {
        this.updateActionBars();
    }

    private void updateActionBars() {
        Map<String, Map<UUID, Double>> damageMap = this.plugin.getDamageTracker().getMobDamageMap();
        if (damageMap.isEmpty()) {
            return;
        }
        Map<UUID, Double> damageByPlayer = new HashMap<>();
        double overallTotalDamage = 0.0;
        for (Map.Entry<String, Map<UUID, Double>> mobEntry : damageMap.entrySet()) {
            Map<UUID, Double> mobDamage = mobEntry.getValue();
            for (Map.Entry<UUID, Double> damageEntry : mobDamage.entrySet()) {
                double damage = damageEntry.getValue() == null ? 0.0 : damageEntry.getValue();
                if (!Double.isFinite(damage) || damage <= 0.0) {
                    continue;
                }
                damageByPlayer.merge(damageEntry.getKey(), damage, Double::sum);
                overallTotalDamage += damage;
            }
        }
        if (damageByPlayer.isEmpty() || overallTotalDamage <= 0.0) {
            return;
        }
        String format = this.plugin.getConfigManager().getActionBarFormat();
        for (Player player : Bukkit.getOnlinePlayers()) {
            double playerDamage = damageByPlayer.getOrDefault(player.getUniqueId(), 0.0);
            if (playerDamage <= 0.0) {
                continue;
            }
            double percentage = overallTotalDamage > 0.0 ? playerDamage / overallTotalDamage * 100.0 : 0.0;
            String message = format.replace("{damage}", this.damageFormat.format(playerDamage)).replace("{percentage}", this.percentageFormat.format(percentage));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, (BaseComponent)new TextComponent(ColorUtils.colorize(message)));
        }
    }

    public void showMobSpawn(String mobType, Location location) {
        String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        String locationStr = String.format("%.0f, %.0f, %.0f", location.getX(), location.getY(), location.getZ());
        List<String> announcements = this.plugin.getConfigManager().getMobAnnouncement(mobType);
        for (String line : announcements) {
            String formattedLine = line.replace("{mobname}", mobName).replace("{location}", locationStr);
            Bukkit.broadcastMessage((String)ColorUtils.colorize(formattedLine));
        }
        if (this.plugin.getConfigManager().isSpawnTitleEnabled()) {
            String title = this.plugin.getConfigManager().getSpawnTitleFormat().replace("{mobname}", mobName);
            String subtitle = this.plugin.getConfigManager().getSpawnSubtitleFormat().replace("{location}", locationStr);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendTitle(ColorUtils.colorize(title), ColorUtils.colorize(subtitle), this.plugin.getConfigManager().getSpawnTitleFadeIn(), this.plugin.getConfigManager().getSpawnTitleStay(), this.plugin.getConfigManager().getSpawnTitleFadeOut());
            }
        }
        if (this.plugin.getConfigManager().isSpawnSoundEnabled()) {
            try {
                Sound sound = Sound.valueOf((String)this.plugin.getConfigManager().getSpawnSoundType());
                location.getWorld().playSound(location, sound, this.plugin.getConfigManager().getSpawnSoundVolume(), this.plugin.getConfigManager().getSpawnSoundPitch());
            }
            catch (IllegalArgumentException e) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-spawn-sound", LanguageManager.placeholders("sound", this.plugin.getConfigManager().getSpawnSoundType())));
                this.plugin.logError("logs.invalid-spawn-sound", e);
            }
        }
    }

    public void cleanup() {
        if (this.actionBarTask != null) {
            this.actionBarTask.cancel();
            this.actionBarTask = null;
        }
    }

    public void reload() {
        this.cleanup();
        this.damageFormat = new DecimalFormat(this.plugin.getConfigManager().getDamageFormat());
        this.percentageFormat = new DecimalFormat(this.plugin.getConfigManager().getPercentageFormat());
        this.startActionBarTask();
    }
}





