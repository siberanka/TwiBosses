package com.siberanka.twibosses.holograms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;

public class FancyHologramProvider implements HologramProvider {
    private final Map<String, Object> activeHolograms = new HashMap<String, Object>();

    @Override
    public void createHologram(String id, Location location, List<String> lines) {
        try {
            this.removeHologram(id);
            Object manager = this.hologramManager();
            Class<?> dataClass = Class.forName("de.oliver.fancyholograms.api.data.HologramData");
            Class<?> textDataClass = Class.forName("de.oliver.fancyholograms.api.data.TextHologramData");
            Class<?> hologramClass = Class.forName("de.oliver.fancyholograms.api.hologram.Hologram");
            Constructor<?> constructor = textDataClass.getConstructor(String.class, Location.class);
            Object hologramData = constructor.newInstance(id, location);
            textDataClass.getMethod("setText", List.class).invoke(hologramData, lines);
            Object hologram = manager.getClass().getMethod("create", dataClass).invoke(manager, hologramData);
            manager.getClass().getMethod("addHologram", hologramClass).invoke(manager, hologram);
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
            Object data = hologram.getClass().getMethod("getData").invoke(hologram);
            Class<?> textDataClass = Class.forName("de.oliver.fancyholograms.api.data.TextHologramData");
            textDataClass.getMethod("setText", List.class).invoke(data, lines);
            hologram.getClass().getMethod("forceUpdate").invoke(hologram);
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
            Object manager = this.hologramManager();
            Class<?> hologramClass = Class.forName("de.oliver.fancyholograms.api.hologram.Hologram");
            manager.getClass().getMethod("removeHologram", hologramClass).invoke(manager, hologram);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void cleanup() {
        for (String id : List.copyOf(activeHolograms.keySet())) {
            this.removeHologram(id);
        }
    }

    private Object hologramManager() throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin");
        Object plugin = pluginClass.getMethod("get").invoke(null);
        return plugin.getClass().getMethod("getHologramManager").invoke(plugin);
    }
}
