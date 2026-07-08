package com.siberanka.twibosses.utils;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public class ColorUtils {
    private static final Pattern HEX_PATTERN = Pattern.compile("#[a-fA-F0-9]{6}");
    private static final Pattern ALT_HEX_PATTERN = Pattern.compile("&(#[a-fA-F0-9]{6})");
    private static final Pattern GRADIENT_PATTERN = Pattern.compile("<gradient:([^>]+)>([^<]+)</gradient>");

    public static String colorize(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() > 2048) {
            message = message.substring(0, 2048);
        }
        StringBuffer sb = new StringBuffer();
        Matcher altMatcher = ALT_HEX_PATTERN.matcher(message);
        while (altMatcher.find()) {
            String hexColor = altMatcher.group(1);
            try {
                altMatcher.appendReplacement(sb, ChatColor.of((String)hexColor).toString());
            }
            catch (IllegalArgumentException e) {
                altMatcher.appendReplacement(sb, "");
            }
        }
        altMatcher.appendTail(sb);
        message = sb.toString();
        sb = new StringBuffer();
        Matcher matcher = HEX_PATTERN.matcher(message);
        while (matcher.find()) {
            try {
                matcher.appendReplacement(sb, ChatColor.of((String)matcher.group()).toString());
            }
            catch (IllegalArgumentException e) {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        message = sb.toString();
        matcher = GRADIENT_PATTERN.matcher(message);
        while (matcher.find()) {
            String[] colors = matcher.group(1).split(":");
            String text = matcher.group(2);
            if (text.length() > 512 || colors.length > 8) {
                message = message.replace(matcher.group(), text);
                continue;
            }
            try {
                message = message.replace(matcher.group(), ColorUtils.applyGradient(text, colors));
            }
            catch (Exception e) {
                message = message.replace(matcher.group(), text);
            }
        }
        return ChatColor.translateAlternateColorCodes((char)'&', (String)message);
    }

    private static String applyGradient(String text, String[] hexColors) {
        if (hexColors.length < 2) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        Color[] colors = new Color[hexColors.length];
        for (int i = 0; i < hexColors.length; ++i) {
            Object hex = hexColors[i];
            if (!((String)hex).startsWith("#")) {
                hex = "#" + (String)hex;
            }
            try {
                colors[i] = Color.decode((String)hex);
                continue;
            }
            catch (NumberFormatException e) {
                return text;
            }
        }
        int steps = text.length() - 1;
        if (steps < 1) {
            return text;
        }
        char[] characters = text.toCharArray();
        for (int i = 0; i < characters.length; ++i) {
            double percent = (double)i / (double)steps;
            Color color = ColorUtils.interpolateColors(colors, percent);
            result.append(ChatColor.of((Color)color)).append(characters[i]);
        }
        return result.toString();
    }

    private static Color interpolateColors(Color[] colors, double percent) {
        int colorCount = colors.length - 1;
        double adjustedPercent = percent * (double)colorCount;
        int i = (int)adjustedPercent;
        double remainder = adjustedPercent - (double)i;
        Color c1 = colors[Math.min(i, colors.length - 1)];
        Color c2 = colors[Math.min(i + 1, colors.length - 1)];
        int red = (int)((double)c1.getRed() * (1.0 - remainder) + (double)c2.getRed() * remainder);
        int green = (int)((double)c1.getGreen() * (1.0 - remainder) + (double)c2.getGreen() * remainder);
        int blue = (int)((double)c1.getBlue() * (1.0 - remainder) + (double)c2.getBlue() * remainder);
        return new Color(red, green, blue);
    }
}





