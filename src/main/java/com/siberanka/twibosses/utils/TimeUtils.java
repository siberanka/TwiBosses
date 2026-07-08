package com.siberanka.twibosses.utils;

public class TimeUtils {
    public static String formatTime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds %= 3600L) / 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds %= 60L);
    }
}





