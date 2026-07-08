package com.siberanka.twibosses.rewards;

public final class RewardDrop {
    private final String provider;
    private final String item;
    private final int amount;
    private final double chance;
    private final double amountPerPercent;
    private final int maxAmount;
    private final boolean privateDrop;
    private final boolean dropAtBoss;
    private final int pickupDelayTicks;
    private final boolean glow;

    public RewardDrop(
            String provider,
            String item,
            int amount,
            double chance,
            double amountPerPercent,
            int maxAmount,
            boolean privateDrop,
            boolean dropAtBoss,
            int pickupDelayTicks,
            boolean glow) {
        this.provider = provider;
        this.item = item;
        this.amount = amount;
        this.chance = chance;
        this.amountPerPercent = amountPerPercent;
        this.maxAmount = maxAmount;
        this.privateDrop = privateDrop;
        this.dropAtBoss = dropAtBoss;
        this.pickupDelayTicks = pickupDelayTicks;
        this.glow = glow;
    }

    public String provider() {
        return this.provider;
    }

    public String item() {
        return this.item;
    }

    public int amount() {
        return this.amount;
    }

    public double chance() {
        return this.chance;
    }

    public double amountPerPercent() {
        return this.amountPerPercent;
    }

    public int maxAmount() {
        return this.maxAmount;
    }

    public boolean privateDrop() {
        return this.privateDrop;
    }

    public boolean dropAtBoss() {
        return this.dropAtBoss;
    }

    public int pickupDelayTicks() {
        return this.pickupDelayTicks;
    }

    public boolean glow() {
        return this.glow;
    }
}
