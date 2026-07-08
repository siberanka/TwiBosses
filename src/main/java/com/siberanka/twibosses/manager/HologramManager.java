package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.holograms.DecentHologramProvider;
import com.siberanka.twibosses.holograms.FancyHologramProvider;
import com.siberanka.twibosses.holograms.HologramProvider;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

public class HologramManager {
    private final TwiBosses plugin;
    private HologramProvider provider;
    private boolean enabled;

    public HologramManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.setupHologramProvider();
    }

    private void setupHologramProvider() {
        String type = this.plugin.getConfig().getString("hologram.provider", "NONE").toUpperCase();
        boolean bl = this.enabled = !type.equals("NONE");
        if (!this.enabled) {
            return;
        }
        switch (type) {
            case "FANCY": {
                Plugin fancyPlugin = this.plugin.getServer().getPluginManager().getPlugin("FancyHolograms");
                if (fancyPlugin != null && fancyPlugin.isEnabled()) {
                    this.provider = new FancyHologramProvider();
                    this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.hologram-fancy"));
                    break;
                }
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.hologram-fancy-missing"));
                this.enabled = false;
                break;
            }
            case "DECENT": {
                Plugin decentPlugin = this.plugin.getServer().getPluginManager().getPlugin("DecentHolograms");
                if (decentPlugin != null && decentPlugin.isEnabled()) {
                    this.provider = new DecentHologramProvider();
                    this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.hologram-decent"));
                    break;
                }
                this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw("logs.hologram-decent-missing"));
                this.enabled = false;
                break;
            }
            default: {
                this.enabled = false;
                this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.hologram-disabled"));
            }
        }
    }

    public void createHologram(String id, Location location, List<String> lines) {
        if (this.enabled && this.provider != null) {
            this.provider.createHologram(id, location, lines);
        }
    }

    public void updateHologram(String id, List<String> lines) {
        if (this.enabled && this.provider != null) {
            this.provider.updateHologram(id, lines);
        }
    }

    public void removeHologram(String id) {
        if (this.enabled && this.provider != null) {
            this.provider.removeHologram(id);
        }
    }

    public void cleanup() {
        if (this.enabled && this.provider != null) {
            this.provider.cleanup();
        }
        this.provider = null;
        this.enabled = false;
    }

    public void reload() {
        this.cleanup();
        this.setupHologramProvider();
    }

    public boolean isEnabled() {
        return this.enabled;
    }
}





