package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

public final class ErrorLogManager {
    private static final long DEFAULT_MAX_SIZE_BYTES = 1024L * 1024L;
    private static final int DEFAULT_MAX_ARCHIVES = 3;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());

    private final TwiBosses plugin;
    private final File logFile;
    private final Object lock = new Object();
    private final Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private final Thread.UncaughtExceptionHandler uncaughtHandler;
    private final Handler handler;
    private boolean enabled = true;
    private boolean includeWarnings = true;
    private long maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
    private int maxArchives = DEFAULT_MAX_ARCHIVES;
    private boolean installed;
    private String lastFingerprint = "";
    private long lastFingerprintMillis;

    public ErrorLogManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "error.log");
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
    }

    public void uninstall() {
        if (!this.installed) {
            return;
        }
        this.plugin.getLogger().removeHandler(this.handler);
        Bukkit.getLogger().removeHandler(this.handler);
        if (Thread.getDefaultUncaughtExceptionHandler() == this.uncaughtHandler) {
            Thread.setDefaultUncaughtExceptionHandler(this.previousUncaughtHandler);
        }
        this.installed = false;
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
        Throwable current = throwable;
        while (current != null) {
            for (StackTraceElement element : current.getStackTrace()) {
                if (element.getClassName().startsWith("com.siberanka.twibosses.")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void write(String level, String loggerName, String message, Throwable throwable) {
        synchronized (this.lock) {
            try {
                if (this.isDuplicate(level, loggerName, message, throwable)) {
                    return;
                }
                File folder = this.plugin.getDataFolder();
                if (!folder.exists() && !folder.mkdirs()) {
                    return;
                }
                this.rotateIfNeeded();
                StringBuilder builder = new StringBuilder(1024);
                builder.append("[").append(TIMESTAMP.format(Instant.now())).append("] ");
                builder.append("[").append(level).append("] ");
                builder.append("[thread=").append(Thread.currentThread().getName()).append("] ");
                builder.append("[logger=").append(loggerName == null ? "unknown" : loggerName).append("]").append(System.lineSeparator());
                builder.append("Plugin: ").append(this.plugin.getDescription().getName()).append(" ").append(this.plugin.getDescription().getVersion()).append(System.lineSeparator());
                builder.append("Server: ").append(Bukkit.getName()).append(" ").append(Bukkit.getVersion()).append(System.lineSeparator());
                builder.append("Message: ").append(clean(message)).append(System.lineSeparator());
                if (throwable != null) {
                    builder.append("Stacktrace:").append(System.lineSeparator()).append(stackTrace(throwable));
                }
                builder.append(System.lineSeparator()).append("----").append(System.lineSeparator());
                Files.writeString(this.logFile.toPath(), builder.toString(), StandardCharsets.UTF_8,
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

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    private boolean isDuplicate(String level, String loggerName, String message, Throwable throwable) {
        long now = System.currentTimeMillis();
        String fingerprint = level + "|" + loggerName + "|" + message + "|" + throwableFingerprint(throwable);
        if (fingerprint.equals(this.lastFingerprint) && now - this.lastFingerprintMillis < 1000L) {
            return true;
        }
        this.lastFingerprint = fingerprint;
        this.lastFingerprintMillis = now;
        return false;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String throwableFingerprint(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        String top = stackTrace.length == 0 ? "" : stackTrace[0].toString();
        return throwable.getClass().getName() + ":" + throwable.getMessage() + ":" + top;
    }
}
