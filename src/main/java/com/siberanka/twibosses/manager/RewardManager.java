package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.utils.ColorUtils;
import com.siberanka.twibosses.utils.PlaceholderHook;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RewardManager {
    private final TwiBosses plugin;
    private final DecimalFormat damageFormat;
    private final DecimalFormat percentFormat;

    public RewardManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.damageFormat = new DecimalFormat(plugin.getConfigManager().getDamageFormat());
        this.percentFormat = new DecimalFormat(plugin.getConfigManager().getPercentageFormat());
    }

    public void handleMobDeath(String mobType, Map<UUID, Double> damageMap, Location deathLocation) {
        String killerName;
        if (damageMap.isEmpty()) {
            return;
        }
        ArrayList<Map.Entry<UUID, Double>> sortedDamage = new ArrayList<Map.Entry<UUID, Double>>(damageMap.entrySet());
        sortedDamage.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        double totalDamage = damageMap.values().stream().mapToDouble(Double::doubleValue).sum();
        String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        OfflinePlayer killer = Bukkit.getOfflinePlayer((UUID)((UUID)((Map.Entry)sortedDamage.get(0)).getKey()));
        String string = killerName = killer.getName() != null ? killer.getName() : this.plugin.getLanguageManager().raw("general.unknown");
        if (this.plugin.getConfigManager().isDeathSoundEnabled() && deathLocation != null) {
            try {
                Sound sound = Sound.valueOf((String)this.plugin.getConfigManager().getDeathSoundType());
                deathLocation.getWorld().playSound(deathLocation, sound, this.plugin.getConfigManager().getDeathSoundVolume(), this.plugin.getConfigManager().getDeathSoundPitch());
            }
            catch (IllegalArgumentException e) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-death-sound", LanguageManager.placeholders("sound", this.plugin.getConfigManager().getDeathSoundType())));
            }
        }
        if (this.plugin.getConfigManager().isDeathTitleEnabled()) {
            String title = this.plugin.getConfigManager().getDeathTitleFormat().replace("{mobname}", mobName);
            String subtitle = this.plugin.getConfigManager().getDeathSubtitleFormat().replace("{killer}", killerName);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendTitle(ColorUtils.colorize(title), ColorUtils.colorize(subtitle), this.plugin.getConfigManager().getDeathTitleFadeIn(), this.plugin.getConfigManager().getDeathTitleStay(), this.plugin.getConfigManager().getDeathTitleFadeOut());
            }
        }
        Map<Integer, List<String>> allRewards = this.plugin.getConfigManager().getAllMobRewards(mobType);
        int maxTop = allRewards.keySet().stream().max(Integer::compareTo).orElse(0);
        for (int i = 0; i < sortedDamage.size(); ++i) {
            List<String> list;
            String name;
            Map.Entry entry = (Map.Entry)sortedDamage.get(i);
            OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
            String string2 = name = player.getName() != null ? player.getName() : this.plugin.getLanguageManager().raw("general.unknown");
            if (!player.isOnline() || (list = allRewards.get(i + 1)) == null) continue;
            if (!this.plugin.getSecurityGuard().isSafePlayerName(name)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.skipped-unsafe-reward-player", LanguageManager.placeholders("player", name)));
                continue;
            }
            for (String command : this.limitCommands(list)) {
                this.dispatchRewardCommand(command, name);
            }
        }
        if (this.plugin.getConfigManager().isTopDamageBroadcastEnabled()) {
            Bukkit.broadcastMessage((String)ColorUtils.colorize(this.plugin.getConfigManager().getTopDamageHeader()));
            String killerMsg = this.plugin.getConfigManager().getTopDamageKillerFormat().replace("{mobname}", mobName).replace("{killer}", killerName);
            Bukkit.broadcastMessage((String)ColorUtils.colorize(killerMsg));
            Bukkit.broadcastMessage((String)ColorUtils.colorize(this.plugin.getConfigManager().getTopDamageListHeader()));
            int maxDisplay = this.plugin.getConfigManager().getMaxTopEntries();
            for (int i = 0; i < Math.min(sortedDamage.size(), maxDisplay); ++i) {
                Map.Entry entry = (Map.Entry)sortedDamage.get(i);
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
                String name = offlinePlayer.getName() != null ? offlinePlayer.getName() : this.plugin.getLanguageManager().raw("general.unknown");
                double damage = (Double)entry.getValue();
                double percentage = damage / totalDamage * 100.0;
                String message = this.plugin.getConfigManager().getTopDamagePlayerFormat().replace("{position}", String.valueOf(i + 1)).replace("{player}", name).replace("{damage}", this.damageFormat.format(damage)).replace("{percentage}", this.percentFormat.format(percentage));
                Bukkit.broadcastMessage((String)ColorUtils.colorize(message));
            }
            Bukkit.broadcastMessage((String)ColorUtils.colorize(this.plugin.getConfigManager().getTopDamageFooter()));
        }
        if (this.plugin.getConfigManager().isRewardBroadcastEnabled()) {
            boolean headerSent = false;
            for (int i = 0; i < sortedDamage.size(); ++i) {
                List<String> list;
                Map.Entry entry = (Map.Entry)sortedDamage.get(i);
                OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
                if (!player.isOnline() || (list = allRewards.get(i + 1)) == null) continue;
                if (!headerSent) {
                    String headerMsg = this.plugin.getConfigManager().getRewardsHeader().replace("{mobname}", mobName).replace("{killer}", killerName);
                    headerMsg = this.parsePlaceholders(player, headerMsg);
                    Bukkit.broadcastMessage((String)ColorUtils.colorize(headerMsg));
                    headerSent = true;
                }
                double damage = (Double)entry.getValue();
                double percentage = damage / totalDamage * 100.0;
                String rewardMsg = this.plugin.getConfigManager().getRewardFormat().replace("{player}", player.getName() != null ? player.getName() : this.plugin.getLanguageManager().raw("general.unknown")).replace("{position}", String.valueOf(i + 1)).replace("{damage}", this.damageFormat.format(damage)).replace("{percentage}", this.percentFormat.format(percentage)).replace("{mobname}", mobName).replace("{killer}", killerName);
                rewardMsg = this.parsePlaceholders(player, rewardMsg);
                Bukkit.broadcastMessage((String)ColorUtils.colorize(rewardMsg));
            }
        }
        if (this.plugin.getConfigManager().isWebhookEnabled()) {
            this.plugin.getWebhookManager().sendDeathWebhook(mobType, deathLocation, killerName);
        }
        if (this.plugin.getConfigManager().isParticipationRewardEnabled(mobType)) {
            double minParticipation = this.plugin.getConfigManager().getParticipationMinDamage(mobType);
            List<String> participationCommands = this.plugin.getConfigManager().getParticipationCommands(mobType);
            for (Map.Entry entry : damageMap.entrySet()) {
                Player player;
                UUID uuid = (UUID)entry.getKey();
                double damage = (Double)entry.getValue();
                boolean isTop = false;
                for (int i = 0; i < Math.min(sortedDamage.size(), maxTop); ++i) {
                    if (!((UUID)((Map.Entry)sortedDamage.get(i)).getKey()).equals(uuid)) continue;
                    isTop = true;
                    break;
                }
                if (isTop || !(damage >= minParticipation) || (player = Bukkit.getPlayer((UUID)uuid)) == null || !player.isOnline()) continue;
                if (!this.plugin.getSecurityGuard().isSafePlayerName(player.getName())) {
                    this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.skipped-unsafe-participation-player", LanguageManager.placeholders("player", player.getName())));
                    continue;
                }
                for (String command : this.limitCommands(participationCommands)) {
                    this.dispatchRewardCommand(command, player.getName());
                }
                player.sendMessage(this.plugin.getLanguageManager().get("rewards.participation-received"));
            }
        }
        List<String> deathMessages = this.plugin.getConfigManager().getDeathMessage(mobType);
        for (String line : deathMessages) {
            String formattedLine = line.replace("{mobname}", mobName).replace("{killer}", killer != null && killer.getName() != null ? killer.getName() : this.plugin.getLanguageManager().raw("general.unknown"));
            Bukkit.broadcastMessage((String)ColorUtils.colorize(formattedLine));
        }
    }

    private String parsePlaceholders(OfflinePlayer player, String text) {
        if (text == null) {
            return "";
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                return PlaceholderHook.setPlaceholders(player, text);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        return text;
    }

    private List<String> limitCommands(List<String> commands) {
        int maxCommands = this.plugin.getConfigManager().getMaxRewardCommandsPerRank();
        if (commands.size() <= maxCommands) {
            return commands;
        }
        this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.reward-command-limit"));
        return commands.subList(0, maxCommands);
    }

    private void dispatchRewardCommand(String command, String playerName) {
        if (!this.plugin.getSecurityGuard().isRewardCommandAllowed(command)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.blocked-reward-command", LanguageManager.placeholders("command", command)));
            return;
        }
        String finalCommand = command.trim().replace("{player}", playerName);
        if (finalCommand.startsWith("/")) {
            finalCommand = finalCommand.substring(1);
        }
        if (!this.plugin.getSecurityGuard().isRewardCommandAllowed(finalCommand)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.blocked-reward-command-after-placeholder", LanguageManager.placeholders("command", command)));
            return;
        }
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)finalCommand);
    }
}





