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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class BedrockVisualManager implements Listener {
    private static final String PROXY_TAG = "twibosses_bedrock_visual";
    private static final long HIT_WINDOW_MILLIS = 1000L;
    private static final long BEDROCK_CACHE_TRUE_MILLIS = 300_000L;
    private static final long BEDROCK_CACHE_FALSE_MILLIS = 5_000L;
    private static final double POSITION_EPSILON_SQUARED = 0.0001;
    private static final float ROTATION_EPSILON = 0.5F;
    private static final double HEALTH_EPSILON = 0.001;

    private final TwiBosses plugin;
    private final ItemResolver itemResolver;
    private final Map<UUID, VisualSession> sessionsByOriginal = new HashMap<>();
    private final Map<UUID, VisualSession> sessionsByProxy = new HashMap<>();
    private final Set<VisualSession> activeSessions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, Deque<Long>> proxyHitWindows = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingSessionTasks = new HashMap<>();
    private final Map<UUID, BedrockStatus> bedrockStatusCache = new HashMap<>();
    private final List<VisualSession> sessionSnapshot = new ArrayList<>();
    private final List<VisualSession> activeSessionSnapshot = new ArrayList<>();
    private final List<VisualSession> nearbySessionBuffer = new ArrayList<>();
    private final List<Player> bedrockPlayerBuffer = new ArrayList<>();
    private final Set<UUID> onlinePlayerIdBuffer = new HashSet<>();
    private final Set<UUID> leavingViewerBuffer = new HashSet<>();
    private final Set<UUID> joiningViewerBuffer = new HashSet<>();
    private final Set<UUID> newModelPartIdBuffer = new HashSet<>();
    private final Set<Entity> modelPartBuffer = new HashSet<>();
    private final Set<VisualSession> reconcileSessionBuffer = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final BossSpatialIndex spatialIndex = new BossSpatialIndex();
    private BukkitTask interestTask;
    private BukkitTask syncTask;
    private BukkitTask cleanupTask;
    private BukkitTask pendingInterestRefreshTask;
    private boolean bedrockBridgeUnavailableLogged;
    private boolean bedrockBridgeAccessInitialized;
    private Object floodgateApi;
    private Method floodgatePlayerMethod;
    private Object geyserApi;
    private Method geyserConnectionMethod;

    public BedrockVisualManager(TwiBosses plugin) {
        this.plugin = plugin;
        this.itemResolver = new ItemResolver(plugin);
        this.initializeBedrockBridgeAccess();
        this.removeOrphanProxies();
        this.startCleanupTask();
    }

    public void registerBoss(String mobType, Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)) {
            this.debug(() -> "register skipped mobType=" + mobType + " reason=not_living entity=" + this.entityInfo(entity));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()) {
            this.debug(() -> "register skipped mobType=" + mobType + " reason=global_disabled original=" + this.entityInfo(livingEntity));
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            this.debug(() -> "register skipped mobType=" + mobType + " reason=mob_disabled original=" + this.entityInfo(livingEntity));
            return;
        }
        if (!this.hasBedrockBridge()) {
            this.logBedrockBridgeMissingOnce();
            this.debug(() -> "register skipped mobType=" + mobType + " reason=bedrock_bridge_missing original=" + this.entityInfo(livingEntity));
            return;
        }
        UUID entityId = livingEntity.getUniqueId();
        if (this.sessionsByOriginal.containsKey(entityId) || this.pendingSessionTasks.containsKey(entityId)) {
            this.debug(() -> "register skipped mobType=" + mobType + " reason=already_registered_or_pending original=" + this.entityInfo(livingEntity));
            return;
        }
        long delay = this.plugin.getConfigManager().getBedrockVisualSpawnDelayTicks(mobType);
        this.debug(() -> "register scheduled mobType=" + mobType + " delayTicks=" + delay + " original=" + this.entityInfo(livingEntity));
        this.scheduleSessionCreate(mobType, livingEntity, delay);
    }

    public void unregisterBoss(Entity entity) {
        if (entity != null) {
            this.unregisterBoss(entity.getUniqueId());
        }
    }

    public void unregisterBoss(UUID entityId) {
        if (entityId == null) {
            return;
        }
        VisualSession session = this.sessionsByOriginal.get(entityId);
        if (session != null) {
            this.destroySession(session, false);
        }
        BukkitTask pendingTask = this.pendingSessionTasks.remove(entityId);
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        this.stopInterestTaskIfIdle();
    }

    public void unregisterBossByMobType(String mobType) {
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            if (session.mobType.equals(mobType)) {
                this.destroySession(session, false);
            }
        }
        this.stopInterestTaskIfIdle();
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
        this.debug(() -> "reload rebuilding sessions currentSessions=" + this.sessionsByOriginal.size() + " pending=" + this.pendingSessionTasks.size());
        this.clearRuntimeState();
        this.bedrockStatusCache.clear();
        if (this.cleanupTask == null) {
            this.startCleanupTask();
        }
        this.rebindActiveBosses();
        this.requestInterestRefresh(2L);
        this.debug(() -> "reload complete sessions=" + this.sessionsByOriginal.size() + " onlinePlayers=" + Bukkit.getOnlinePlayers().size());
    }

    public void cleanup() {
        this.debug(() -> "cleanup sessions=" + this.sessionsByOriginal.size() + " pending=" + this.pendingSessionTasks.size());
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        this.clearRuntimeState();
        this.bedrockStatusCache.clear();
    }

    private void clearRuntimeState() {
        this.cancelTask(this.pendingInterestRefreshTask);
        this.pendingInterestRefreshTask = null;
        this.cancelTask(this.interestTask);
        this.interestTask = null;
        this.cancelTask(this.syncTask);
        this.syncTask = null;
        for (VisualSession session : new ArrayList<>(this.sessionsByOriginal.values())) {
            this.destroySession(session, false);
        }
        for (BukkitTask pendingTask : this.pendingSessionTasks.values()) {
            pendingTask.cancel();
        }
        this.sessionsByOriginal.clear();
        this.sessionsByProxy.clear();
        this.activeSessions.clear();
        this.pendingSessionTasks.clear();
        this.proxyHitWindows.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.bedrockStatusCache.remove(playerId);
        this.hideActiveProxies(event.getPlayer());
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            this.bedrockStatusCache.remove(playerId);
            this.hideActiveProxiesFromJavaPlayer(player);
            this.requestInterestRefresh(1L);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.bedrockStatusCache.remove(playerId);
        this.proxyHitWindows.remove(playerId);
        boolean changed = false;
        for (VisualSession session : this.activeSessions) {
            changed |= session.viewerIds.remove(playerId);
        }
        if (changed) {
            this.stopSyncTaskIfIdle();
            this.requestInterestRefresh(1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        this.requestInterestRefresh(2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.requestInterestRefresh(2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProxyDamage(EntityDamageByEntityEvent event) {
        VisualSession session = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        if (!this.plugin.getConfigManager().shouldForwardBedrockProxyDamage(session.mobType)) {
            this.debug(() -> "proxy damage blocked reason=forwarding_disabled mobType=" + session.mobType + " proxy=" + this.entityInfo(session.proxy));
            return;
        }
        Entity source = event.getDamager();
        Player player = this.resolvePlayer(source);
        if (player == null || !session.viewerIds.contains(player.getUniqueId()) || !this.isBedrockPlayer(player)) {
            this.debug(() -> "proxy damage blocked reason=invalid_player source=" + this.entityInfo(source) + " proxy=" + this.entityInfo(session.proxy));
            return;
        }
        if (!this.allowProxyHit(player.getUniqueId())) {
            this.debug(() -> "proxy damage blocked reason=rate_limited player=" + this.playerInfo(player) + " proxy=" + this.entityInfo(session.proxy));
            return;
        }
        LivingEntity original = session.original;
        if (!this.isValidLiving(original) || original.isDead()) {
            this.debug(() -> "proxy damage blocked reason=original_invalid mobType=" + session.mobType + " original=" + this.entityInfo(original));
            this.destroySession(session, false);
            return;
        }
        double damage = Math.max(0.0, Math.min(event.getFinalDamage(), this.plugin.getConfigManager().getMaxForwardedBedrockProxyDamage()));
        if (damage <= 0.0) {
            return;
        }
        this.debug(() -> "proxy damage forwarded player=" + this.playerInfo(player) + " mobType=" + session.mobType + " damage=" + damage);
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.isValidLiving(original) && !original.isDead() && session.viewerIds.contains(player.getUniqueId())) {
                original.damage(damage, source);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOriginalDamage(EntityDamageEvent event) {
        VisualSession session = this.sessionsByOriginal.get(event.getEntity().getUniqueId());
        if (session == null || session.proxy == null || session.viewerIds.isEmpty() || event.getFinalDamage() <= 0.0) {
            return;
        }
        LivingEntity proxy = session.proxy;
        if (!this.isValidLiving(proxy) || proxy.isDead()) {
            return;
        }
        proxy.playEffect(EntityEffect.HURT);
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> this.syncProxyHealth(session.original, proxy));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        VisualSession originalSession = this.sessionsByOriginal.get(event.getEntity().getUniqueId());
        if (originalSession != null) {
            this.destroySession(originalSession, true);
            return;
        }
        VisualSession proxySession = this.sessionsByProxy.get(event.getEntity().getUniqueId());
        if (proxySession != null) {
            this.deactivateSession(proxySession, false);
            this.requestInterestRefresh(1L);
        }
    }

    private void scheduleSessionCreate(String mobType, LivingEntity original, long delay) {
        UUID originalId = original.getUniqueId();
        BukkitTask task = this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            this.pendingSessionTasks.remove(originalId);
            this.createSession(mobType, original);
        }, Math.max(1L, delay));
        BukkitTask previous = this.pendingSessionTasks.put(originalId, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void createSession(String mobType, LivingEntity original) {
        if (!this.isValidLiving(original) || original.isDead() || this.sessionsByOriginal.containsKey(original.getUniqueId())) {
            return;
        }
        if (!this.plugin.getConfigManager().isBedrockVisualsEnabled()
                || !this.plugin.getConfigManager().isBedrockVisualEnabled(mobType)) {
            return;
        }
        EntityType proxyType = this.plugin.getConfigManager().getBedrockVisualEntityType(mobType);
        if (proxyType == null || !proxyType.isAlive() || !proxyType.isSpawnable()) {
            this.plugin.getLogger().warning(this.plugin.getLanguageManager().raw(
                    "logs.bedrock-visual-invalid-entity",
                    LanguageManager.placeholders("mobtype", mobType, "entity", String.valueOf(proxyType))));
            return;
        }
        VisualSession session = new VisualSession(mobType, original, proxyType);
        this.sessionsByOriginal.put(original.getUniqueId(), session);
        this.startInterestTaskIfNeeded();
        this.requestInterestRefresh(1L);
        this.debug(() -> "session registered dormant mobType=" + mobType + " original=" + this.entityInfo(original));
    }

    private void startInterestTaskIfNeeded() {
        if (this.interestTask != null || this.sessionsByOriginal.isEmpty()) {
            return;
        }
        long interval = this.plugin.getConfigManager().getBedrockVisualVisibilityRefreshIntervalTicks();
        this.interestTask = this.plugin.getServer().getScheduler().runTaskTimer(
                (Plugin)this.plugin, this::refreshInterest, interval, interval);
    }

    private void stopInterestTaskIfIdle() {
        if (!this.sessionsByOriginal.isEmpty() || !this.pendingSessionTasks.isEmpty()) {
            return;
        }
        this.cancelTask(this.interestTask);
        this.interestTask = null;
        this.cancelTask(this.pendingInterestRefreshTask);
        this.pendingInterestRefreshTask = null;
    }

    private void requestInterestRefresh(long delayTicks) {
        if (this.sessionsByOriginal.isEmpty() || this.pendingInterestRefreshTask != null) {
            return;
        }
        this.pendingInterestRefreshTask = this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            this.pendingInterestRefreshTask = null;
            this.refreshInterest();
        }, Math.max(1L, delayTicks));
    }

    private void refreshInterest() {
        if (this.sessionsByOriginal.isEmpty()) {
            this.stopInterestTaskIfIdle();
            return;
        }
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            this.snapshotActiveSessions();
            for (VisualSession session : this.activeSessionSnapshot) {
                this.reconcileViewers(session, Set.of());
            }
            this.bedrockStatusCache.clear();
            this.stopSyncTaskIfIdle();
            return;
        }

        double radius = this.plugin.getConfigManager().getBedrockVisualVisibilityRefreshRadius();
        double radiusSquared = radius * radius;
        this.spatialIndex.reset(radius);
        this.sessionSnapshot.clear();
        this.sessionSnapshot.addAll(this.sessionsByOriginal.values());
        for (VisualSession session : this.sessionSnapshot) {
            session.candidateViewerIds.clear();
            if (!this.isValidLiving(session.original) || session.original.isDead()) {
                this.destroySession(session, false);
                continue;
            }
            this.spatialIndex.add(session);
        }

        this.bedrockPlayerBuffer.clear();
        this.onlinePlayerIdBuffer.clear();
        for (Player player : onlinePlayers) {
            this.onlinePlayerIdBuffer.add(player.getUniqueId());
            if (player.isOnline() && this.spatialIndex.hasWorld(player.getWorld().getUID()) && this.isBedrockPlayer(player)) {
                this.bedrockPlayerBuffer.add(player);
            }
        }
        this.bedrockStatusCache.keySet().removeIf(id -> !this.onlinePlayerIdBuffer.contains(id));
        if (this.bedrockPlayerBuffer.isEmpty()) {
            this.snapshotActiveSessions();
            for (VisualSession session : this.activeSessionSnapshot) {
                this.reconcileViewers(session, Set.of());
            }
            this.stopSyncTaskIfIdle();
            return;
        }

        int viewerLimit = this.plugin.getConfigManager().getMaxBedrockVisualViewersPerRefresh();
        int nearbySessionCount = 0;
        this.reconcileSessionBuffer.clear();
        for (Player player : this.bedrockPlayerBuffer) {
            Location playerLocation = player.getLocation();
            this.spatialIndex.collectNearby(playerLocation, this.nearbySessionBuffer);
            for (VisualSession session : this.nearbySessionBuffer) {
                if (!this.isWithinRadius(playerLocation, session.original.getLocation(), radiusSquared)) {
                    continue;
                }
                if (session.candidateViewerIds.size() < viewerLimit) {
                    if (session.candidateViewerIds.isEmpty()) {
                        nearbySessionCount++;
                    }
                    session.candidateViewerIds.add(player.getUniqueId());
                    this.reconcileSessionBuffer.add(session);
                }
            }
        }

        this.reconcileSessionBuffer.addAll(this.activeSessions);
        for (VisualSession session : this.reconcileSessionBuffer) {
            this.reconcileViewers(session, session.candidateViewerIds);
        }
        int finalNearbySessionCount = nearbySessionCount;
        this.debug(() -> "interest refresh registered=" + this.sessionsByOriginal.size()
                + " active=" + this.activeSessions.size() + " bedrockPlayers=" + this.bedrockPlayerBuffer.size()
                + " nearbySessions=" + finalNearbySessionCount);
    }

    private void reconcileViewers(VisualSession session, Set<UUID> newViewers) {
        if (!this.sessionsByOriginal.containsKey(session.original.getUniqueId())) {
            return;
        }
        this.leavingViewerBuffer.clear();
        this.leavingViewerBuffer.addAll(session.viewerIds);
        this.leavingViewerBuffer.removeAll(newViewers);
        for (UUID playerId : this.leavingViewerBuffer) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                this.restoreOriginalForViewer(player, session);
            }
        }
        session.viewerIds.removeAll(this.leavingViewerBuffer);

        if (newViewers.isEmpty()) {
            session.idleRefreshes++;
            int refreshInterval = this.plugin.getConfigManager().getBedrockVisualVisibilityRefreshIntervalTicks();
            int idleDelay = this.plugin.getConfigManager().getBedrockVisualIdleDeactivationDelayTicks();
            int allowedIdleRefreshes = idleDelay <= 0 ? 0 : (int)Math.ceil((double)idleDelay / (double)refreshInterval);
            if (session.proxy != null && session.idleRefreshes >= allowedIdleRefreshes) {
                this.deactivateSession(session, false);
            }
            return;
        }

        session.idleRefreshes = 0;
        if (!this.ensureModelEligible(session)) {
            return;
        }
        if (!this.activateSession(session)) {
            return;
        }
        this.syncSession(session);

        this.joiningViewerBuffer.clear();
        this.joiningViewerBuffer.addAll(newViewers);
        this.joiningViewerBuffer.removeAll(session.viewerIds);
        Collection<Entity> currentParts = this.currentModelPartCandidates(session);
        this.newModelPartIdBuffer.clear();
        for (Entity part : currentParts) {
            if (session.modelPartIds.add(part.getUniqueId())) {
                this.newModelPartIdBuffer.add(part.getUniqueId());
            }
        }
        this.pruneModelPartIds(session);

        for (UUID playerId : this.joiningViewerBuffer) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                this.applyBedrockVisibility(player, session, currentParts);
                session.viewerIds.add(playerId);
            }
        }
        if (!this.newModelPartIdBuffer.isEmpty()) {
            for (UUID playerId : session.viewerIds) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                for (UUID partId : this.newModelPartIdBuffer) {
                    Entity part = Bukkit.getEntity(partId);
                    if (part != null) {
                        player.hideEntity((Plugin)this.plugin, part);
                    }
                }
            }
        }
        this.startSyncTaskIfNeeded();
    }

    private boolean activateSession(VisualSession session) {
        if (session.proxy != null && this.isValidLiving(session.proxy) && !session.proxy.isDead()) {
            return true;
        }
        if (!this.isValidLiving(session.original) || session.original.isDead()) {
            this.destroySession(session, false);
            return false;
        }
        Location spawnLocation = session.original.getLocation();
        World world = spawnLocation.getWorld();
        if (world == null) {
            return false;
        }
        Entity spawned;
        try {
            spawned = world.spawnEntity(spawnLocation, session.proxyType);
        } catch (RuntimeException exception) {
            this.plugin.logError(this.plugin.getLanguageManager().raw(
                    "logs.bedrock-visual-create-failed",
                    LanguageManager.placeholders("mobtype", session.mobType)), exception);
            return false;
        }
        if (!(spawned instanceof LivingEntity proxy)) {
            spawned.remove();
            return false;
        }
        this.configureProxy(session.mobType, proxy);
        session.proxy = proxy;
        this.sessionsByProxy.put(proxy.getUniqueId(), session);
        this.activeSessions.add(session);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world) || !this.isBedrockPlayer(player)) {
                player.hideEntity((Plugin)this.plugin, proxy);
            }
        }
        this.plugin.getLogger().info(this.plugin.getLanguageManager().raw(
                "logs.bedrock-visual-created",
                LanguageManager.placeholders("mobtype", session.mobType, "entity", session.proxyType.name())));
        this.debug(() -> "session activated mobType=" + session.mobType + " original=" + this.entityInfo(session.original)
                + " proxy=" + this.entityInfo(proxy));
        return true;
    }

    private boolean ensureModelEligible(VisualSession session) {
        if (session.modelEligible) {
            return true;
        }
        if (session.modelRejected) {
            return false;
        }
        if (!this.plugin.getConfigManager().shouldOnlyUseBedrockVisualWhenModeled(session.mobType)) {
            session.modelEligible = true;
            return true;
        }
        long now = System.currentTimeMillis();
        if (now < session.nextModelCheckAtMillis) {
            return false;
        }
        if (this.isModeledBoss(session.original, session.mobType)) {
            session.modelEligible = true;
            this.debug(() -> "model detection accepted mobType=" + session.mobType + " attempts=" + session.modelDetectionAttempts);
            return true;
        }
        int maxRetries = this.plugin.getConfigManager().getBedrockVisualModelDetectionRetries();
        if (session.modelDetectionAttempts >= maxRetries) {
            if (this.plugin.getConfigManager().shouldFallbackBedrockVisualWhenModelUndetected(session.mobType)) {
                session.modelEligible = true;
                this.debug(() -> "model detection fallback mobType=" + session.mobType + " attempts=" + session.modelDetectionAttempts);
                return true;
            }
            session.modelRejected = true;
            this.debug(() -> "model detection rejected mobType=" + session.mobType + " attempts=" + session.modelDetectionAttempts);
            return false;
        }
        session.modelDetectionAttempts++;
        int retryTicks = this.plugin.getConfigManager().getBedrockVisualModelDetectionRetryIntervalTicks();
        session.nextModelCheckAtMillis = now + retryTicks * 50L;
        this.requestInterestRefresh(retryTicks);
        this.debug(() -> "model detection deferred mobType=" + session.mobType + " attempt=" + session.modelDetectionAttempts);
        return false;
    }

    private void deactivateSession(VisualSession session, boolean deathAnimation) {
        LivingEntity proxy = session.proxy;
        for (UUID playerId : new HashSet<>(session.viewerIds)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                this.restoreOriginalForViewer(player, session);
            }
        }
        session.viewerIds.clear();
        session.modelPartIds.clear();
        session.idleRefreshes = 0;
        session.proxy = null;
        this.activeSessions.remove(session);
        if (proxy != null) {
            this.sessionsByProxy.remove(proxy.getUniqueId());
            if (proxy.isValid()) {
                if (deathAnimation && !proxy.isDead()) {
                    proxy.playEffect(EntityEffect.DEATH);
                    this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                        if (proxy.isValid()) {
                            proxy.remove();
                        }
                    }, 20L);
                } else {
                    proxy.remove();
                }
            }
        }
        this.stopSyncTaskIfIdle();
        this.debug(() -> "session deactivated mobType=" + session.mobType + " deathAnimation=" + deathAnimation);
    }

    private void startSyncTaskIfNeeded() {
        if (this.syncTask != null || !this.hasActiveViewers()) {
            return;
        }
        long interval = this.plugin.getConfigManager().getBedrockVisualSyncIntervalTicks("");
        this.syncTask = this.plugin.getServer().getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            boolean hasViewers = false;
            this.snapshotActiveSessions();
            for (VisualSession session : this.activeSessionSnapshot) {
                if (session.viewerIds.isEmpty()) {
                    continue;
                }
                hasViewers = true;
                this.syncSession(session);
            }
            if (!hasViewers) {
                this.stopSyncTaskIfIdle();
            }
        }, interval, interval);
    }

    private void stopSyncTaskIfIdle() {
        if (this.hasActiveViewers()) {
            return;
        }
        this.cancelTask(this.syncTask);
        this.syncTask = null;
    }

    private boolean hasActiveViewers() {
        for (VisualSession session : this.activeSessions) {
            if (!session.viewerIds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void snapshotActiveSessions() {
        this.activeSessionSnapshot.clear();
        this.activeSessionSnapshot.addAll(this.activeSessions);
    }

    private void syncSession(VisualSession session) {
        LivingEntity original = session.original;
        LivingEntity proxy = session.proxy;
        if (!this.isValidLiving(original) || original.isDead()) {
            this.destroySession(session, false);
            return;
        }
        if (!this.isValidLiving(proxy) || proxy.isDead()) {
            this.deactivateSession(session, false);
            this.requestInterestRefresh(1L);
            return;
        }
        Location originalLocation = original.getLocation();
        Location proxyLocation = proxy.getLocation();
        if (originalLocation.getWorld() == null || proxyLocation.getWorld() == null
                || !originalLocation.getWorld().equals(proxyLocation.getWorld())) {
            this.deactivateSession(session, false);
            this.requestInterestRefresh(1L);
            return;
        }
        if (proxyLocation.distanceSquared(originalLocation) > POSITION_EPSILON_SQUARED
                || BedrockVisualMath.rotationDifference(proxyLocation.getYaw(), originalLocation.getYaw()) > ROTATION_EPSILON
                || Math.abs(proxyLocation.getPitch() - originalLocation.getPitch()) > ROTATION_EPSILON) {
            proxy.teleport(originalLocation);
        }
        if (Math.abs(proxy.getFireTicks() - original.getFireTicks()) > 1) {
            proxy.setFireTicks(original.getFireTicks());
        }
        this.syncProxyHealth(original, proxy);
        if (proxy.getMaximumNoDamageTicks() != original.getMaximumNoDamageTicks()) {
            proxy.setMaximumNoDamageTicks(original.getMaximumNoDamageTicks());
        }
    }

    private void configureProxy(String mobType, LivingEntity proxy) {
        proxy.addScoreboardTag(PROXY_TAG);
        proxy.addScoreboardTag("twibosses_bedrock_visual_" + safeTagPart(mobType));
        proxy.setRemoveWhenFarAway(false);
        proxy.setPersistent(false);
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
        ItemStack air = new ItemStack(Material.AIR);
        equipment.setItemInMainHand(air);
        equipment.setItemInOffHand(air);
        equipment.setHelmet(air);
        equipment.setChestplate(air);
        equipment.setLeggings(air);
        equipment.setBoots(air);
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

    private void applyBedrockVisibility(Player player, VisualSession session, Collection<Entity> modelParts) {
        if (!player.isOnline() || session.proxy == null) {
            return;
        }
        player.hideEntity((Plugin)this.plugin, session.original);
        player.showEntity((Plugin)this.plugin, session.proxy);
        if (this.plugin.getConfigManager().shouldHideNearbyModelParts(session.mobType)) {
            for (Entity part : modelParts) {
                player.hideEntity((Plugin)this.plugin, part);
            }
        }
        this.debug(() -> "visibility activated player=" + this.playerInfo(player) + " mobType=" + session.mobType);
    }

    private void restoreOriginalForViewer(Player player, VisualSession session) {
        player.showEntity((Plugin)this.plugin, session.original);
        if (session.proxy != null) {
            player.hideEntity((Plugin)this.plugin, session.proxy);
        }
        for (UUID modelPartId : session.modelPartIds) {
            Entity modelPart = Bukkit.getEntity(modelPartId);
            if (modelPart != null) {
                player.showEntity((Plugin)this.plugin, modelPart);
            }
        }
        this.debug(() -> "visibility released player=" + this.playerInfo(player) + " mobType=" + session.mobType);
    }

    private void hideActiveProxiesFromJavaPlayer(Player player) {
        if (player == null || !player.isOnline() || this.isBedrockPlayer(player)) {
            return;
        }
        for (VisualSession session : this.activeSessions) {
            if (session.proxy != null) {
                player.hideEntity((Plugin)this.plugin, session.proxy);
            }
        }
    }

    private void hideActiveProxies(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        for (VisualSession session : this.activeSessions) {
            if (session.proxy != null) {
                player.hideEntity((Plugin)this.plugin, session.proxy);
            }
        }
    }

    private Collection<Entity> currentModelPartCandidates(VisualSession session) {
        this.modelPartBuffer.clear();
        if (!this.plugin.getConfigManager().shouldHideNearbyModelParts(session.mobType)) {
            return this.modelPartBuffer;
        }
        return this.findModelPartCandidates(session.original, session.mobType,
                this.plugin.getConfigManager().getModelPartHideRadius(session.mobType), this.modelPartBuffer);
    }

    private void pruneModelPartIds(VisualSession session) {
        session.modelPartIds.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid();
        });
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
        return !this.findModelPartCandidates(original, mobType, radius, this.modelPartBuffer).isEmpty();
    }

    private Collection<Entity> findModelPartCandidates(LivingEntity original, String mobType, double radius, Set<Entity> candidates) {
        candidates.clear();
        if (!this.isValidLiving(original) || radius <= 0.0) {
            return candidates;
        }
        String lowerMobType = mobType.toLowerCase(Locale.ROOT);
        for (Entity passenger : original.getPassengers()) {
            if (!(passenger instanceof Player) && this.looksLikeModelPart(passenger, lowerMobType)) {
                candidates.add(passenger);
            }
        }
        for (Entity nearby : original.getNearbyEntities(radius, radius, radius)) {
            if (!nearby.equals(original) && !(nearby instanceof Player) && this.looksLikeModelPart(nearby, lowerMobType)) {
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

    private void destroySession(VisualSession session, boolean deathAnimation) {
        if (session == null) {
            return;
        }
        this.sessionsByOriginal.remove(session.original.getUniqueId());
        this.deactivateSession(session, deathAnimation);
        BukkitTask pendingTask = this.pendingSessionTasks.remove(session.original.getUniqueId());
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        this.stopInterestTaskIfIdle();
    }

    private void startCleanupTask() {
        this.cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (VisualSession session : new ArrayList<>(BedrockVisualManager.this.sessionsByOriginal.values())) {
                    if (!BedrockVisualManager.this.isValidLiving(session.original) || session.original.isDead()) {
                        BedrockVisualManager.this.destroySession(session, false);
                    } else if (session.proxy != null
                            && (!BedrockVisualManager.this.isValidLiving(session.proxy) || session.proxy.isDead())) {
                        BedrockVisualManager.this.deactivateSession(session, false);
                        BedrockVisualManager.this.requestInterestRefresh(1L);
                    }
                }
            }
        }.runTaskTimer((Plugin)this.plugin, 100L, 100L);
    }

    private void rebindActiveBosses() {
        Set<String> trackedMobs = this.plugin.getConfigManager().getTrackedMobs();
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                Optional<ActiveMob> activeMob = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
                if (activeMob.isPresent() && trackedMobs.contains(activeMob.get().getMobType())) {
                    this.registerBoss(activeMob.get().getMobType(), entity);
                }
            }
        }
    }

    private void removeOrphanProxies() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity.getScoreboardTags().contains(PROXY_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private boolean isValidLiving(Entity entity) {
        return entity instanceof LivingEntity && entity.isValid() && entity.getWorld() != null;
    }

    private void syncProxyHealth(LivingEntity original, LivingEntity proxy) {
        if (!this.isValidLiving(original) || !this.isValidLiving(proxy) || original.isDead() || proxy.isDead()) {
            return;
        }
        double originalMax = Math.max(1.0, original.getMaxHealth());
        double proxyMax = Math.max(1.0, proxy.getMaxHealth());
        double ratio = Math.max(0.0, Math.min(1.0, original.getHealth() / originalMax));
        double proxyHealth = Math.max(0.1, Math.min(proxyMax, proxyMax * ratio));
        if (Math.abs(proxy.getHealth() - proxyHealth) <= HEALTH_EPSILON) {
            return;
        }
        try {
            proxy.setHealth(proxyHealth);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean hasBedrockBridge() {
        this.initializeBedrockBridgeAccess();
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
        long now = System.currentTimeMillis();
        BedrockStatus cached = this.bedrockStatusCache.get(player.getUniqueId());
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.bedrock;
        }
        boolean bedrock = this.isFloodgatePlayer(player) || this.isGeyserPlayer(player);
        long lifetime = bedrock ? BEDROCK_CACHE_TRUE_MILLIS : BEDROCK_CACHE_FALSE_MILLIS;
        this.bedrockStatusCache.put(player.getUniqueId(), new BedrockStatus(bedrock, now + lifetime));
        return bedrock;
    }

    private boolean isFloodgatePlayer(Player player) {
        this.initializeBedrockBridgeAccess();
        if (this.floodgateApi == null || this.floodgatePlayerMethod == null) {
            return false;
        }
        try {
            Object result = this.floodgatePlayerMethod.invoke(this.floodgateApi, player.getUniqueId());
            return result instanceof Boolean bool && bool;
        } catch (Throwable throwable) {
            this.debug(() -> "bedrock player check floodgate failed player=" + this.playerInfo(player)
                    + " error=" + throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
            return false;
        }
    }

    private boolean isGeyserPlayer(Player player) {
        this.initializeBedrockBridgeAccess();
        if (this.geyserApi == null || this.geyserConnectionMethod == null) {
            return false;
        }
        try {
            return this.geyserConnectionMethod.invoke(this.geyserApi, player.getUniqueId()) != null;
        } catch (Throwable throwable) {
            this.debug(() -> "bedrock player check geyser failed player=" + this.playerInfo(player)
                    + " error=" + throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
            return false;
        }
    }

    private void initializeBedrockBridgeAccess() {
        if (this.bedrockBridgeAccessInitialized) {
            return;
        }
        this.bedrockBridgeAccessInitialized = true;
        try {
            Class<?> apiClass = this.findClass("org.geysermc.floodgate.api.FloodgateApi");
            if (apiClass != null) {
                this.floodgateApi = apiClass.getMethod("getInstance").invoke(null);
                this.floodgatePlayerMethod = this.floodgateApi.getClass().getMethod("isFloodgatePlayer", UUID.class);
            }
        } catch (Throwable throwable) {
            this.floodgateApi = null;
            this.floodgatePlayerMethod = null;
            this.debug(() -> "floodgate bridge initialization failed error=" + throwable.getClass().getSimpleName()
                    + ":" + throwable.getMessage());
        }
        try {
            Class<?> apiClass = this.findClass("org.geysermc.geyser.api.GeyserApi");
            if (apiClass != null) {
                this.geyserApi = apiClass.getMethod("api").invoke(null);
                this.geyserConnectionMethod = this.geyserApi.getClass().getMethod("connectionByUuid", UUID.class);
            }
        } catch (Throwable throwable) {
            this.geyserApi = null;
            this.geyserConnectionMethod = null;
            this.debug(() -> "geyser bridge initialization failed error=" + throwable.getClass().getSimpleName()
                    + ":" + throwable.getMessage());
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
        if (!this.bedrockBridgeUnavailableLogged) {
            this.bedrockBridgeUnavailableLogged = true;
            this.plugin.getLogger().info(this.plugin.getLanguageManager().raw("logs.bedrock-visual-floodgate-missing"));
        }
    }

    private boolean isWithinRadius(Location first, Location second, double radiusSquared) {
        return first.getWorld() != null && second.getWorld() != null && first.getWorld().equals(second.getWorld())
                && BedrockVisualMath.withinSquaredDistance(
                        first.getX(), first.getY(), first.getZ(),
                        second.getX(), second.getY(), second.getZ(), radiusSquared);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void debug(String message) {
        DebugLogManager manager = this.plugin.getDebugLogManager();
        if (manager != null && manager.isEnabled()) {
            manager.log("bedrock-visual", message);
        }
    }

    private void debug(Supplier<String> message) {
        DebugLogManager manager = this.plugin.getDebugLogManager();
        if (manager != null && manager.isEnabled()) {
            manager.log("bedrock-visual", message.get());
        }
    }

    private String playerInfo(Player player) {
        if (player == null) {
            return "null";
        }
        return player.getName() + "/" + player.getUniqueId() + "@" + this.locationInfo(player.getLocation());
    }

    private String entityInfo(Entity entity) {
        if (entity == null) {
            return "null";
        }
        return entity.getType().name() + "/" + entity.getUniqueId() + " valid=" + entity.isValid()
                + "@" + this.locationInfo(entity.getLocation());
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

    private static final class BossSpatialIndex {
        private final Map<UUID, Map<Long, List<VisualSession>>> cellsByWorld = new HashMap<>();
        private final Deque<Map<Long, List<VisualSession>>> worldMapPool = new ArrayDeque<>();
        private final Deque<List<VisualSession>> cellListPool = new ArrayDeque<>();
        private double cellSize = 1.0;

        private void reset(double cellSize) {
            this.cellSize = Math.max(1.0, cellSize);
            for (Map<Long, List<VisualSession>> worldCells : this.cellsByWorld.values()) {
                for (List<VisualSession> cellSessions : worldCells.values()) {
                    cellSessions.clear();
                    this.cellListPool.addLast(cellSessions);
                }
                worldCells.clear();
                this.worldMapPool.addLast(worldCells);
            }
            this.cellsByWorld.clear();
        }

        private void add(VisualSession session) {
            Location location = session.original.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            int cellX = this.cell(location.getX());
            int cellZ = this.cell(location.getZ());
            Map<Long, List<VisualSession>> worldCells = this.cellsByWorld.get(world.getUID());
            if (worldCells == null) {
                worldCells = this.worldMapPool.pollFirst();
                if (worldCells == null) {
                    worldCells = new HashMap<>();
                }
                this.cellsByWorld.put(world.getUID(), worldCells);
            }
            long key = BedrockVisualMath.cellKey(cellX, cellZ);
            List<VisualSession> cellSessions = worldCells.get(key);
            if (cellSessions == null) {
                cellSessions = this.cellListPool.pollFirst();
                if (cellSessions == null) {
                    cellSessions = new ArrayList<>();
                }
                worldCells.put(key, cellSessions);
            }
            cellSessions.add(session);
        }

        private void collectNearby(Location location, List<VisualSession> destination) {
            destination.clear();
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            Map<Long, List<VisualSession>> cells = this.cellsByWorld.get(world.getUID());
            if (cells == null || cells.isEmpty()) {
                return;
            }
            int centerX = this.cell(location.getX());
            int centerZ = this.cell(location.getZ());
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    List<VisualSession> cellSessions = cells.get(BedrockVisualMath.cellKey(x, z));
                    if (cellSessions != null) {
                        destination.addAll(cellSessions);
                    }
                }
            }
        }

        private boolean hasWorld(UUID worldId) {
            return this.cellsByWorld.containsKey(worldId);
        }

        private int cell(double coordinate) {
            return BedrockVisualMath.cell(coordinate, this.cellSize);
        }
    }

    private static final class VisualSession {
        private final String mobType;
        private final LivingEntity original;
        private final EntityType proxyType;
        private final Set<UUID> modelPartIds = new HashSet<>();
        private final Set<UUID> viewerIds = new HashSet<>();
        private final Set<UUID> candidateViewerIds = new HashSet<>();
        private LivingEntity proxy;
        private int idleRefreshes;
        private int modelDetectionAttempts;
        private long nextModelCheckAtMillis;
        private boolean modelEligible;
        private boolean modelRejected;

        private VisualSession(String mobType, LivingEntity original, EntityType proxyType) {
            this.mobType = mobType;
            this.original = original;
            this.proxyType = proxyType;
        }
    }

    private static final class BedrockStatus {
        private final boolean bedrock;
        private final long expiresAtMillis;

        private BedrockStatus(boolean bedrock, long expiresAtMillis) {
            this.bedrock = bedrock;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
