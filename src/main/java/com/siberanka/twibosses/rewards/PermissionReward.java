package com.siberanka.twibosses.rewards;

public final class PermissionReward {
    private final String id;
    private final String permission;
    private final RewardBundle bundle;

    public PermissionReward(String id, String permission, RewardBundle bundle) {
        this.id = id;
        this.permission = permission;
        this.bundle = bundle;
    }

    public String id() {
        return this.id;
    }

    public String permission() {
        return this.permission;
    }

    public RewardBundle bundle() {
        return this.bundle;
    }
}
