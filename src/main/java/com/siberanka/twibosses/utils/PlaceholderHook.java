package com.siberanka.twibosses.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

public class PlaceholderHook {
    public static String setPlaceholders(OfflinePlayer player, String text) {
        if (text == null) {
            return "";
        }
        return PlaceholderAPI.setPlaceholders((OfflinePlayer)player, (String)text);
    }
}





