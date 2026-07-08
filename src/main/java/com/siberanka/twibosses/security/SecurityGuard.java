package com.siberanka.twibosses.security;

import com.siberanka.twibosses.TwiBosses;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SecurityGuard {
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final int WINDOW_MILLIS = 60_000;

    private final TwiBosses plugin;
    private final Map<String, Deque<Long>> commandWindows = new HashMap<>();
    private final Map<String, Long> manualSpawnCooldowns = new HashMap<>();

    public SecurityGuard(TwiBosses plugin) {
        this.plugin = plugin;
    }

    public boolean allowCommand(CommandSender sender) {
        int limit = sender instanceof Player
                ? plugin.getConfigManager().getMaxPlayerCommandsPerMinute()
                : plugin.getConfigManager().getMaxConsoleCommandsPerMinute();
        return allowInWindow(commandKey(sender), commandWindows, limit);
    }

    public boolean allowManualSpawn(Player player, String mobType) {
        int seconds = plugin.getConfigManager().getMinManualSpawnIntervalSeconds();
        if (seconds <= 0) {
            return true;
        }
        String key = player.getUniqueId() + ":" + mobType.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long nextAllowed = manualSpawnCooldowns.getOrDefault(key, 0L);
        if (nextAllowed > now) {
            return false;
        }
        manualSpawnCooldowns.put(key, now + seconds * 1000L);
        return true;
    }

    public boolean hasPermission(CommandSender sender, String node) {
        return sender.hasPermission("twibosses." + node);
    }

    public boolean isSafePlayerName(String playerName) {
        return playerName != null && PLAYER_NAME.matcher(playerName).matches();
    }

    public boolean isRewardCommandAllowed(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.length() > plugin.getConfigManager().getMaxRewardCommandLength()) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isISOControl(ch)) {
                return false;
            }
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String blocked : plugin.getConfigManager().getBlockedRewardCommandFragments()) {
            if (!blocked.isBlank() && lower.contains(blocked.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        List<String> allowedPrefixes = plugin.getConfigManager().getAllowedRewardCommandPrefixes();
        if (allowedPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : allowedPrefixes) {
            String cleanPrefix = prefix.trim().toLowerCase(Locale.ROOT);
            if (!cleanPrefix.isEmpty() && (lower.equals(cleanPrefix) || lower.startsWith(cleanPrefix + " "))) {
                return true;
            }
        }
        return false;
    }

    public String sanitizeDiscordText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength);
    }

    public boolean isAllowedDiscordWebhookUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(scheme) || host == null || path == null) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return (lowerHost.equals("discord.com") || lowerHost.equals("discordapp.com"))
                    && path.startsWith("/api/webhooks/");
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    public void clear() {
        commandWindows.clear();
        manualSpawnCooldowns.clear();
    }

    private String commandKey(CommandSender sender) {
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            return "player:" + uuid;
        }
        return "sender:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    private boolean allowInWindow(String key, Map<String, Deque<Long>> windows, int limit) {
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MILLIS) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }
}
