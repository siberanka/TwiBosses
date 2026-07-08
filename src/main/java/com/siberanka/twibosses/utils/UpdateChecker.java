package com.siberanka.twibosses.utils;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;

public class UpdateChecker
implements Listener {
    private static final String RELEASES_API_URL = "https://api.github.com/repos/siberanka/TwiBosses/releases/latest";

    private final TwiBosses plugin;
    private String latestVersion;
    private String downloadUrl;

    public UpdateChecker(TwiBosses plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)plugin);
        this.checkForUpdates();
    }

    private void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously((Plugin)this.plugin, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(RELEASES_API_URL).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "TwiBosses/" + this.plugin.getDescription().getVersion());
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream inputStream = connection.getInputStream()) {
                        JSONObject release = new JSONObject(this.readUtf8(inputStream));
                        this.latestVersion = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                        this.downloadUrl = release.optString("html_url", "https://github.com/siberanka/TwiBosses/releases/latest");
                        String currentVersion = this.plugin.getDescription().getVersion().replaceFirst("^[vV]", "");
                        if (!this.latestVersion.isBlank() && !currentVersion.equalsIgnoreCase(this.latestVersion)) {
                            for (String line : this.plugin.getLanguageManager().list("updates.console", LanguageManager.placeholders("current", currentVersion, "latest", this.latestVersion, "url", this.downloadUrl))) {
                                this.plugin.getLogger().info(ColorUtils.colorize(line));
                            }
                        }
                    }
                }
            }
            catch (IOException e) {
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("updates.check-failed", LanguageManager.placeholders("error", e.getMessage())));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String currentVersion;
        Player player = event.getPlayer();
        if (player.hasPermission("twibosses.update") && this.latestVersion != null && !(currentVersion = this.plugin.getDescription().getVersion().replaceFirst("^[vV]", "")).equalsIgnoreCase(this.latestVersion)) {
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                for (String line : this.plugin.getLanguageManager().list("updates.player", LanguageManager.placeholders("current", currentVersion, "latest", this.latestVersion, "url", this.downloadUrl))) {
                    player.sendMessage(line);
                }
            }, 40L);
        }
    }

    private String readUtf8(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}





