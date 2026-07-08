package com.siberanka.twibosses.holograms;

import java.util.List;
import org.bukkit.Location;

public interface HologramProvider {
    public void createHologram(String var1, Location var2, List<String> var3);

    public void updateHologram(String var1, List<String> var2);

    public void removeHologram(String var1);

    public void cleanup();
}





