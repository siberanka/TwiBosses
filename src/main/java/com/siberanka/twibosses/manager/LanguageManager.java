package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.utils.ColorUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LanguageManager {
    private static final List<String> DEFAULT_LANGUAGES = List.of("tr", "en", "az", "es");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TwiBosses plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private FileConfiguration activeLanguage;
    private String activeCode = "tr";

    public LanguageManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public synchronized void reload() {
        this.languages.clear();
        File languageFolder = new File(this.plugin.getDataFolder(), "languages");
        if (!languageFolder.exists() && !languageFolder.mkdirs()) {
            this.plugin.getLogger().warning(this.bootstrapRaw("logs.language-folder-create-failed", Collections.emptyMap()));
        }
        for (String code : DEFAULT_LANGUAGES) {
            this.loadLanguage(languageFolder, code);
        }
        String configured = this.plugin.getConfigManager().getLanguageCode();
        if (!this.languages.containsKey(configured)) {
            this.plugin.getLogger().warning(this.bootstrapRaw("logs.language-unknown", placeholders("language", configured)));
            configured = "tr";
        }
        this.activeCode = configured;
        this.activeLanguage = this.languages.getOrDefault(configured, this.languages.get("tr"));
    }

    public String getActiveCode() {
        return this.activeCode;
    }

    public String get(String path) {
        return this.get(path, Collections.emptyMap());
    }

    public String get(String path, Map<String, String> placeholders) {
        return ColorUtils.colorize(this.raw(path, placeholders));
    }

    public String raw(String path) {
        return this.raw(path, Collections.emptyMap());
    }

    public String raw(String path, Map<String, String> placeholders) {
        String value = this.activeLanguage != null ? this.activeLanguage.getString(path) : null;
        if (value == null) {
            value = this.fallback(path);
        }
        return this.applyPlaceholders(value == null ? "" : value, placeholders);
    }

    public List<String> list(String path, Map<String, String> placeholders) {
        List<String> values = this.activeLanguage != null ? this.activeLanguage.getStringList(path) : Collections.emptyList();
        if (values.isEmpty()) {
            FileConfiguration fallback = this.languages.get("tr");
            if (fallback != null && fallback.isList(path)) {
                values = fallback.getStringList(path);
            }
        }
        List<String> formatted = new ArrayList<>();
        for (String value : values) {
            formatted.add(ColorUtils.colorize(this.applyPlaceholders(value, placeholders)));
        }
        return formatted;
    }

    public static Map<String, String> placeholders(String... values) {
        if (values == null || values.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> placeholders = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            placeholders.put(values[i], values[i + 1] == null ? "" : values[i + 1]);
        }
        return placeholders;
    }

    private void loadLanguage(File languageFolder, String code) {
        String resourcePath = "languages/" + code + ".yml";
        File file = new File(languageFolder, code + ".yml");
        if (!file.exists()) {
            this.plugin.saveResource(resourcePath, false);
        }
        try (InputStream stream = this.plugin.getResource(resourcePath)) {
            if (stream == null) {
                this.plugin.getLogger().warning(this.bootstrapRaw("logs.language-resource-missing", placeholders("resource", resourcePath)));
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(stream, StandardCharsets.UTF_8));
            YamlConfiguration language = YamlConfiguration.loadConfiguration(file);
            if (this.repairLanguage(language, defaults)) {
                this.backup(file, "language-" + code);
                this.saveSafely(language, file);
                this.plugin.getLogger().info(this.bootstrapRaw("logs.language-repaired", placeholders("language", code)));
            }
            this.languages.put(code.toLowerCase(Locale.ROOT), language);
        } catch (Exception ex) {
            this.plugin.getLogger().warning(this.bootstrapRaw("logs.language-load-failed", placeholders("language", code, "error", ex.getMessage())));
        }
    }

    private boolean repairLanguage(YamlConfiguration language, YamlConfiguration defaults) {
        boolean changed = false;
        List<String> existingKeys = new ArrayList<>(language.getKeys(true));
        existingKeys.sort((left, right) -> Integer.compare(depth(right), depth(left)));
        for (String key : existingKeys) {
            if (!this.isAllowedLanguagePath(key, defaults)) {
                language.set(key, null);
                changed = true;
            }
        }
        List<String> defaultKeys = new ArrayList<>(defaults.getKeys(true));
        defaultKeys.sort((left, right) -> Integer.compare(depth(left), depth(right)));
        for (String key : defaultKeys) {
            Object expected = defaults.get(key);
            if (expected instanceof ConfigurationSection) {
                if (!language.isConfigurationSection(key)) {
                    language.set(key, null);
                    language.createSection(key);
                    changed = true;
                }
                continue;
            }
            if (!language.contains(key) || !sameKind(language.get(key), expected)) {
                language.set(key, expected);
                changed = true;
            }
        }
        return changed;
    }

    private boolean isAllowedLanguagePath(String path, YamlConfiguration defaults) {
        if (defaults.contains(path)) {
            return true;
        }
        return matchesDynamicSchema(path, "mobs", Set.of("display-name", "announcement", "death-message"))
                || matchesDynamicSchema(path, "damage.threshold.per-mob", Set.of("message"))
                || matchesWebhookSchema(path);
    }

    private boolean matchesWebhookSchema(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 3 || !"webhooks".equals(parts[0])) {
            return false;
        }
        if (!Set.of("spawn", "death").contains(parts[2])) {
            return false;
        }
        String relative = parts.length == 3 ? "" : String.join(".", List.of(parts).subList(3, parts.length));
        return relative.isEmpty()
                || Set.of("content", "username", "embed-title", "embed-description", "embed-footer", "field-boss", "field-world", "field-location", "field-killer").contains(relative);
    }

    private boolean matchesDynamicSchema(String path, String rootPath, Set<String> allowedRelatives) {
        String[] parts = path.split("\\.");
        String[] rootParts = rootPath.split("\\.");
        if (parts.length < rootParts.length + 1) {
            return false;
        }
        for (int i = 0; i < rootParts.length; i++) {
            if (!rootParts[i].equals(parts[i])) {
                return false;
            }
        }
        String relative = parts.length == rootParts.length + 1 ? "" : String.join(".", List.of(parts).subList(rootParts.length + 1, parts.length));
        if (relative.isEmpty()) {
            return true;
        }
        return allowedRelatives.contains(relative);
    }

    private String fallback(String path) {
        FileConfiguration fallback = this.languages.get("tr");
        return fallback != null ? fallback.getString(path, "") : "";
    }

    private String bootstrapRaw(String path, Map<String, String> placeholders) {
        String value = this.fallback(path);
        if (value == null || value.isBlank()) {
            value = path;
        }
        return this.applyPlaceholders(value, placeholders);
    }

    private String applyPlaceholders(String message, Map<String, String> placeholders) {
        String formatted = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            formatted = formatted.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return formatted;
    }

    private void backup(File file, String prefix) throws IOException {
        if (!file.exists()) {
            return;
        }
        File backupFolder = new File(this.plugin.getDataFolder(), "file-backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            throw new IOException("Could not create backup folder.");
        }
        String stamp = BACKUP_TIME.format(LocalDateTime.now());
        Files.copy(file.toPath(), new File(backupFolder, prefix + "-" + stamp + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveSafely(YamlConfiguration configuration, File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        configuration.save(temp);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int depth(String path) {
        return path.isEmpty() ? 0 : path.split("\\.").length;
    }

    private static boolean sameKind(Object value, Object expected) {
        if (value == null || expected == null) {
            return value == expected;
        }
        if (expected instanceof List<?>) {
            return value instanceof List<?>;
        }
        if (expected instanceof Number) {
            return value instanceof Number;
        }
        if (expected instanceof Boolean) {
            return value instanceof Boolean;
        }
        return value instanceof String;
    }
}
