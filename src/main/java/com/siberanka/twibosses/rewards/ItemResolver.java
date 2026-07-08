package com.siberanka.twibosses.rewards;

import com.siberanka.twibosses.TwiBosses;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemResolver {
    private final TwiBosses plugin;

    public ItemResolver(TwiBosses plugin) {
        this.plugin = plugin;
    }

    public Optional<ItemStack> resolve(RewardDrop drop, int amount) {
        if (amount <= 0 || !this.plugin.getSecurityGuard().isSafeRewardItem(drop.provider(), drop.item())) {
            return Optional.empty();
        }
        try {
            ItemStack stack = switch (drop.provider().toUpperCase(Locale.ROOT)) {
                case "VANILLA" -> this.vanilla(drop.item(), amount);
                case "MYTHICMOBS", "MYTHIC" -> this.mythicMobs(drop.item(), amount);
                case "ITEMSADDER", "IA" -> this.itemsAdder(drop.item(), amount);
                case "NEXO" -> this.nexo(drop.item(), amount);
                case "CRAFTENGINE", "CE" -> this.craftEngine(drop.item(), amount);
                default -> null;
            };
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                return Optional.empty();
            }
            stack.setAmount(Math.min(amount, this.plugin.getConfigManager().getMaxDropStackAmount()));
            return Optional.of(stack);
        } catch (Throwable ex) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.reward-drop-resolve-failed",
                    com.siberanka.twibosses.manager.LanguageManager.placeholders(
                            "provider", drop.provider(),
                            "item", drop.item(),
                            "error", ex.getClass().getSimpleName())));
            return Optional.empty();
        }
    }

    private ItemStack vanilla(String itemId, int amount) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        return new ItemStack(material, amount);
    }

    private ItemStack mythicMobs(String itemId, int amount) {
        ItemStack stack = MythicBukkit.inst().getItemManager().getItemStack(itemId, amount);
        return stack == null ? null : stack.clone();
    }

    private ItemStack itemsAdder(String itemId, int amount) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) {
            return null;
        }
        Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
        Object customStack = customStackClass.getMethod("getInstance", String.class).invoke(null, itemId);
        if (customStack == null) {
            return null;
        }
        Object stack = customStackClass.getMethod("getItemStack").invoke(customStack);
        return cloneStack(stack, amount);
    }

    private ItemStack nexo(String itemId, int amount) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) {
            return null;
        }
        Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");
        Object builder = nexoItemsClass.getMethod("itemFromId", String.class).invoke(null, itemId);
        if (builder == null) {
            return null;
        }
        Object stack = builder.getClass().getMethod("build").invoke(builder);
        return cloneStack(stack, amount);
    }

    private ItemStack craftEngine(String itemId, int amount) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") == null) {
            return null;
        }
        ItemStack stack = tryCraftEngineFactory("net.momirealms.craftengine.bukkit.api.CraftEngineItems", itemId, amount);
        if (stack != null) {
            return stack;
        }
        stack = tryCraftEngineFactory("net.momirealms.craftengine.bukkit.api.CraftEngineBukkitItems", itemId, amount);
        if (stack != null) {
            return stack;
        }
        return tryCraftEngineSingleton(itemId, amount);
    }

    private ItemStack tryCraftEngineFactory(String className, String itemId, int amount) {
        try {
            Class<?> apiClass = Class.forName(className);
            for (String methodName : new String[]{"byId", "itemById", "getItem", "createItem", "buildItemStack"}) {
                for (Method method : apiClass.getMethods()) {
                    if (!method.getName().equals(methodName) || method.getParameterCount() == 0) {
                        continue;
                    }
                    Object result = invokeItemFactory(method, null, itemId, amount);
                    ItemStack stack = asItemStack(result, amount);
                    if (stack != null) {
                        return stack;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ItemStack tryCraftEngineSingleton(String itemId, int amount) {
        try {
            Class<?> pluginClass = Class.forName("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine");
            Object instance = pluginClass.getMethod("instance").invoke(null);
            for (Method method : pluginClass.getMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains("item") || method.getParameterCount() > 1) {
                    continue;
                }
                Object manager = method.getParameterCount() == 0 ? method.invoke(instance) : null;
                ItemStack stack = tryManager(manager, itemId, amount);
                if (stack != null) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ItemStack tryManager(Object manager, String itemId, int amount) {
        if (manager == null) {
            return null;
        }
        for (Method method : manager.getClass().getMethods()) {
            if (method.getParameterCount() == 0) {
                continue;
            }
            String lower = method.getName().toLowerCase(Locale.ROOT);
            if (!(lower.contains("item") || lower.contains("build") || lower.contains("create"))) {
                continue;
            }
            try {
                Object result = invokeItemFactory(method, manager, itemId, amount);
                ItemStack stack = asItemStack(result, amount);
                if (stack != null) {
                    return stack;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object invokeItemFactory(Method method, Object target, String itemId, int amount) throws Exception {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length == 1 && parameters[0] == String.class) {
            return method.invoke(target, itemId);
        }
        if (parameters.length == 2 && parameters[0] == String.class && (parameters[1] == int.class || parameters[1] == Integer.class)) {
            return method.invoke(target, itemId, amount);
        }
        return null;
    }

    private static ItemStack asItemStack(Object result, int amount) throws Exception {
        if (result == null) {
            return null;
        }
        if (result instanceof Optional<?> optional) {
            return optional.map(value -> {
                try {
                    return asItemStack(value, amount);
                } catch (Exception ignored) {
                    return null;
                }
            }).orElse(null);
        }
        if (result instanceof ItemStack stack) {
            return cloneStack(stack, amount);
        }
        for (String methodName : new String[]{"build", "buildItemStack", "getItemStack", "itemStack"}) {
            try {
                Method method = result.getClass().getMethod(methodName);
                Object nested = method.invoke(result);
                if (nested instanceof ItemStack stack) {
                    return cloneStack(stack, amount);
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static ItemStack cloneStack(Object stack, int amount) {
        if (!(stack instanceof ItemStack itemStack)) {
            return null;
        }
        ItemStack clone = itemStack.clone();
        clone.setAmount(amount);
        return clone;
    }
}
