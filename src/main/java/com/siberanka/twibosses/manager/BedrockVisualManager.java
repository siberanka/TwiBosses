package com.siberanka.twibosses.manager;

import com.siberanka.twibosses.TwiBosses;
import com.siberanka.twibosses.rewards.ItemResolver;
import com.siberanka.twibosses.rewards.RewardDrop;
import com.siberanka.twibosses.utils.ColorUtils;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class BedrockVisualManager implements Listener {
    private static final String PROXY_TAG = "twibosses_bedrock_visual";
    private static final long HIT_WINDOW_MILLIS = 1000L;

    private final TwiBosses plugin;
    private final ItemResolver itemResolver;
    private final Map<UUID, VisualSession> sessionsByOriginal = new HashMap<>();
    private final Map<UUID, VisualSession> sessionsByProxy = new HashMap<>();
    private final Map<UUID, Deque<Long>> proxyHitWindows = new HashMap<>();
    private BukkitTask cleanupTask;
    private boolean floodgateUnavailableLogged;

    public BedrockVisualManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.itemResolver = new ItemResolver(plugin);
        this.startCleanupTask();
    }

    public void registerBoss(String mobType, Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)
                || !this.plugin.getConfigManager().isBedrockVisualsEnabled()
                || !this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            return;
        }
        if (!this.hasFloodgate()) {
            this.logFloodgateMissingOnce();
            return;
        }
        long delay = this.plugin.getConfigManager().getBedrockVisualSpawnDelayTicks(mobType);
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.createSession(mobType, livingEntity), delay);
    }

    public void unregisterBoss(Entity entity) {
        if (entity == null) {
            return;
        }
        VisualSession session = this.sessionsByOriginal.remove(entity.getUniqueId());
        if (session != null) {
            this.destroySession(session);
        }
    }

    public void unregisterBossByMobType(String mobType) {
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            if (session.mobType().equals(mobType)) {
                this.destroySession(session);
            }
        }
    }

    public boolean isProxy(Entity entity) {
        return entity != null && this.sessionsByProxy.containsKey(entity.getUniqueId());
    }

    public void reload() {
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()) {
            this.cleanup();
            return;
        }
        if (this.cleanupTask == null) {
            this.startCleanupTask();
        }
        this.rebindActiveBosses();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.applyVisibility(player);
        }
    }

    public void cleanup() {
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            this.destroySession(session);
        }
        this.sessionsByOriginal.clear();
        this.sessionsByProxy.clear();
        this.proxyHitWindows.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.applyVisibility(event.getPlayer()), 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProxyDamage(EntityDamageByEntityEvent event) {
        VisualSession session = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        if (!this.plugin.getConfigManager().shouldForwardBedrockProxyDamage(session.mobType())) {
            return;
        }
        Entity source = event.getDamager();
        Player player = this.resolvePlayer(source);
        if (player == null || !this.isFloodgatePlayer(player) || !this.allowProxyHit(player.getUniqueId())) {
            return;
        }
        LivingEntity original = session.original();
        if (!this.isValidLiving(original) || original.isDead()) {
            this.destroySession(session);
            return;
        }
        double damage = Math.max(0.0, Math.min(event.getFinalDamage(), this.plugin.getConfigManager().getMaxForwardedBedrockProxyDamage()));
        if (damage <= 0.0) {
            return;
        }
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.isValidLiving(original) && !original.isDead()) {
                original.damage(damage, source);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        this.unregisterBoss(event.getEntity());
        VisualSession session = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (session != null) {
            this.destroySession(session);
        }
    }

    private void createSession(String mobType, LivingEntity original) {
        if (!this.isValidLiving(original)
                || original.isDead()
                || this.sessionsByOriginal.containsKey(original.getUniqueId())
                || !this.plugin.getConfigManager().isBedrockVisualsEnabled()
                || !this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            return;
        }
        if (this.plugin.getConfigManager().shouldOnlyUseBedrockVisualWhenModeled(mobType) && !this.isModeledBoss(original, mobType)) {
            return;
        }
        EntityType proxyType = this.plugin.getConfigManager().getBedrockVisualEntityType(mobType);
        if (proxyType == null || !proxyType.isAlive() || !proxyType.isSpawnable()) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.bedrock-visual-invalid-entity",
                    LanguageManager.placeholders("mobtype", mobType, "entity", String.valueOf(proxyType))));
            return;
        }
        Location spawnLocation = original.getLocation();
        World world = spawnLocation.getWorld();
        if (world == null) {
            return;
        }
        Entity spawned = world.spawnEntity(spawnLocation, proxyType);
        if (!(spawned instanceof LivingEntity proxy)) {
            spawned.remove();
            return;
        }
        this.configureProxy(mobType, proxy);
        VisualSession session = new VisualSession(mobType, original, proxy, this.syncTask(mobType, original, proxy), new HashSet<>());
        this.sessionsByOriginal.put(original.getUniqueId(), session);
        this.sessionsByProxy.put(proxy.getUniqueId(), session);
        this.applyVisibilityForSession(session);
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw(
                "logs.bedrock-visual-created",
                LanguageManager.placeholders("mobtype", mobType, "entity", proxyType.name())));
    }

    private BukkitTask syncTask(String mobType, LivingEntity original, LivingEntity proxy) {
        int interval = this.plugin.getConfigManager().getBedrockVisualSyncIntervalTicks(mobType);
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (!BedrockVisualManager.this.isValidLiving(original) || original.isDead()
                        || !BedrockVisualManager.this.isValidLiving(proxy) || proxy.isDead()) {
                    this.cancel();
                    BedrockVisualManager.this.unregisterBoss(original);
                    return;
                }
                Location originalLocation = original.getLocation();
                if (originalLocation.getWorld() == null || !originalLocation.getWorld().equals(proxy.getWorld())) {
                    this.cancel();
                    BedrockVisualManager.this.unregisterBoss(original);
                    return;
                }
                proxy.teleport(originalLocation);
                proxy.setFireTicks(original.getFireTicks());
                if (proxy.getMaximumNoDamageTicks() != original.getMaximumNoDamageTicks()) {
                    proxy.setMaximumNoDamageTicks(original.getMaximumNoDamageTicks());
                }
            }
        }.runTaskTimer((Plugin)this.plugin, interval, interval);
    }

    private void configureProxy(String mobType, LivingEntity proxy) {
        proxy.addScoreboardTag(PROXY_TAG);
        proxy.addScoreboardTag("twibosses_bedrock_visual_" + safeTagPart(mobType));
        proxy.setRemoveWhenFarAway(false);
        proxy.setSilent(this.plugin.getConfigManager().isBedrockVisualSilent(mobType));
        proxy.setInvulnerable(true);
        proxy.setCollidable(false);
        proxy.setAI(false);
        proxy.setGravity(false);
        proxy.setCustomName(ColorUtils.colorize(this.plugin.getConfigManager().getMobDisplayName(mobType)));
        proxy.setCustomNameVisible(this.plugin.getConfigManager().isBedrockVisualNameVisible(mobType));
        this.applyEquipment(mobType, proxy);
    }

    private void applyEquipment(String mobType, LivingEntity proxy) {
        EntityEquipment equipment = proxy.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(this.resolveEquipmentItem(mobType, "main-hand"));
        equipment.setItemInOffHand(this.resolveEquipmentItem(mobType, "off-hand"));
        equipment.setHelmet(this.resolveEquipmentItem(mobType, "helmet"));
        equipment.setChestplate(this.resolveEquipmentItem(mobType, "chestplate"));
        equipment.setLeggings(this.resolveEquipmentItem(mobType, "leggings"));
        equipment.setBoots(this.resolveEquipmentItem(mobType, "boots"));
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
    }

    private ItemStack resolveEquipmentItem(String mobType, String slot) {
        ConfigManager.EquipmentItem configured = this.plugin.getConfigManager().getBedrockVisualEquipmentItem(mobType, slot);
        if (configured == null || configured.item().isBlank() || "AIR".equalsIgnoreCase(configured.item())) {
            return new ItemStack(Material.AIR);
        }
        if (!this.plugin.getSecurityGuard().isSafeRewardItem(configured.provider(), configured.item())) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.bedrock-visual-invalid-equipment",
                    LanguageManager.placeholders("mobtype", mobType, "slot", slot)));
            return new ItemStack(Material.AIR);
        }
        RewardDrop drop = new RewardDrop(configured.provider(), configured.item(), configured.amount(), 1.0, 0.0,
                configured.amount(), false, false, 0, false);
        Optional<ItemStack> stack = this.itemResolver.resolve(drop, configured.amount());
        return stack.orElseGet(() -> new ItemStack(Material.AIR));
    }

    private void applyVisibilityForSession(VisualSession session) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.applyVisibility(player, session);
        }
    }

    private void applyVisibility(Player player) {
        for (VisualSession session : this.sessionsByOriginal.values()) {
            this.applyVisibility(player, session);
        }
    }

    private void applyVisibility(Player player, VisualSession session) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (this.isFloodgatePlayer(player)) {
            player.hideEntity((Plugin)this.plugin, session.original());
            player.showEntity((Plugin)this.plugin, session.proxy());
            if (this.plugin.getConfigManager().shouldHideNearbyModelParts(session.mobType())) {
                for (Entity part : this.findModelPartCandidates(session.original(), session.mobType(),
                        this.plugin.getConfigManager().getModelPartHideRadius(session.mobType()))) {
                    session.modelPartIds().add(part.getUniqueId());
                    player.hideEntity((Plugin)this.plugin, part);
                }
            }
            return;
        }
        player.showEntity((Plugin)this.plugin, session.original());
        player.hideEntity((Plugin)this.plugin, session.proxy());
    }

    private boolean isModeledBoss(LivingEntity original, String mobType) {
        if (this.plugin.getConfigManager().isBedrockVisualModelForced(mobType)) {
            return true;
        }
        String mode = this.plugin.getConfigManager().getBedrockVisualModelMode(mobType);
        if ("false".equals(mode) || "none".equals(mode) || "vanilla".equals(mode)) {
            return false;
        }
        if (this.hasKnownModelMarker(original)) {
            return true;
        }
        double radius = this.plugin.getConfigManager().getBedrockVisualModelCheckRadius(mobType);
        return !this.findModelPartCandidates(original, mobType, radius).isEmpty();
    }

    private Collection<Entity> findModelPartCandidates(LivingEntity original, String mobType, double radius) {
        Set<Entity> candidates = new HashSet<>();
        if (!this.isValidLiving(original) || radius <= 0.0) {
            return candidates;
        }
        candidates.addAll(original.getPassengers());
        String lowerMobType = mobType.toLowerCase(Locale.ROOT);
        for (Entity nearby : original.getNearbyEntities(radius, radius, radius)) {
            if (nearby.equals(original) || nearby instanceof Player) {
                continue;
            }
            if (this.looksLikeModelPart(nearby, lowerMobType)) {
                candidates.add(nearby);
            }
        }
        return candidates;
    }

    private boolean looksLikeModelPart(Entity entity, String lowerMobType) {
        String typeName = entity.getType().name();
        if (!(entity instanceof ArmorStand) && !typeName.endsWith("_DISPLAY") && !"INTERACTION".equals(typeName)) {
            return false;
        }
        if (this.hasKnownModelMarker(entity)) {
            return true;
        }
        String name = entity.getCustomName();
        return name != null && !name.isBlank() && name.toLowerCase(Locale.ROOT).contains(lowerMobType);
    }

    private boolean hasKnownModelMarker(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.contains("modelengine") || lower.contains("model_engine")
                    || lower.contains("bettermodel") || lower.contains("better_model")) {
                return true;
            }
        }
        for (String key : new String[]{"ModelEngine", "modelengine", "modeled_entity", "BetterModel", "bettermodel", "better_model"}) {
            if (entity.hasMetadata(key)) {
                return true;
            }
        }
        String className = entity.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("modelengine") || className.contains("bettermodel");
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean allowProxyHit(UUID playerId) {
        int limit = this.plugin.getConfigManager().getMaxBedrockProxyHitsPerSecond();
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = this.proxyHitWindows.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > HIT_WINDOW_MILLIS) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    private void destroySession(VisualSession session) {
        this.sessionsByOriginal.remove(session.original().getUniqueId());
        this.sessionsByProxy.remove(session.proxy().getUniqueId());
        if (session.syncTask() != null) {
            session.syncTask().cancel();
        }
        Entity proxy = session.proxy();
        if (proxy.isValid()) {
            proxy.remove();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showEntity((Plugin)this.plugin, session.original());
            for (UUID modelPartId : session.modelPartIds()) {
                Entity modelPart = Bukkit.getEntity(modelPartId);
                if (modelPart != null) {
                    player.showEntity((Plugin)this.plugin, modelPart);
                }
            }
        }
    }

    private void startCleanupTask() {
        this.cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (VisualSession session : new ArrayList<>(BedrockVisualManager.this.sessionsByOriginal.values())) {
                    if (!BedrockVisualManager.this.isValidLiving(session.original())
                            || session.original().isDead()
                            || !BedrockVisualManager.this.isValidLiving(session.proxy())
                            || session.proxy().isDead()) {
                        BedrockVisualManager.this.destroySession(session);
                    }
                }
            }
        }.runTaskTimer((Plugin)this.plugin, 100L, 100L);
    }

    private void rebindActiveBosses() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                Optional<ActiveMob> activeMob = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
                if (activeMob.isEmpty()) {
                    continue;
                }
                String mobType = activeMob.get().getMobType();
                if (this.plugin.getConfigManager().getTrackedMobs().contains(mobType)) {
                    this.registerBoss(mobType, entity);
                }
            }
        }
    }

    private boolean isValidLiving(Entity entity) {
        return entity instanceof LivingEntity && entity.isValid() && entity.getWorld() != null;
    }

    private boolean hasFloodgate() {
        return Bukkit.getPluginManager().getPlugin("floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Floodgate") != null
                || this.findClass("org.geysermc.floodgate.api.FloodgateApi") != null;
    }

    private boolean isFloodgatePlayer(Player player) {
        if (player == null) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method method = api.getClass().getMethod("isFloodgatePlayer", UUID.class);
            Object result = method.invoke(api, player.getUniqueId());
            return result instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Class<?> findClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private void logFloodgateMissingOnce() {
        if (this.floodgateUnavailableLogged) {
            return;
        }
        this.floodgateUnavailableLogged = true;
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.bedrock-visual-floodgate-missing"));
    }

    private static String safeTagPart(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-.]", "_");
    }

    private record VisualSession(String mobType, LivingEntity original, LivingEntity proxy, BukkitTask syncTask, Set<UUID> modelPartIds) {
    }
}
