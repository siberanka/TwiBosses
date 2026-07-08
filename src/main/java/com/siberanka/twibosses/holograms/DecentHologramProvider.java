package com.siberanka.twibosses.holograms;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;

public class DecentHologramProvider implements HologramProvider {
    private final Map<String, Object> activeHolograms = new HashMap<String, Object>();

    @Override
    public void createHologram(String id, Location location, List<String> lines) {
        try {
            this.removeHologram(id);
            Class<?> dhapi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            Method create = dhapi.getMethod("createHologram", String.class, Location.class, List.class);
            Object hologram = create.invoke(null, id, location, lines);
            activeHolograms.put(id, hologram);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void updateHologram(String id, List<String> lines) {
        Object hologram = activeHolograms.get(id);
        if (hologram == null) {
            return;
        }
        try {
            Class<?> dhapi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            Class<?> hologramClass = Class.forName("eu.decentsoftware.holograms.api.holograms.Hologram");
            Method setLines = dhapi.getMethod("setHologramLines", hologramClass, List.class);
            setLines.invoke(null, hologram, lines);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void removeHologram(String id) {
        Object hologram = activeHolograms.remove(id);
        if (hologram == null) {
            return;
        }
        try {
            hologram.getClass().getMethod("delete").invoke(hologram);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void cleanup() {
        for (String id : List.copyOf(activeHolograms.keySet())) {
            this.removeHologram(id);
        }
    }
}
