package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.rewards.ItemResolver;
import com.siberanka.twibosses.rewards.RewardBundle;
import com.siberanka.twibosses.rewards.RewardDrop;
import com.siberanka.twibosses.utils.ColorUtils;
import com.siberanka.twibosses.utils.PlaceholderHook;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class RewardManager {
    private final TwiBosses plugin;
    private final DecimalFormat damageFormat;
    private final DecimalFormat percentFormat;
    private final ItemResolver itemResolver;

    public RewardManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.damageFormat = new DecimalFormat(plugin.getConfigManager().getDamageFormat());
        this.percentFormat = new DecimalFormat(plugin.getConfigManager().getPercentageFormat());
        this.itemResolver = new ItemResolver(plugin);
    }

    public void handleMobDeath(String mobType, Map<UUID, Double> damageMap, Location deathLocation) {
        this.handleMobDeath(mobType, damageMap, deathLocation, null);
    }

    public void handleMobDeath(String mobType, Map<UUID, Double> damageMap, Location deathLocation, Player lasthitPlayer) {
        String killerName;
        if (damageMap.isEmpty()) {
            return;
        }
        ArrayList<Map.Entry<UUID, Double>> sortedDamage = new ArrayList<Map.Entry<UUID, Double>>(damageMap.entrySet());
        sortedDamage.removeIf(entry -> entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() <= 0.0);
        if (sortedDamage.isEmpty()) {
            return;
        }
        sortedDamage.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        double totalDamage = sortedDamage.stream().mapToDouble(Map.Entry::getValue).sum();
        if (!Double.isFinite(totalDamage) || totalDamage <= 0.0) {
            return;
        }
        String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        OfflinePlayer killer = Bukkit.getOfflinePlayer((UUID)((UUID)((Map.Entry)sortedDamage.get(0)).getKey()));
        String string = killerName = killer.getName() != null ? killer.getName() : this.plugin.getLanguageManager().raw("general.unknown");
        if (this.plugin.getConfigManager().isDeathSoundEnabled() && deathLocation != null) {
            try {
                Sound sound = Sound.valueOf((String)this.plugin.getConfigManager().getDeathSoundType());
                World soundWorld = deathLocation.getWorld();
                if (soundWorld != null) {
                    soundWorld.playSound(deathLocation, sound, this.plugin.getConfigManager().getDeathSoundVolume(), this.plugin.getConfigManager().getDeathSoundPitch());
                }
            }
            catch (IllegalArgumentException e) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-death-sound", LanguageManager.placeholders("sound", this.plugin.getConfigManager().getDeathSoundType())));
                this.plugin.logError("logs.invalid-death-sound", e);
            }
        }
        if (this.plugin.getConfigManager().isDeathTitleEnabled()) {
            String title = this.plugin.getConfigManager().getDeathTitleFormat().replace("{mobname}", mobName);
            String subtitle = this.plugin.getConfigManager().getDeathSubtitleFormat().replace("{killer}", killerName);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendTitle(ColorUtils.colorize(title), ColorUtils.colorize(subtitle), this.plugin.getConfigManager().getDeathTitleFadeIn(), this.plugin.getConfigManager().getDeathTitleStay(), this.plugin.getConfigManager().getDeathTitleFadeOut());
            }
        }
        Map<Integer, RewardBundle> allRewards = this.plugin.getConfigManager().getRankRewardBundles(mobType);
        int maxTop = allRewards.keySet().stream().max(Integer::compareTo).orElse(0);
        RewardDropCounter dropCounter = new RewardDropCounter(this.plugin.getConfigManager().getMaxTotalDropsPerBoss());
        if (lasthitPlayer != null) {
            double lasthitDamage = damageMap.getOrDefault(lasthitPlayer.getUniqueId(), 0.0);
            this.applyLastHitReward(mobType, lasthitPlayer, lasthitDamage, totalDamage, deathLocation, dropCounter);
        }
        for (int i = 0; i < sortedDamage.size(); ++i) {
            String name;
            Map.Entry entry = (Map.Entry)sortedDamage.get(i);
            OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
            String string2 = name = player.getName() != null ? player.getName() : this.plugin.getLanguageManager().raw("general.unknown");
            Player onlinePlayer = Bukkit.getPlayer((UUID)entry.getKey());
            RewardBundle bundle = allRewards.get(i + 1);
            if (onlinePlayer == null || !onlinePlayer.isOnline() || bundle == null) continue;
            if (!this.plugin.getSecurityGuard().isSafePlayerName(name)) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.skipped-unsafe-reward-player", LanguageManager.placeholders("player", name)));
                continue;
            }
            double damage = (Double)entry.getValue();
            double percentage = totalDamage > 0.0 ? damage / totalDamage * 100.0 : 0.0;
            if (!this.meetsRewardRequirements(bundle, damage, percentage)) {
                continue;
            }
            RewardContext context = new RewardContext(onlinePlayer, i + 1, damage, percentage, mobType, mobName, killerName, deathLocation);
            this.applyRewardBundle(bundle, context, dropCounter);
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
                double percentage = totalDamage > 0.0 ? damage / totalDamage * 100.0 : 0.0;
                String message = this.plugin.getConfigManager().getTopDamagePlayerFormat().replace("{position}", String.valueOf(i + 1)).replace("{player}", name).replace("{damage}", this.damageFormat.format(damage)).replace("{percentage}", this.percentFormat.format(percentage));
                Bukkit.broadcastMessage((String)ColorUtils.colorize(message));
            }
            Bukkit.broadcastMessage((String)ColorUtils.colorize(this.plugin.getConfigManager().getTopDamageFooter()));
        }
        if (this.plugin.getConfigManager().isRewardBroadcastEnabled()) {
            boolean headerSent = false;
            for (int i = 0; i < sortedDamage.size(); ++i) {
                Map.Entry entry = (Map.Entry)sortedDamage.get(i);
                OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)((UUID)entry.getKey()));
                RewardBundle bundle = allRewards.get(i + 1);
                if (!player.isOnline() || bundle == null || bundle.isEmpty()) continue;
                double damage = (Double)entry.getValue();
                double percentage = totalDamage > 0.0 ? damage / totalDamage * 100.0 : 0.0;
                if (!this.meetsRewardRequirements(bundle, damage, percentage)) {
                    continue;
                }
                if (!headerSent) {
                    String headerMsg = this.plugin.getConfigManager().getRewardsHeader().replace("{mobname}", mobName).replace("{killer}", killerName);
                    headerMsg = this.parsePlaceholders(player, headerMsg);
                    Bukkit.broadcastMessage((String)ColorUtils.colorize(headerMsg));
                    headerSent = true;
                }
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
            RewardBundle participationBundle = this.plugin.getConfigManager().getParticipationRewardBundle(mobType);
            for (Map.Entry entry : damageMap.entrySet()) {
                Player player;
                UUID uuid = (UUID)entry.getKey();
                double damage = (Double)entry.getValue();
                double percentage = totalDamage > 0.0 ? damage / totalDamage * 100.0 : 0.0;
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
                if (!this.meetsRewardRequirements(participationBundle, damage, percentage)) {
                    continue;
                }
                RewardContext context = new RewardContext(player, 0, damage, percentage, mobType, mobName, killerName, deathLocation);
                this.applyRewardBundle(participationBundle, context, dropCounter);
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
                this.plugin.logError(this.plugin.getLanguageManager().raw("logs.placeholder-parse-failed"), throwable);
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

    private void applyRewardBundle(RewardBundle bundle, RewardContext context, RewardDropCounter dropCounter) {
        for (String command : this.limitCommands(bundle.commands())) {
            this.dispatchRewardCommand(command, context);
        }
        for (RewardDrop drop : bundle.drops()) {
            this.dropReward(drop, context, dropCounter);
        }
    }

    public void applyLastHitReward(String mobType, Player player, double damage, double totalDamage, Location deathLocation) {
        this.applyLastHitReward(mobType, player, damage, totalDamage, deathLocation, new RewardDropCounter(this.plugin.getConfigManager().getMaxTotalDropsPerBoss()));
    }

    private void applyLastHitReward(String mobType, Player player, double damage, double totalDamage, Location deathLocation, RewardDropCounter dropCounter) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!this.plugin.getConfigManager().isLasthitRewardEnabled(mobType)) {
            return;
        }
        if (damage < this.plugin.getConfigManager().getLasthitMinDamage(mobType)) {
            return;
        }
        if (!this.plugin.getSecurityGuard().isSafePlayerName(player.getName())) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.skipped-unsafe-lasthit-player", LanguageManager.placeholders("player", player.getName())));
            return;
        }
        String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        RewardBundle bundle = this.plugin.getConfigManager().getLasthitRewardBundle(mobType);
        double percentage = totalDamage > 0.0 ? damage / totalDamage * 100.0 : 0.0;
        if (!this.meetsRewardRequirements(bundle, damage, percentage)) {
            return;
        }
        RewardContext context = new RewardContext(player, 0, damage, percentage, mobType, mobName, player.getName(), deathLocation);
        this.applyRewardBundle(bundle, context, dropCounter);
        Bukkit.broadcastMessage(this.plugin.getLanguageManager().get("rewards.lasthit-broadcast", LanguageManager.placeholders("player", player.getName())));
    }

    private boolean meetsRewardRequirements(RewardBundle bundle, double damage, double percentage) {
        return bundle != null && damage >= bundle.minDamage() && percentage >= bundle.minPercentage() && !bundle.isEmpty();
    }

    private void dispatchRewardCommand(String command, RewardContext context) {
        if (!this.plugin.getSecurityGuard().isRewardCommandAllowed(command)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.blocked-reward-command", LanguageManager.placeholders("command", command)));
            return;
        }
        String finalCommand = this.applyRewardPlaceholders(command.trim(), context);
        if (finalCommand.startsWith("/")) {
            finalCommand = finalCommand.substring(1);
        }
        if (!this.plugin.getSecurityGuard().isRewardCommandAllowed(finalCommand)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.blocked-reward-command-after-placeholder", LanguageManager.placeholders("command", command)));
            return;
        }
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)finalCommand);
    }

    private void dropReward(RewardDrop drop, RewardContext context, RewardDropCounter dropCounter) {
        if (drop.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() > drop.chance()) {
            return;
        }
        if (!dropCounter.tryReserve()) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.reward-drop-limit"));
            return;
        }
        int amount = this.calculateDropAmount(drop, context.percentage());
        Optional<ItemStack> stack = this.itemResolver.resolve(drop, amount);
        if (stack.isEmpty()) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.reward-drop-invalid",
                    LanguageManager.placeholders("provider", drop.provider(), "item", drop.item())));
            dropCounter.release();
            return;
        }
        Location location = drop.dropAtBoss() && context.deathLocation() != null ? context.deathLocation() : context.player().getLocation();
        if (!this.isSafeDropLocation(location)) {
            dropCounter.release();
            return;
        }
        World world = location.getWorld();
        ItemStack rewardStack = stack.get().clone();
        rewardStack.setAmount(Math.max(1, Math.min(rewardStack.getAmount(), this.plugin.getConfigManager().getMaxDropStackAmount())));
        try {
            Item item = world.dropItemNaturally(location, rewardStack);
            item.setPickupDelay(drop.pickupDelayTicks());
            this.disableMobPickupIfSupported(item);
            item.setGlowing(drop.glow());
            if (drop.privateDrop()) {
                item.setOwner(context.player().getUniqueId());
            }
        } catch (RuntimeException e) {
            dropCounter.release();
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.reward-drop-spawn-failed",
                    LanguageManager.placeholders("provider", drop.provider(), "item", drop.item(), "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            this.plugin.logError("logs.reward-drop-spawn-failed", e);
        }
    }

    private boolean isSafeDropLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) {
            return false;
        }
        World world = location.getWorld();
        return world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void disableMobPickupIfSupported(Item item) {
        try {
            item.getClass().getMethod("setCanMobPickup", boolean.class).invoke(item, false);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private int calculateDropAmount(RewardDrop drop, double percentage) {
        int scaled = drop.amount() + (int)Math.floor(percentage * drop.amountPerPercent());
        int capped = Math.min(scaled, drop.maxAmount());
        return Math.max(1, Math.min(capped, this.plugin.getConfigManager().getMaxDropStackAmount()));
    }

    private String applyRewardPlaceholders(String text, RewardContext context) {
        return text
                .replace("{player}", context.player().getName())
                .replace("{rank}", String.valueOf(context.rank()))
                .replace("{position}", String.valueOf(context.rank()))
                .replace("{damage}", this.damageFormat.format(context.damage()))
                .replace("{percentage}", this.percentFormat.format(context.percentage()))
                .replace("{mobtype}", context.mobType())
                .replace("{mobname}", context.mobName())
                .replace("{killer}", context.killerName());
    }

    private record RewardContext(Player player, int rank, double damage, double percentage, String mobType, String mobName, String killerName, Location deathLocation) {
    }

    private static final class RewardDropCounter {
        private final int limit;
        private int count;

        private RewardDropCounter(int limit) {
            this.limit = limit;
        }

        private boolean tryReserve() {
            if (this.limit <= 0 || this.count >= this.limit) {
                return false;
            }
            this.count++;
            return true;
        }

        private void release() {
            if (this.count > 0) {
                this.count--;
            }
        }
    }
}





