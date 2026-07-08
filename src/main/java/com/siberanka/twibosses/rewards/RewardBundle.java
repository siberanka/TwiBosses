package com.siberanka.twibosses.rewards;

import java.util.List;

public final class RewardBundle {
    private final List<String> commands;
    private final List<RewardDrop> drops;
    private final double minDamage;
    private final double minPercentage;

    public RewardBundle(List<String> commands, List<RewardDrop> drops, double minDamage, double minPercentage) {
        this.commands = List.copyOf(commands);
        this.drops = List.copyOf(drops);
        this.minDamage = minDamage;
        this.minPercentage = minPercentage;
    }

    public List<String> commands() {
        return this.commands;
    }

    public List<RewardDrop> drops() {
        return this.drops;
    }

    public double minDamage() {
        return this.minDamage;
    }

    public double minPercentage() {
        return this.minPercentage;
    }

    public boolean isEmpty() {
        return this.commands.isEmpty() && this.drops.isEmpty();
    }
}
