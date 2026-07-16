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
    private static final int QUEUE_CAPACITY = 512;
    private static final int MAX_CATEGORY_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 8192;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());

    private final TwiBosses plugin;
    private final File dataFolder;
    private final File logFile;
    private final BoundedAsyncLogWriter<DebugEntry> writer;
    private volatile boolean enabled;
    private volatile long maxSizeBytes = 1024L * 1024L;
    private volatile int maxArchives = 3;
    private volatile String queueOverflowTemplate = "Debug log queue full; dropped {count} entries.";

    public DebugLogManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        this.logFile = new File(this.dataFolder, "debug.log");
        this.writer = new BoundedAsyncLogWriter<>("TwiBosses-DebugLog", QUEUE_CAPACITY, this::writeEntry);
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
        if (this.plugin.getLanguageManager() != null) {
            this.queueOverflowTemplate = this.plugin.getLanguageManager().raw("logs.debug-log-queue-overflow");
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void log(String category, String message) {
        if (!this.enabled) {
            return;
        }
        this.writer.submit(new DebugEntry(
                Instant.now(),
                clean(Thread.currentThread().getName(), 128),
                clean(category, MAX_CATEGORY_LENGTH),
                clean(message, MAX_MESSAGE_LENGTH)));
    }

    public void shutdown() {
        this.enabled = false;
        this.writer.close();
    }

    private void writeEntry(DebugEntry entry, long droppedBefore) throws IOException {
        if (!this.dataFolder.exists() && !this.dataFolder.mkdirs()) {
            return;
        }
        this.rotateIfNeeded();
        StringBuilder output = new StringBuilder(256);
        if (droppedBefore > 0L) {
            output.append("[").append(TIMESTAMP.format(Instant.now())).append("] ")
                    .append("[thread=TwiBosses-DebugLog] [logger] ")
                    .append(this.queueOverflowTemplate.replace("{count}", Long.toString(droppedBefore)))
                    .append(System.lineSeparator());
        }
        if (entry != null) {
            output.append("[").append(TIMESTAMP.format(entry.timestamp())).append("] ")
                    .append("[thread=").append(entry.threadName()).append("] ")
                    .append("[").append(entry.category()).append("] ")
                    .append(entry.message()).append(System.lineSeparator());
        }
        Files.writeString(this.logFile.toPath(), output.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
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

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String bounded = value.length() > maxLength ? value.substring(0, maxLength) : value;
        return bounded.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    private record DebugEntry(Instant timestamp, String threadName, String category, String message) {
    }
}
