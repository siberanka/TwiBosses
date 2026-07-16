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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

public final class ErrorLogManager {
    private static final long DEFAULT_MAX_SIZE_BYTES = 1024L * 1024L;
    private static final int DEFAULT_MAX_ARCHIVES = 3;
    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_LEVEL_LENGTH = 32;
    private static final int MAX_LOGGER_NAME_LENGTH = 256;
    private static final int MAX_MESSAGE_LENGTH = 8192;
    private static final int MAX_STACK_TRACE_LENGTH = 32_768;
    private static final int MAX_THROWABLES = 64;
    private static final int MAX_STACK_FRAMES = 256;
    private static final int MAX_PLUGIN_SCAN_FRAMES = 1024;
    private static final int MAX_PLUGIN_SCAN_FRAMES_PER_THROWABLE = 64;
    private static final int MAX_SUPPRESSED_PER_THROWABLE = 8;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());

    private final TwiBosses plugin;
    private final File dataFolder;
    private final File logFile;
    private final Object fingerprintLock = new Object();
    private final BoundedAsyncLogWriter<ErrorEntry> writer;
    private final String pluginInfo;
    private final String serverInfo;
    private final Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private final Thread.UncaughtExceptionHandler uncaughtHandler;
    private final Handler handler;
    private volatile boolean enabled = true;
    private volatile boolean includeWarnings = true;
    private volatile long maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
    private volatile int maxArchives = DEFAULT_MAX_ARCHIVES;
    private volatile String queueOverflowTemplate = "Error log queue full; dropped {count} entries.";
    private boolean installed;
    private String lastFingerprint = "";
    private long lastFingerprintMillis;

    public ErrorLogManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        this.logFile = new File(this.dataFolder, "error.log");
        this.pluginInfo = plugin.getDescription().getName() + " " + plugin.getDescription().getVersion();
        this.serverInfo = Bukkit.getName() + " " + Bukkit.getVersion();
        this.writer = new BoundedAsyncLogWriter<>("TwiBosses-ErrorLog", QUEUE_CAPACITY, this::writeEntry);
        this.previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.uncaughtHandler = (thread, throwable) -> {
            if (this.isPluginThrowable(throwable)) {
                this.log("Uncaught exception in thread " + thread.getName(), throwable);
            }
            if (this.previousUncaughtHandler != null) {
                this.previousUncaughtHandler.uncaughtException(thread, throwable);
            }
        };
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                ErrorLogManager.this.publish(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    public void install() {
        if (this.installed) {
            return;
        }
        this.installed = true;
        this.handler.setLevel(Level.ALL);
        this.plugin.getLogger().addHandler(this.handler);
        Bukkit.getLogger().addHandler(this.handler);
        Thread.setDefaultUncaughtExceptionHandler(this.uncaughtHandler);
    }

    public void reload() {
        ConfigManager configManager = this.plugin.getConfigManager();
        if (configManager == null) {
            return;
        }
        this.enabled = configManager.isErrorLogEnabled();
        this.includeWarnings = configManager.shouldErrorLogIncludeWarnings();
        this.maxSizeBytes = Math.max(64L * 1024L, configManager.getErrorLogMaxSizeKb() * 1024L);
        this.maxArchives = Math.max(1, configManager.getErrorLogMaxArchives());
        if (this.plugin.getLanguageManager() != null) {
            this.queueOverflowTemplate = this.plugin.getLanguageManager().raw("logs.error-log-queue-overflow");
        }
    }

    public void uninstall() {
        if (this.installed) {
            this.plugin.getLogger().removeHandler(this.handler);
            Bukkit.getLogger().removeHandler(this.handler);
            if (Thread.getDefaultUncaughtExceptionHandler() == this.uncaughtHandler) {
                Thread.setDefaultUncaughtExceptionHandler(this.previousUncaughtHandler);
            }
            this.installed = false;
        }
        this.enabled = false;
        this.writer.close();
    }

    public void log(String message, Throwable throwable) {
        if (!this.enabled) {
            return;
        }
        this.write(Level.SEVERE.getName(), "TwiBosses", message, throwable);
    }

    private void publish(LogRecord record) {
        if (!this.enabled || record == null || !this.shouldRecord(record)) {
            return;
        }
        String message = record.getMessage() == null ? "" : record.getMessage();
        this.write(record.getLevel().getName(), record.getLoggerName(), message, record.getThrown());
    }

    private boolean shouldRecord(LogRecord record) {
        if (record.getLevel().intValue() < (this.includeWarnings ? Level.WARNING.intValue() : Level.SEVERE.intValue())) {
            return false;
        }
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.toLowerCase(Locale.ROOT).contains("twibosses")) {
            return true;
        }
        String message = record.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("twibosses")) {
            return true;
        }
        return this.isPluginThrowable(record.getThrown());
    }

    private boolean isPluginThrowable(Throwable throwable) {
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int inspectedFrames = 0;
        if (throwable != null) {
            pending.add(throwable);
        }
        while (!pending.isEmpty() && visited.size() < MAX_THROWABLES && inspectedFrames < MAX_PLUGIN_SCAN_FRAMES) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            int inspectedCurrentFrames = 0;
            for (StackTraceElement element : safeStackTrace(current)) {
                if (inspectedFrames++ >= MAX_PLUGIN_SCAN_FRAMES
                        || inspectedCurrentFrames++ >= MAX_PLUGIN_SCAN_FRAMES_PER_THROWABLE) {
                    break;
                }
                if (element != null && element.getClassName().startsWith("com.siberanka.twibosses.")) {
                    return true;
                }
            }
            Throwable cause = safeCause(current);
            if (cause != null && !visited.contains(cause)) {
                pending.addLast(cause);
            }
            Throwable[] suppressed = safeSuppressed(current);
            for (int index = 0; index < Math.min(suppressed.length, MAX_SUPPRESSED_PER_THROWABLE); index++) {
                Throwable child = suppressed[index];
                if (child != null && !visited.contains(child)) {
                    pending.addLast(child);
                }
            }
        }
        return false;
    }

    private void write(String level, String loggerName, String message, Throwable throwable) {
        String safeLevel = level == null ? "UNKNOWN" : clean(level, MAX_LEVEL_LENGTH);
        String safeLoggerName = clean(loggerName == null ? "unknown" : loggerName, MAX_LOGGER_NAME_LENGTH);
        String safeMessage = clean(message, MAX_MESSAGE_LENGTH);
        if (this.isDuplicate(safeLevel, safeLoggerName, safeMessage, throwable)) {
            return;
        }
        this.writer.submit(new ErrorEntry(
                Instant.now(),
                safeLevel,
                safeLoggerName,
                clean(Thread.currentThread().getName(), 128),
                safeMessage,
                throwable == null ? "" : stackTrace(throwable)));
    }

    private void writeEntry(ErrorEntry entry, long droppedBefore) throws IOException {
        if (!this.dataFolder.exists() && !this.dataFolder.mkdirs()) {
            return;
        }
        this.rotateIfNeeded();
        StringBuilder builder = new StringBuilder(1280);
        if (droppedBefore > 0L) {
            builder.append("[").append(TIMESTAMP.format(Instant.now())).append("] [WARNING] ")
                    .append("[thread=TwiBosses-ErrorLog] [logger=async-writer]").append(System.lineSeparator())
                    .append("Plugin: ").append(this.pluginInfo).append(System.lineSeparator())
                    .append("Server: ").append(this.serverInfo).append(System.lineSeparator())
                    .append("Message: ").append(this.queueOverflowTemplate.replace("{count}", Long.toString(droppedBefore)))
                    .append(System.lineSeparator())
                    .append(System.lineSeparator()).append("----").append(System.lineSeparator());
        }
        if (entry != null) {
            builder.append("[").append(TIMESTAMP.format(entry.timestamp())).append("] ");
            builder.append("[").append(entry.level()).append("] ");
            builder.append("[thread=").append(entry.threadName()).append("] ");
            builder.append("[logger=").append(entry.loggerName()).append("]").append(System.lineSeparator());
            builder.append("Plugin: ").append(this.pluginInfo).append(System.lineSeparator());
            builder.append("Server: ").append(this.serverInfo).append(System.lineSeparator());
            builder.append("Message: ").append(entry.message()).append(System.lineSeparator());
            if (!entry.stackTrace().isEmpty()) {
                builder.append("Stacktrace:").append(System.lineSeparator()).append(entry.stackTrace());
            }
            builder.append(System.lineSeparator()).append("----").append(System.lineSeparator());
        }
        Files.writeString(this.logFile.toPath(), builder.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private void rotateIfNeeded() throws IOException {
        if (!this.logFile.exists() || this.logFile.length() < this.maxSizeBytes) {
            return;
        }
        for (int index = this.maxArchives; index >= 1; index--) {
            File source = index == 1 ? this.logFile : new File(this.logFile.getParentFile(), "error.log." + (index - 1));
            File target = new File(this.logFile.getParentFile(), "error.log." + index);
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

    private boolean isDuplicate(String level, String loggerName, String message, Throwable throwable) {
        synchronized (this.fingerprintLock) {
            long now = System.currentTimeMillis();
            String fingerprint = level + "|" + loggerName + "|" + message + "|" + throwableFingerprint(throwable);
            if (fingerprint.equals(this.lastFingerprint) && now - this.lastFingerprintMillis < 1000L) {
                return true;
            }
            this.lastFingerprint = fingerprint;
            this.lastFingerprintMillis = now;
            return false;
        }
    }

    static String stackTrace(Throwable throwable) {
        StringBuilder output = new StringBuilder(4096);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        TraceBudget budget = new TraceBudget();
        appendThrowable(output, throwable, "", visited, budget);
        return output.toString();
    }

    private static String throwableFingerprint(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StackTraceElement[] stackTrace = safeStackTrace(throwable);
        String top = stackTrace.length == 0 ? "" : stackTrace[0].toString();
        return throwable.getClass().getName() + ":" + clean(safeMessage(throwable), 256) + ":" + clean(top, 512);
    }

    private record ErrorEntry(Instant timestamp, String level, String loggerName, String threadName,
                              String message, String stackTrace) {
    }

    private static void appendThrowable(StringBuilder output, Throwable throwable, String caption,
                                        Set<Throwable> visited, TraceBudget budget) {
        if (throwable == null || output.length() >= MAX_STACK_TRACE_LENGTH) {
            return;
        }
        if (budget.throwables++ >= MAX_THROWABLES) {
            appendLimited(output, "... throwable graph truncated ..." + System.lineSeparator());
            return;
        }
        if (!visited.add(throwable)) {
            appendLimited(output, caption + "[CIRCULAR REFERENCE: " + throwable.getClass().getName() + "]"
                    + System.lineSeparator());
            return;
        }
        appendLimited(output, caption + throwable.getClass().getName());
        String message = clean(safeMessage(throwable), 2048);
        if (!message.isEmpty()) {
            appendLimited(output, ": " + message);
        }
        appendLimited(output, System.lineSeparator());

        StackTraceElement[] frames = safeStackTrace(throwable);
        for (StackTraceElement frame : frames) {
            if (budget.frames++ >= MAX_STACK_FRAMES) {
                appendLimited(output, "\t... stack frames truncated ..." + System.lineSeparator());
                break;
            }
            appendLimited(output, "\tat " + clean(String.valueOf(frame), 1024) + System.lineSeparator());
        }

        Throwable[] suppressed = safeSuppressed(throwable);
        int suppressedLimit = Math.min(suppressed.length, MAX_SUPPRESSED_PER_THROWABLE);
        for (int index = 0; index < suppressedLimit; index++) {
            appendThrowable(output, suppressed[index], "Suppressed: ", visited, budget);
        }
        if (suppressed.length > suppressedLimit) {
            appendLimited(output, "... additional suppressed exceptions truncated ..." + System.lineSeparator());
        }
        appendThrowable(output, safeCause(throwable), "Caused by: ", visited, budget);
    }

    private static void appendLimited(StringBuilder output, String value) {
        if (value == null || output.length() >= MAX_STACK_TRACE_LENGTH) {
            return;
        }
        int writable = Math.min(value.length(), MAX_STACK_TRACE_LENGTH - output.length());
        output.append(value, 0, writable);
    }

    private static StackTraceElement[] safeStackTrace(Throwable throwable) {
        try {
            StackTraceElement[] frames = throwable.getStackTrace();
            return frames == null ? new StackTraceElement[0] : frames;
        } catch (Throwable ignored) {
            return new StackTraceElement[0];
        }
    }

    private static Throwable[] safeSuppressed(Throwable throwable) {
        try {
            Throwable[] suppressed = throwable.getSuppressed();
            return suppressed == null ? new Throwable[0] : suppressed;
        } catch (Throwable ignored) {
            return new Throwable[0];
        }
    }

    private static Throwable safeCause(Throwable throwable) {
        try {
            return throwable.getCause();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeMessage(Throwable throwable) {
        try {
            return throwable.getMessage();
        } catch (Throwable ignored) {
            return "<message unavailable>";
        }
    }

    private static final class TraceBudget {
        private int throwables;
        private int frames;
    }
}
