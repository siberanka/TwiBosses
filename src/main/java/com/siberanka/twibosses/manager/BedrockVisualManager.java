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
import org.bukkit.EntityEffect;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
    private final Map<UUID, BukkitTask> pendingSessionTasks = new HashMap<>();
    private BukkitTask cleanupTask;
    private boolean bedrockBridgeUnavailableLogged;

    public BedrockVisualManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.itemResolver = new ItemResolver(plugin);
        this.startCleanupTask();
    }

    public void registerBoss(String mobType, Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)) {
            this.debug("register skipped mobType=" + mobType + " reason=not_living entity=" + this.entityInfo(entity));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()) {
            this.debug("register skipped mobType=" + mobType + " reason=global_disabled original=" + this.entityInfo(livingEntity));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            this.debug("register skipped mobType=" + mobType + " reason=mob_disabled original=" + this.entityInfo(livingEntity));
            return;
        }
        if (!this.hasBedrockBridge()) {
            this.logBedrockBridgeMissingOnce();
            this.debug("register skipped mobType=" + mobType + " reason=bedrock_bridge_missing original=" + this.entityInfo(livingEntity));
            return;
        }
        UUID entityId = livingEntity.getUniqueId();
        if (this.sessionsByOriginal.containsKey(entityId) || this.pendingSessionTasks.containsKey(entityId)) {
            this.debug("register skipped mobType=" + mobType + " reason=already_registered_or_pending original=" + this.entityInfo(livingEntity));
            return;
        }
        long delay = this.plugin.getConfigManager().getBedrockVisualSpawnDelayTicks(mobType);
        this.debug("register scheduled mobType=" + mobType + " delayTicks=" + delay + " original=" + this.entityInfo(livingEntity));
        this.scheduleSessionCreate(mobType, livingEntity, delay, 0);
    }

    public void unregisterBoss(Entity entity) {
        if (entity == null) {
            return;
        }
        this.unregisterBoss(entity.getUniqueId());
    }

    public void unregisterBoss(UUID entityId) {
        if (entityId == null) {
            return;
        }
        VisualSession session = this.sessionsByOriginal.remove(entityId);
        if (session != null) {
            this.destroySession(session, false);
        }
        BukkitTask pendingTask = this.pendingSessionTasks.remove(entityId);
        if (pendingTask != null) {
            pendingTask.cancel();
        }
    }

    public void unregisterBossByMobType(String mobType) {
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            if (session.mobType().equals(mobType)) {
                this.destroySession(session, false);
            }
        }
    }

    public boolean isProxy(Entity entity) {
        return entity != null && this.sessionsByProxy.containsKey(entity.getUniqueId());
    }

    public void reload() {
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()) {
            this.debug("reload requested but bedrock visuals are disabled; cleaning sessions");
            this.cleanup();
            return;
        }
        this.debug("reload rebuilding sessions currentSessions=" + this.sessionsByOriginal.size() + " pending=" + this.pendingSessionTasks.size());
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            this.destroySession(session, false);
        }
        for (BukkitTask pendingTask : this.pendingSessionTasks.values()) {
            pendingTask.cancel();
        }
        this.sessionsByOriginal.clear();
        this.sessionsByProxy.clear();
        this.pendingSessionTasks.clear();
        this.proxyHitWindows.clear();
        if (this.cleanupTask == null) {
            this.startCleanupTask();
        }
        this.rebindActiveBosses();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.applyVisibility(player);
        }
        this.debug("reload complete sessions=" + this.sessionsByOriginal.size() + " onlinePlayers=" + Bukkit.getOnlinePlayers().size());
    }

    public void cleanup() {
        this.debug("cleanup sessions=" + this.sessionsByOriginal.size() + " pending=" + this.pendingSessionTasks.size());
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            this.destroySession(session, false);
        }
        for (BukkitTask pendingTask : this.pendingSessionTasks.values()) {
            pendingTask.cancel();
        }
        this.sessionsByOriginal.clear();
        this.sessionsByProxy.clear();
        this.proxyHitWindows.clear();
        this.pendingSessionTasks.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.applyVisibility(event.getPlayer()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.applyVisibility(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.applyVisibility(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProxyDamage(EntityDamageByEntityEvent event) {
        VisualSession session = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        if (!this.plugin.getConfigManager().shouldForwardBedrockProxyDamage(session.mobType())) {
            this.debug("proxy damage blocked reason=forwarding_disabled mobType=" + session.mobType() + " proxy=" + this.entityInfo(session.proxy()));
            return;
        }
        Entity source = event.getDamager();
        Player player = this.resolvePlayer(source);
        if (player == null) {
            this.debug("proxy damage blocked reason=no_player_source source=" + this.entityInfo(source) + " proxy=" + this.entityInfo(session.proxy()));
            return;
        }
        if (!this.isBedrockPlayer(player)) {
            this.debug("proxy damage blocked reason=not_bedrock player=" + this.playerInfo(player) + " proxy=" + this.entityInfo(session.proxy()));
            return;
        }
        if (!this.allowProxyHit(player.getUniqueId())) {
            this.debug("proxy damage blocked reason=rate_limited player=" + this.playerInfo(player) + " proxy=" + this.entityInfo(session.proxy()));
            return;
        }
        LivingEntity original = session.original();
        if (!this.isValidLiving(original) || original.isDead()) {
            this.debug("proxy damage blocked reason=original_invalid mobType=" + session.mobType() + " original=" + this.entityInfo(original));
            this.destroySession(session);
            return;
        }
        double damage = Math.max(0.0, Math.min(event.getFinalDamage(), this.plugin.getConfigManager().getMaxForwardedBedrockProxyDamage()));
        if (damage <= 0.0) {
            this.debug("proxy damage blocked reason=non_positive_damage player=" + this.playerInfo(player) + " finalDamage=" + event.getFinalDamage());
            return;
        }
        this.debug("proxy damage forwarded player=" + this.playerInfo(player) + " mobType=" + session.mobType() + " damage=" + damage + " original=" + this.entityInfo(original));
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.isValidLiving(original) && !original.isDead()) {
                original.damage(damage, source);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOriginalDamage(EntityDamageEvent event) {
        VisualSession session = this.sessionsByOriginal.get(event.getEntity().getUniqueId());
        if (session == null || event.getFinalDamage() <= 0.0) {
            return;
        }
        LivingEntity proxy = session.proxy();
        if (!this.isValidLiving(proxy) || proxy.isDead()) {
            return;
        }
        proxy.playEffect(EntityEffect.HURT);
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> this.syncProxyHealth(session));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        VisualSession originalSession = this.sessionsByOriginal.get(event.getEntity().getUniqueId());
        if (originalSession != null) {
            this.destroySession(originalSession, true);
            return;
        }
        VisualSession session = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (session != null) {
            this.destroySession(session, false);
        }
    }

    private void scheduleSessionCreate(String mobType, LivingEntity original, long delay, int attempt) {
        this.debug("session create scheduled mobType=" + mobType + " attempt=" + attempt + " delayTicks=" + delay + " original=" + this.entityInfo(original));
        BukkitTask task = this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            this.pendingSessionTasks.remove(original.getUniqueId());
            this.createSession(mobType, original, attempt);
        }, Math.max(1L, delay));
        this.pendingSessionTasks.put(original.getUniqueId(), task);
    }

    private void createSession(String mobType, LivingEntity original, int attempt) {
        if (!this.isValidLiving(original)) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=original_invalid original=" + this.entityInfo(original));
            return;
        }
        if (original.isDead()) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=original_dead original=" + this.entityInfo(original));
            return;
        }
        if (this.sessionsByOriginal.containsKey(original.getUniqueId())) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=session_exists original=" + this.entityInfo(original));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=global_disabled original=" + this.entityInfo(original));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=mob_disabled original=" + this.entityInfo(original));
            return;
        }
        boolean requiresModel = this.plugin.getConfigManager().shouldOnlyUseBedrockVisualWhenModeled(mobType);
        boolean modeled = this.isModeledBoss(original, mobType);
        this.debug("session create model check mobType=" + mobType + " attempt=" + attempt + " requiresModel=" + requiresModel + " modeled=" + modeled + " original=" + this.entityInfo(original));
        if (requiresModel && !modeled) {
            int maxRetries = this.plugin.getConfigManager().getBedrockVisualModelDetectionRetries();
            if (attempt >= maxRetries && this.plugin.getConfigManager().shouldFallbackBedrockVisualWhenModelUndetected(mobType)) {
                this.debug("session create continuing with fallback mobType=" + mobType + " attempt=" + attempt + " maxRetries=" + maxRetries + " reason=model_undetected");
            } else {
                this.retrySessionCreateIfNeeded(mobType, original, attempt);
                return;
            }
        }
        EntityType proxyType = this.plugin.getConfigManager().getBedrockVisualEntityType(mobType);
        if (proxyType == null || !proxyType.isAlive() || !proxyType.isSpawnable()) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.bedrock-visual-invalid-entity",
                    LanguageManager.placeholders("mobtype", mobType, "entity", String.valueOf(proxyType))));
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=invalid_proxy_type type=" + proxyType);
            return;
        }
        Location spawnLocation = original.getLocation();
        World world = spawnLocation.getWorld();
        if (world == null) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=world_null original=" + this.entityInfo(original));
            return;
        }
        Entity spawned = world.spawnEntity(spawnLocation, proxyType);
        if (!(spawned instanceof LivingEntity proxy)) {
            this.debug("session create skipped mobType=" + mobType + " attempt=" + attempt + " reason=spawned_not_living proxy=" + this.entityInfo(spawned));
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
        this.debug("session created mobType=" + mobType + " proxyType=" + proxyType.name() + " original=" + this.entityInfo(original) + " proxy=" + this.entityInfo(proxy));
    }

    private void retrySessionCreateIfNeeded(String mobType, LivingEntity original, int attempt) {
        int maxRetries = this.plugin.getConfigManager().getBedrockVisualModelDetectionRetries();
        if (attempt >= maxRetries || !this.isValidLiving(original) || original.isDead()) {
            this.debug("session create stopped mobType=" + mobType + " attempt=" + attempt + " maxRetries=" + maxRetries + " reason=model_not_detected_or_original_invalid original=" + this.entityInfo(original));
            return;
        }
        this.debug("session create retry mobType=" + mobType + " nextAttempt=" + (attempt + 1) + " maxRetries=" + maxRetries + " original=" + this.entityInfo(original));
        this.scheduleSessionCreate(mobType, original, this.plugin.getConfigManager().getBedrockVisualModelDetectionRetryIntervalTicks(), attempt + 1);
    }

    private BukkitTask syncTask(String mobType, LivingEntity original, LivingEntity proxy) {
        int interval = this.plugin.getConfigManager().getBedrockVisualSyncIntervalTicks(mobType);
        return new BukkitRunnable() {
            private final int visibilityRefreshRuns = Math.max(1, (int)Math.ceil((double)BedrockVisualManager.this.plugin.getConfigManager().getBedrockVisualVisibilityRefreshIntervalTicks() / (double)interval));
            private int visibilityRefreshCounter;

            @Override
            public void run() {
                if (!BedrockVisualManager.this.isValidLiving(original) || original.isDead()
                        || !BedrockVisualManager.this.isValidLiving(proxy) || proxy.isDead()) {
                    BedrockVisualManager.this.debug("sync stopped reason=invalid_or_dead mobType=" + mobType
                            + " original=" + BedrockVisualManager.this.entityInfo(original)
                            + " proxy=" + BedrockVisualManager.this.entityInfo(proxy));
                    this.cancel();
                    BedrockVisualManager.this.unregisterBoss(original);
                    return;
                }
                Location originalLocation = original.getLocation();
                if (originalLocation.getWorld() == null || !originalLocation.getWorld().equals(proxy.getWorld())) {
                    BedrockVisualManager.this.debug("sync stopped reason=world_mismatch mobType=" + mobType
                            + " original=" + BedrockVisualManager.this.entityInfo(original)
                            + " proxy=" + BedrockVisualManager.this.entityInfo(proxy));
                    this.cancel();
                    BedrockVisualManager.this.unregisterBoss(original);
                    return;
                }
                proxy.teleport(originalLocation);
                proxy.setFireTicks(original.getFireTicks());
                BedrockVisualManager.this.syncProxyHealth(original, proxy);
                if (proxy.getMaximumNoDamageTicks() != original.getMaximumNoDamageTicks()) {
                    proxy.setMaximumNoDamageTicks(original.getMaximumNoDamageTicks());
                }
                if (++this.visibilityRefreshCounter >= this.visibilityRefreshRuns) {
                    this.visibilityRefreshCounter = 0;
                    VisualSession session = BedrockVisualManager.this.sessionsByOriginal.get(original.getUniqueId());
                    if (session != null) {
                        BedrockVisualManager.this.applyVisibilityForSession(session);
                    }
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
        this.clearEquipment(equipment);
        if (!this.plugin.getConfigManager().isBedrockVisualEquipmentEnabled(mobType)) {
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

    private void clearEquipment(EntityEquipment equipment) {
        equipment.setItemInMainHand(new ItemStack(Material.AIR));
        equipment.setItemInOffHand(new ItemStack(Material.AIR));
        equipment.setHelmet(new ItemStack(Material.AIR));
        equipment.setChestplate(new ItemStack(Material.AIR));
        equipment.setLeggings(new ItemStack(Material.AIR));
        equipment.setBoots(new ItemStack(Material.AIR));
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
        int processed = 0;
        int limit = this.plugin.getConfigManager().getMaxBedrockVisualViewersPerRefresh();
        double radius = this.plugin.getConfigManager().getBedrockVisualVisibilityRefreshRadius();
        double radiusSquared = radius * radius;
        Collection<Entity> modelParts = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (processed >= limit) {
                break;
            }
            if (!this.shouldRefreshVisibilityFor(player, session, radiusSquared)) {
                this.debug("visibility skipped reason=outside_refresh_scope player=" + this.playerInfo(player) + " mobType=" + session.mobType() + " original=" + this.entityInfo(session.original()));
                continue;
            }
            boolean bedrockPlayer = this.isBedrockPlayer(player);
            if (bedrockPlayer && modelParts == null) {
                modelParts = this.currentModelPartCandidates(session);
                for (Entity modelPart : modelParts) {
                    session.modelPartIds().add(modelPart.getUniqueId());
                }
            }
            this.applyVisibility(player, session, bedrockPlayer, modelParts == null ? Set.of() : modelParts);
            processed++;
        }
        this.pruneModelPartIds(session);
        this.debug("visibility refresh mobType=" + session.mobType() + " processed=" + processed + " limit=" + limit + " radius=" + radius);
    }

    private void applyVisibility(Player player) {
        for (VisualSession session : this.sessionsByOriginal.values()) {
            boolean bedrockPlayer = this.isBedrockPlayer(player);
            this.applyVisibility(player, session, bedrockPlayer, bedrockPlayer ? this.currentModelPartCandidates(session) : Set.of());
        }
    }

    private void applyVisibility(Player player, VisualSession session, boolean bedrockPlayer, Collection<Entity> modelParts) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!this.isValidLiving(session.original()) || !this.isValidLiving(session.proxy())) {
            this.debug("visibility skipped reason=session_invalid player=" + this.playerInfo(player) + " mobType=" + session.mobType()
                    + " original=" + this.entityInfo(session.original()) + " proxy=" + this.entityInfo(session.proxy()));
            return;
        }
        if (bedrockPlayer) {
            player.hideEntity((Plugin)this.plugin, session.original());
            player.showEntity((Plugin)this.plugin, session.proxy());
            int hiddenParts = 0;
            if (this.plugin.getConfigManager().shouldHideNearbyModelParts(session.mobType())) {
                for (Entity part : modelParts) {
                    session.modelPartIds().add(part.getUniqueId());
                    player.hideEntity((Plugin)this.plugin, part);
                    hiddenParts++;
                }
            }
            this.debug("visibility applied target=bedrock player=" + this.playerInfo(player) + " mobType=" + session.mobType()
                    + " hiddenOriginal=true shownProxy=true hiddenModelParts=" + hiddenParts);
            return;
        }
        player.showEntity((Plugin)this.plugin, session.original());
        player.hideEntity((Plugin)this.plugin, session.proxy());
        for (UUID modelPartId : session.modelPartIds()) {
            Entity modelPart = Bukkit.getEntity(modelPartId);
            if (modelPart != null) {
                player.showEntity((Plugin)this.plugin, modelPart);
            }
        }
        this.debug("visibility applied target=java player=" + this.playerInfo(player) + " mobType=" + session.mobType()
                + " shownOriginal=true hiddenProxy=true restoredModelParts=" + session.modelPartIds().size());
    }

    private Collection<Entity> currentModelPartCandidates(VisualSession session) {
        if (session == null || !this.plugin.getConfigManager().shouldHideNearbyModelParts(session.mobType())) {
            return Set.of();
        }
        return this.findModelPartCandidates(session.original(), session.mobType(),
                this.plugin.getConfigManager().getModelPartHideRadius(session.mobType()));
    }

    private boolean shouldRefreshVisibilityFor(Player player, VisualSession session, double radiusSquared) {
        if (player == null || !player.isOnline() || !this.isValidLiving(session.original())) {
            return false;
        }
        Location playerLocation = player.getLocation();
        Location bossLocation = session.original().getLocation();
        if (playerLocation.getWorld() == null || bossLocation.getWorld() == null || !playerLocation.getWorld().equals(bossLocation.getWorld())) {
            return false;
        }
        return playerLocation.distanceSquared(bossLocation) <= radiusSquared;
    }

    private void pruneModelPartIds(VisualSession session) {
        session.modelPartIds().removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid();
        });
    }

    private boolean isModeledBoss(LivingEntity original, String mobType) {
        if (this.plugin.getConfigManager().isBedrockVisualModelForced(mobType)) {
            this.debug("model detection mobType=" + mobType + " result=true reason=forced original=" + this.entityInfo(original));
            return true;
        }
        String mode = this.plugin.getConfigManager().getBedrockVisualModelMode(mobType);
        if ("false".equals(mode) || "none".equals(mode) || "vanilla".equals(mode)) {
            this.debug("model detection mobType=" + mobType + " result=false reason=mode_disabled mode=" + mode + " original=" + this.entityInfo(original));
            return false;
        }
        if (this.hasKnownModelMarker(original)) {
            this.debug("model detection mobType=" + mobType + " result=true reason=known_marker_on_original original=" + this.entityInfo(original));
            return true;
        }
        double radius = this.plugin.getConfigManager().getBedrockVisualModelCheckRadius(mobType);
        Collection<Entity> parts = this.findModelPartCandidates(original, mobType, radius);
        this.debug("model detection mobType=" + mobType + " result=" + !parts.isEmpty() + " reason=nearby_parts count=" + parts.size() + " radius=" + radius + " original=" + this.entityInfo(original));
        return !parts.isEmpty();
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
        this.destroySession(session, false);
    }

    private void destroySession(VisualSession session, boolean deathAnimation) {
        this.debug("destroy session mobType=" + session.mobType() + " deathAnimation=" + deathAnimation
                + " original=" + this.entityInfo(session.original()) + " proxy=" + this.entityInfo(session.proxy()));
        this.sessionsByOriginal.remove(session.original().getUniqueId());
        this.sessionsByProxy.remove(session.proxy().getUniqueId());
        if (session.syncTask() != null) {
            session.syncTask().cancel();
        }
        Entity proxy = session.proxy();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showEntity((Plugin)this.plugin, session.original());
            if (!this.isBedrockPlayer(player)) {
                player.hideEntity((Plugin)this.plugin, proxy);
            }
            for (UUID modelPartId : session.modelPartIds()) {
                Entity modelPart = Bukkit.getEntity(modelPartId);
                if (modelPart != null) {
                    player.showEntity((Plugin)this.plugin, modelPart);
                }
            }
        }
        if (!proxy.isValid()) {
            return;
        }
        if (deathAnimation && proxy instanceof LivingEntity livingProxy && !livingProxy.isDead()) {
            livingProxy.playEffect(EntityEffect.DEATH);
            this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                if (proxy.isValid()) {
                    proxy.remove();
                }
            }, 20L);
            return;
        }
        proxy.remove();
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
                        BedrockVisualManager.this.debug("cleanup task removing invalid session mobType=" + session.mobType()
                                + " original=" + BedrockVisualManager.this.entityInfo(session.original())
                                + " proxy=" + BedrockVisualManager.this.entityInfo(session.proxy()));
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

    private void syncProxyHealth(VisualSession session) {
        this.syncProxyHealth(session.original(), session.proxy());
    }

    private void syncProxyHealth(LivingEntity original, LivingEntity proxy) {
        if (!this.isValidLiving(original) || !this.isValidLiving(proxy) || original.isDead() || proxy.isDead()) {
            return;
        }
        double originalMax = Math.max(1.0, original.getMaxHealth());
        double proxyMax = Math.max(1.0, proxy.getMaxHealth());
        double ratio = Math.max(0.0, Math.min(1.0, original.getHealth() / originalMax));
        double proxyHealth = Math.max(0.1, Math.min(proxyMax, proxyMax * ratio));
        try {
            proxy.setHealth(proxyHealth);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean hasBedrockBridge() {
        return Bukkit.getPluginManager().getPlugin("floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                || Bukkit.getPluginManager().getPlugin("GeyserBukkit") != null
                || this.findClass("org.geysermc.floodgate.api.FloodgateApi") != null
                || this.findClass("org.geysermc.geyser.api.GeyserApi") != null;
    }

    private boolean isBedrockPlayer(Player player) {
        if (player == null) {
            return false;
        }
        if (this.isFloodgatePlayer(player)) {
            return true;
        }
        return this.isGeyserPlayer(player);
    }

    private boolean isFloodgatePlayer(Player player) {
        try {
            Class<?> apiClass = this.findClass("org.geysermc.floodgate.api.FloodgateApi");
            if (apiClass == null) {
                return false;
            }
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method method = api.getClass().getMethod("isFloodgatePlayer", UUID.class);
            Object result = method.invoke(api, player.getUniqueId());
            return result instanceof Boolean bool && bool;
        } catch (Throwable throwable) {
            this.debug("bedrock player check floodgate failed player=" + this.playerInfo(player) + " error=" + throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
            return false;
        }
    }

    private boolean isGeyserPlayer(Player player) {
        try {
            Class<?> apiClass = this.findClass("org.geysermc.geyser.api.GeyserApi");
            if (apiClass == null) {
                return false;
            }
            Object api = apiClass.getMethod("api").invoke(null);
            Method method = api.getClass().getMethod("connectionByUuid", UUID.class);
            Object connection = method.invoke(api, player.getUniqueId());
            return connection != null;
        } catch (Throwable throwable) {
            this.debug("bedrock player check geyser failed player=" + this.playerInfo(player) + " error=" + throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
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

    private void logBedrockBridgeMissingOnce() {
        if (this.bedrockBridgeUnavailableLogged) {
            return;
        }
        this.bedrockBridgeUnavailableLogged = true;
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.bedrock-visual-floodgate-missing"));
    }

    private void debug(String message) {
        this.plugin.debug("bedrock-visual", message);
    }

    private String playerInfo(Player player) {
        if (player == null) {
            return "null";
        }
        Location location = player.getLocation();
        return player.getName() + "/" + player.getUniqueId() + "@" + this.locationInfo(location);
    }

    private String entityInfo(Entity entity) {
        if (entity == null) {
            return "null";
        }
        return entity.getType().name() + "/" + entity.getUniqueId() + " valid=" + entity.isValid() + "@" + this.locationInfo(entity.getLocation());
    }

    private String locationInfo(Location location) {
        if (location == null) {
            return "null";
        }
        World world = location.getWorld();
        return (world == null ? "null-world" : world.getName())
                + ":" + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ());
    }

    private static String safeTagPart(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-.]", "_");
    }

    private record VisualSession(String mobType, LivingEntity original, LivingEntity proxy, BukkitTask syncTask, Set<UUID> modelPartIds) {
    }
}
