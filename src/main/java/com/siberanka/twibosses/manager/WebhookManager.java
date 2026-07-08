package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.json.JSONArray;
import org.json.JSONObject;

public class WebhookManager {
    private final TwiBosses plugin;

    public WebhookManager(TwiBosses plugin) {
        this.plugin = plugin;
    }

    public void sendSpawnWebhook(String mobType, Location location) {
        this.sendWebhook(mobType, "spawn", location, null);
    }

    public void sendDeathWebhook(String mobType, Location location, String killer) {
        this.sendWebhook(mobType, "death", location, killer);
    }

    private void sendWebhook(String mobType, String event, Location location, String killer) {
        ConfigurationSection section = this.plugin.getConfigManager().getWebhookSection(mobType, event);
        if (section == null || !section.getBoolean("enabled", false)) {
            return;
        }
        String url = section.getString("url");
        if (!this.plugin.getSecurityGuard().isAllowedDiscordWebhookUrl(url)) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.invalid-webhook-url", LanguageManager.placeholders("mobtype", mobType, "event", event)));
            return;
        }
        String mobName = this.plugin.getConfigManager().getMobDisplayName(mobType);
        String world = location.getWorld() != null ? location.getWorld().getName() : this.plugin.getLanguageManager().raw("general.unknown");
        String locationStr = String.format("%.1f, %.1f, %.1f", location.getX(), location.getY(), location.getZ());
        JSONObject embed = new JSONObject();
        boolean isSpawn = event.equals("spawn");
        Map<String, String> placeholders = LanguageManager.placeholders("mobname", mobName, "location", locationStr, "world", world, "killer", killer == null ? "" : killer);
        String basePath = "webhooks." + mobType + "." + event;
        String title = this.languageRaw(basePath + ".embed-title", "webhooks.default." + event + ".embed-title", placeholders);
        String description = this.languageRaw(basePath + ".embed-description", "webhooks.default." + event + ".embed-description", placeholders);
        embed.put("title", this.limitField(title.replace("{mobname}", mobName)));
        embed.put("description", this.limitField(description));
        embed.put("color", isSpawn ? 0xEF4444 : 2278750);
        JSONArray fields = new JSONArray();
        JSONObject mobField = new JSONObject();
        mobField.put("name", this.limitField(this.languageRaw(basePath + ".field-boss", "webhooks.default.field-boss", placeholders)));
        mobField.put("value", "```" + this.limitField(mobName) + "```");
        mobField.put("inline", true);
        fields.put(mobField);
        JSONObject worldField = new JSONObject();
        worldField.put("name", this.limitField(this.languageRaw(basePath + ".field-world", "webhooks.default.field-world", placeholders)));
        worldField.put("value", "```" + this.limitField(world) + "```");
        worldField.put("inline", true);
        fields.put(worldField);
        JSONObject locationField = new JSONObject();
        locationField.put("name", this.limitField(this.languageRaw(basePath + ".field-location", "webhooks.default.field-location", placeholders)));
        locationField.put("value", "```" + this.limitField(locationStr) + "```");
        locationField.put("inline", true);
        fields.put(locationField);
        if (!isSpawn && killer != null) {
            JSONObject killerField = new JSONObject();
            killerField.put("name", this.limitField(this.languageRaw(basePath + ".field-killer", "webhooks.default.field-killer", placeholders)));
            killerField.put("value", "```" + this.limitField(killer) + "```");
            killerField.put("inline", false);
            fields.put(killerField);
        }
        embed.put("fields", fields);
        JSONObject footer = new JSONObject();
        footer.put("text", this.limitField(this.languageRaw(basePath + ".embed-footer", "webhooks.default." + event + ".embed-footer", placeholders)));
        embed.put("footer", footer);
        embed.put("timestamp", Instant.now().toString());
        String thumbnail = section.getString("embed-thumbnail", "");
        if (!thumbnail.isEmpty()) {
            JSONObject thumb = new JSONObject();
            if (thumbnail.startsWith("https://")) {
                thumb.put("url", thumbnail);
                embed.put("thumbnail", thumb);
            }
        }
        String pingContent = this.languageRaw(basePath + ".content", "webhooks.default." + event + ".content", placeholders);
        String username = this.languageRaw(basePath + ".username", "webhooks.default.username", placeholders);
        this.sendDiscordWebhook(url, this.limitContent(pingContent), embed, this.limitField(username), section.getString("avatar-url", ""));
    }

    private void sendDiscordWebhook(String url, String content, JSONObject embed, String username, String avatarUrl) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setConnectTimeout(this.plugin.getConfigManager().getWebhookConnectTimeoutMs());
                connection.setReadTimeout(this.plugin.getConfigManager().getWebhookReadTimeoutMs());
                connection.setDoOutput(true);
                JSONObject json = new JSONObject();
                if (content != null && !content.isEmpty()) {
                    json.put("content", content);
                }
                if (!username.isEmpty()) {
                    json.put("username", username);
                }
                if (!avatarUrl.isEmpty() && avatarUrl.startsWith("https://")) {
                    json.put("avatar_url", avatarUrl);
                }
                JSONArray embeds = new JSONArray();
                embeds.put(embed);
                json.put("embeds", embeds);
                try (OutputStream os = connection.getOutputStream();){
                    os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int responseCode = connection.getResponseCode();
                if (responseCode != 204 && connection.getErrorStream() != null) {
                    connection.getErrorStream().close();
                }
            }
            catch (Exception e) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.webhook-send-failed", LanguageManager.placeholders("error", e.getMessage())));
                this.plugin.logError("logs.webhook-send-failed", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String limitContent(String value) {
        return this.plugin.getSecurityGuard().sanitizeDiscordText(value, this.plugin.getConfigManager().getMaxWebhookContentLength());
    }

    private String limitField(String value) {
        return this.plugin.getSecurityGuard().sanitizeDiscordText(value, this.plugin.getConfigManager().getMaxWebhookFieldLength());
    }

    private String languageRaw(String path, String fallbackPath, Map<String, String> placeholders) {
        String value = this.plugin.getLanguageManager().raw(path, placeholders);
        if (!value.isBlank()) {
            return value;
        }
        return this.plugin.getLanguageManager().raw(fallbackPath, placeholders);
    }
}





