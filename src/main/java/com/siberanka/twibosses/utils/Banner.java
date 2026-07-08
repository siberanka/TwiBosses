package com.siberanka.twibosses.utils;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.manager.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;

public final class Banner {
    private Banner() {
    }

    public static void display(TwiBosses plugin, PluginDescriptionFile description) {
        for (String line : plugin.getLanguageManager().list("banner.enable", LanguageManager.placeholders("version", description.getVersion()))) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }

    public static void disable(TwiBosses plugin, PluginDescriptionFile description) {
        for (String line : plugin.getLanguageManager().list("banner.disable", LanguageManager.placeholders("version", description.getVersion()))) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }
}
