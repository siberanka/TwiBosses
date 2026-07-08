package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DebugLogManager {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());

    private final TwiBosses plugin;
    private final File logFile;
    private final Object lock = new Object();
    private boolean enabled;
    private long maxSizeBytes = 1024L * 1024L;
    private int maxArchives = 3;

    public DebugLogManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "debug.log");
    }

    public void reload() {
        ConfigManager configManager = this.plugin.getConfigManager();
        if (configManager == null) {
            this.enabled = false;
            return;
        }
        this.enabled = configManager.isDebugLogEnabled();
        this.maxSizeBytes = Math.max(64L * 1024L, (long)configManager.getDebugLogMaxSizeKb() * 1024L);
        this.maxArchives = Math.max(1, configManager.getDebugLogMaxArchives());
        this.log("debug", "Debug logging " + (this.enabled ? "enabled" : "disabled") + ".");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void log(String category, String message) {
        if (!this.enabled) {
            return;
        }
        synchronized (this.lock) {
            try {
                File folder = this.plugin.getDataFolder();
                if (!folder.exists() && !folder.mkdirs()) {
                    return;
                }
                this.rotateIfNeeded();
                String line = "[" + TIMESTAMP.format(Instant.now()) + "] "
                        + "[thread=" + Thread.currentThread().getName() + "] "
                        + "[" + clean(category) + "] "
                        + clean(message) + System.lineSeparator();
                Files.writeString(this.logFile.toPath(), line, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!this.logFile.exists() || this.logFile.length() < this.maxSizeBytes) {
            return;
        }
        for (int index = this.maxArchives; index >= 1; index--) {
            File source = index == 1 ? this.logFile : new File(this.logFile.getParentFile(), "debug.log." + (index - 1));
            File target = new File(this.logFile.getParentFile(), "debug.log." + index);
            if (!source.exists()) {
                continue;
            }
            if (index == this.maxArchives) {
                Files.deleteIfExists(target.toPath());
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }
}
