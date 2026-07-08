package com.siberanka.twibosses.utils;

import org.bukkit.Bukkit;

public class VersionSupport {
    private static final String VERSION;
    private static final String SERVER_VERSION;
    private static final int MAJOR_VERSION;
    private static final int MINOR_VERSION;
    private static final int PATCH_VERSION;

    public static String getVersion() {
        return VERSION;
    }

    public static String getServerVersion() {
        return SERVER_VERSION;
    }

    public static boolean isSupported() {
        if (MAJOR_VERSION == 1) {
            if (MINOR_VERSION == 18) {
                return PATCH_VERSION >= 2;
            }
            if (MINOR_VERSION >= 19 && MINOR_VERSION <= 21) {
                return true;
            }
        } else if (MAJOR_VERSION == 26) {
            return true;
        }
        return false;
    }

    public static String getVersionCategory() {
        if (MAJOR_VERSION == 26) {
            return "Modern";
        }
        if (MINOR_VERSION <= 18) {
            return "Legacy";
        }
        if (MINOR_VERSION <= 19) {
            return "Transitional";
        }
        return "Modern";
    }

    public static boolean isAtLeast(String version) {
        try {
            String[] current = VERSION.split("\\.");
            String[] target = version.split("\\.");
            for (int i = 0; i < Math.min(current.length, target.length); ++i) {
                int targetPart;
                int currentPart = Integer.parseInt(current[i]);
                if (currentPart < (targetPart = Integer.parseInt(target[i]))) {
                    return false;
                }
                if (currentPart <= targetPart) continue;
                return true;
            }
            return current.length >= target.length;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static boolean isLegacy() {
        if (MAJOR_VERSION == 26) {
            return false;
        }
        return MINOR_VERSION <= 19;
    }

    public static int getMajorVersion() {
        return MAJOR_VERSION;
    }

    public static int getMinorVersion() {
        return MINOR_VERSION;
    }

    public static int getPatchVersion() {
        return PATCH_VERSION;
    }

    public static String getFormattedVersion() {
        return String.format("%d.%d.%d", MAJOR_VERSION, MINOR_VERSION, PATCH_VERSION);
    }

    public static boolean isVersion(String version) {
        return VERSION.equals(version);
    }

    public static String getCompatibilityStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Server Version: ").append(VersionSupport.getFormattedVersion()).append("\n");
        status.append("Category: ").append(VersionSupport.getVersionCategory()).append("\n");
        status.append("NMS Version: ").append(VersionSupport.getServerVersion()).append("\n");
        status.append("Supported: ").append(VersionSupport.isSupported() ? "Yes" : "No").append("\n");
        status.append("Version Type: ").append(VersionSupport.isLegacy() ? "Legacy" : "Modern");
        return status.toString();
    }

    public static boolean supportsFeature(VersionFeature feature) {
        if (MAJOR_VERSION == 26) {
            return true;
        }
        switch (feature.ordinal()) {
            case 0: 
            case 1: 
            case 2: {
                return MINOR_VERSION >= 19;
            }
            case 3: {
                return true;
            }
            case 4: {
                return MINOR_VERSION >= 20;
            }
        }
        return false;
    }

    static {
        String version = "0.0.0";
        String serverVersion = "unknown";
        int major = 0;
        int minor = 0;
        int patch = 0;
        try {
            String[] versionParts;
            String[] serverPackage = Bukkit.getServer().getClass().getPackage().getName().split("\\.");
            if (serverPackage.length > 3) {
                serverVersion = serverPackage[3];
            }
            major = (versionParts = (version = Bukkit.getBukkitVersion().split("-")[0]).split("\\.")).length > 0 ? Integer.parseInt(versionParts[0]) : 0;
            minor = versionParts.length > 1 ? Integer.parseInt(versionParts[1]) : 0;
            patch = versionParts.length > 2 ? Integer.parseInt(versionParts[2]) : 0;
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to determine server version", e);
        }
        VERSION = version;
        SERVER_VERSION = serverVersion;
        MAJOR_VERSION = major;
        MINOR_VERSION = minor;
        PATCH_VERSION = patch;
    }

    public static enum VersionFeature {
        ADVANCED_CHAT,
        ADVANCEMENT_CRITERIA,
        NEW_MATERIAL_SYSTEM,
        PERSISTENT_DATA,
        BUNDLE_API;

    }
}





