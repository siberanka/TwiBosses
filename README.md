# TwiBosses

TwiBosses is a production-oriented MythicMobs boss tracking plugin for Spigot and Paper servers. It tracks boss damage, announces spawns and deaths, distributes rank-based rewards, supports scheduled and manual spawns, and exposes PlaceholderAPI placeholders for live boss state.

## Features

- Per-boss damage tracking with top damage rankings
- Entity UUID based boss sessions to prevent same-type boss reward desync
- Rank, participation, last-hit, and permission-based bonus reward support
- Boss-location item drops with optional player-private pickup ownership
- Vanilla, MythicMobs, ItemsAdder, Nexo, and CraftEngine reward item providers
- Damage-percentage based reward scaling for drop amounts
- Optional Geyser/Floodgate-aware Bedrock vanilla boss visuals for modeled MythicMobs
- Reward command validation with allowlists, blocked fragments, command length limits, and player name checks
- Manual spawn cooldowns and command rate limits
- Respawn timers with persistent `data.yml` storage
- Admin killall tools and optional boss timeout removal
- Optional PlaceholderAPI, FancyHolograms, and DecentHolograms integrations
- Optional Discord webhook notifications with strict URL validation and payload limits
- GitHub Releases based update checks
- Reload-safe multilingual message system

## Requirements

- Java 17 or newer
- Spigot or Paper 1.18.2+
- MythicMobs
- Optional: PlaceholderAPI
- Optional: FancyHolograms or DecentHolograms
- Optional: ItemsAdder, Nexo, or CraftEngine for custom reward drops
- Optional: Floodgate or Geyser API for Bedrock vanilla boss visuals
- Optional: ModelEngine or BetterModel when using modeled MythicMobs

## Installation

1. Download `TwiBosses-1.0.14.jar` from the latest GitHub release.
2. Place the jar in your server `plugins` folder.
3. Start the server once to generate the configuration files.
4. Edit `plugins/TwiBosses/config.yml`, `plugins/TwiBosses/bosses.yml`, and `plugins/TwiBosses/languages/*.yml`.
5. Run `/twiboss reload` or restart the server.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/twiboss help` | none | Shows the command list. |
| `/twiboss reload` | `twibosses.reload` | Reloads config and language files. |
| `/twiboss toggle` | `twibosses.toggle` | Enables or disables damage tracking. |
| `/twiboss spawn <mobtype>` | `twibosses.spawn` | Spawns a tracked boss at your location. |
| `/twiboss spawn <mobtype> -w <world> -c <x> <y> <z>` | `twibosses.spawn` | Spawns a tracked boss at an explicit loaded location. |
| `/twiboss setspawn <mobtype>` | `twibosses.setspawn` | Saves the current location as a boss spawn point. |
| `/twiboss setspawn <mobtype> -w <world> -c <x> <y> <z>` | `twibosses.setspawn` | Saves an explicit loaded location as a boss spawn point. |
| `/twiboss deletespawn <mobtype>` | `twibosses.deletespawn` | Deletes a saved boss spawn point. |
| `/twiboss killall` | `twibosses.killall` | Kills all active tracked bosses. |
| `/twiboss killall <mobtype>` | `twibosses.killall` | Kills all active tracked bosses of one mob type. |
| `/twiboss killall -w <world>` | `twibosses.killall` | Kills all active tracked bosses in one world. |
| `/twiboss killall <mobtype> -w <world>` | `twibosses.killall` | Kills active tracked bosses matching both filters. |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `twibosses.reload` | `op` | Allows `/twiboss reload`. |
| `twibosses.toggle` | `op` | Allows `/twiboss toggle`. |
| `twibosses.spawn` | `op` | Allows manual boss spawning. |
| `twibosses.setspawn` | `op` | Allows saving boss spawn points. |
| `twibosses.deletespawn` | `op` | Allows deleting boss spawn points. |
| `twibosses.killall` | `op` | Allows killing active tracked bosses. |
| `twibosses.update` | `op` | Shows update notifications on join. |

## Configuration

Core runtime settings are stored in:

```text
plugins/TwiBosses/config.yml
```

`config.yml` is organized by ownership:

| Section | Purpose |
| --- | --- |
| `settings` | Language and simple plugin-level preferences. |
| `runtime` | Update checks, metrics, rotating plugin error logs, and optional diagnostic debug logs. |
| `security` | Rate limits, command validation, reward limits, and anti-abuse caps. |
| `integrations` | Holograms, Bedrock visual proxies, and Discord webhooks. |
| `display` | Titles, action bar, top damage display, sounds, and hologram toggles. |
| `bosses.yml` | Tracked MythicMobs, respawn, timeout, Bedrock visual, reward, and schedule settings. |

Boss definitions are stored in:

```text
plugins/TwiBosses/bosses.yml
```

Older `config.yml -> tracked-mobs` definitions are migrated into `bosses.yml` during startup or `/twiboss reload`. The previous files are backed up before repair.

Rank rewards can use the legacy command list format or the structured format below. Structured rewards support console commands and physical drops at the boss death location.

```yaml
tracked-mobs:
  EliteSkeleton:
    display-name: ""
    timeout-seconds: -1
    rewards:
      top-1:
        min-damage: 0
        min-percentage: 0
        commands:
          - "eco give {player} 5000"
        drops:
          - provider: VANILLA
            item: DIAMOND
            amount: 5
            chance: 1.0
            amount-per-percent: 0.0
            max-amount: 5
            private: true
            drop-at-boss: true
            pickup-delay-ticks: 20
            glow: false
```

Permission rewards are optional extra packages for players who have configured permission nodes and dealt valid boss damage. They use the same command validation, item providers, drop caps, private drop ownership, and boss-location checks as rank rewards.

```yaml
tracked-mobs:
  EliteSkeleton:
    permission-rewards:
      enabled: true
      stop-after-first-match: false
      rewards:
        vip:
          permission: twibosses.reward.vip
          min-damage: 200
          min-percentage: 0
          commands:
            - "eco give {player} 1000"
          drops:
            - provider: VANILLA
              item: GOLD_INGOT
              amount: 3
              chance: 1.0
              private: true
              drop-at-boss: true
        elite:
          permission: twibosses.reward.elite
          min-damage: 500
          min-percentage: 5
          commands:
            - "eco give {player} 2500"
          drops:
            - provider: CRAFTENGINE
              item: "server:elite_token"
              amount: 1
              chance: 1.0
              private: true
              drop-at-boss: true
```

`stop-after-first-match: false` lets one player receive every matching permission package, for example both VIP and Elite. Set it to `true` if your permission packages are tiered and only the first matching package should run.

`timeout-seconds` removes a live boss automatically if it is not killed in time. The default `-1` disables timeout removal. Timeout removal cleans runtime state and starts the normal respawn timer when respawn is enabled, but it does not run death rewards.

`display-name` controls the boss name used by TwiBosses in Bedrock visual proxy names, respawn holograms, webhooks, broadcasts, titles, reward messages, and `{mobname}` placeholders. Leave it empty to use the MythicMobs display name. If MythicMobs does not provide one, TwiBosses falls back to the bundled language file value under `mobs.<mobtype>.display-name`, then finally to the mob type id.

Supported drop providers:

| Provider | Item id example | Notes |
| --- | --- | --- |
| `VANILLA` | `DIAMOND` | Uses Bukkit materials. |
| `MYTHICMOBS` | `ExampleSword` | Uses MythicMobs item manager. |
| `ITEMSADDER` | `namespace:item_id` | Uses ItemsAdder `CustomStack` when installed. |
| `NEXO` | `item_id` | Uses Nexo `NexoItems` when installed. |
| `CRAFTENGINE` | `namespace:item_id` | Uses CraftEngine API reflectively when installed. |

`private: true` assigns the dropped item owner to the rewarded player through the Bukkit/Paper item owner API. This lets supported servers expose boss-location rewards only to the intended player while keeping the drop visible and natural.

## Bedrock Visuals

TwiBosses can hide configured modeled MythicMobs from Bedrock players and show a synchronized vanilla mob proxy instead. Java players keep seeing the original MythicMobs, ModelEngine, or BetterModel presentation.

This feature is enabled in the default configuration and requires Floodgate or a compatible Geyser API on the backend server:

```yaml
integrations:
  bedrock-visuals:
    enabled: true
    defaults:
      vanilla-entity: ZOMBIE
      modeled: auto
      only-when-modeled: true
      fallback-when-model-undetected: true
      spawn-delay-ticks: 10
      sync-interval-ticks: 2
      hide-nearby-model-parts: true
      forward-proxy-damage: true
      equipment:
        enabled: true
    limits:
      visibility-refresh-interval-ticks: 20
      visibility-refresh-radius: 128.0
      max-viewers-per-refresh: 160
      idle-deactivation-delay-ticks: 40
      model-detection-retries: 8
      model-detection-retry-interval-ticks: 10

tracked-mobs:
  EliteSkeleton:
    bedrock-visual:
      vanilla-entity: SKELETON
      equipment:
        main-hand:
          provider: VANILLA
          item: BOW
          amount: 1
        helmet: AIR
```

Global Bedrock behavior lives under `config.yml -> integrations.bedrock-visuals`. Per-boss `bosses.yml -> tracked-mobs.<mob>.bedrock-visual` only selects the vanilla entity type and optional equipment shown to Bedrock players.

`modeled: auto` checks common ModelEngine and BetterModel markers and nearby model part entities. Model checks and bounded retries start only when a Bedrock player is close enough to need the visual. `only-when-modeled: true` keeps non-modeled MythicMobs unchanged when model detection succeeds. `fallback-when-model-undetected: true` is a visibility fail-safe: if a configured boss still cannot be confirmed as modeled after the bounded retry window, TwiBosses creates the vanilla proxy anyway so Bedrock players are not left with an invisible boss.

Visibility is managed by one bounded interest scheduler and a world/cell spatial index. Players in worlds without tracked bosses skip Bedrock detection, Java players never enter boss proximity matching, and Bedrock players are compared only with bosses in neighboring cells. The index, candidate sets, and viewer buffers are reused between refreshes to avoid steady-state allocation pressure. A boss has no proxy and no high-frequency sync work until a nearby Bedrock player needs it. When the last viewer leaves, packet sync stops immediately and the idle proxy is removed after `idle-deactivation-delay-ticks`; `0` removes it on the next visibility refresh.

Active proxies share one scheduler. Position and rotation packets are emitted only when those values change, while health, fire state, and invulnerability timing are updated only when necessary. This still covers delayed model parts, teleports, joins, world changes, bosses spawned before players arrive, and players entering the area later.

Bedrock hits on the proxy can be forwarded to the real MythicMob, so existing damage rankings, thresholds, and rewards continue to use the real boss session. Proxy hit forwarding is rate-limited and damage-capped:

```yaml
integrations:
  bedrock-visuals:
    limits:
      max-proxy-hits-per-player-per-second: 8
      max-forwarded-damage: 1000.0
```

`config.yml -> integrations.bedrock-visuals.defaults.equipment.enabled: false` shows the Bedrock vanilla mob without configured held items or armor. When enabled, equipment slots in `bosses.yml` support vanilla items and the same custom item providers used by reward drops: `VANILLA`, `MYTHICMOBS`, `ITEMSADDER`, `NEXO`, and `CRAFTENGINE`.

The Bedrock proxy syncs position, yaw, pitch, fire state, configured equipment, visible health ratio, hurt animation, and death animation from the real boss session. Java players never receive the proxy view.

For Bedrock visibility troubleshooting, enable the rotating diagnostic log temporarily:

```yaml
runtime:
  logging:
    debug-log:
      enabled: true
      max-size-kb: 1024
      max-archives: 3
```

When enabled, TwiBosses writes `plugins/TwiBosses/debug.log` entries for bridge detection, Bedrock player checks, model detection attempts, fallback proxy creation, visibility hide/show decisions, model part hiding, proxy damage forwarding, and cleanup. Keep it disabled during normal gameplay. Disk writes use one bounded background writer, so diagnostics cannot create an unbounded task backlog or pause the server thread. Oversized records are truncated and saturated queues drop excess records with a localized summary.

Discord webhook endpoints are configured in one place under `integrations.webhooks.mobs`:

```yaml
integrations:
  webhooks:
    enabled: true
    limits:
      max-content-length: 512
      max-field-length: 1024
      connect-timeout-ms: 4000
      read-timeout-ms: 4000
    mobs:
      EliteSkeleton:
        spawn:
          enabled: false
          url: ""
          avatar-url: ""
          embed-thumbnail: ""
        death:
          enabled: false
          url: ""
          avatar-url: ""
          embed-thumbnail: ""
```

All editable text is stored in:

```text
plugins/TwiBosses/languages
```

Bundled languages:

- `tr.yml`
- `en.yml`
- `az.yml`
- `es.yml`

Select the active language:

```yaml
settings:
  language: tr
```

On startup and `/twiboss reload`, TwiBosses validates `config.yml`, `bosses.yml`, and bundled language files. Missing entries are added, invalid entries are removed, and the previous file is backed up to:

```text
plugins/TwiBosses/file-backups
```

Dynamic boss, reward, webhook, and per-mob language sections are preserved when they match the supported schema.

Spawn and setspawn command tab completion reads MythicMobs' loaded mob registry, while execution still requires the mob to be configured under `bosses.yml -> tracked-mobs` so rewards, timeouts, Bedrock visuals, and duplicate-spawn protections stay consistent.

Plugin-related warnings, severe errors, uncaught plugin exceptions, and detailed stack traces are written to:

```text
plugins/TwiBosses/error.log
```

The file is size-limited and rotated with `error.log.1`, `error.log.2`, and so on. Records are copied into a bounded background queue before disk access; queue admission never blocks the server thread, and shutdown drains accepted entries within a bounded wait:

```yaml
runtime:
  logging:
    error-log:
      enabled: true
      include-warnings: true
      max-size-kb: 1024
      max-archives: 3
```

Short-lived diagnostics can be written to:

```text
plugins/TwiBosses/debug.log
```

`debug.log` is disabled by default, size-limited, and rotated with the same archive style. It is intended for investigating visibility, reload, integration, or reward-flow issues without flooding the normal console log. Debug and error records have fixed size limits, bounded queue capacities, and overflow summaries to prevent logging storms from becoming memory or tick-time pressure.

## Placeholders

TwiBosses registers the `twibosses` PlaceholderAPI expansion when PlaceholderAPI is installed.

Common placeholders:

| Placeholder | Description |
| --- | --- |
| `%twibosses_respawn_<mobtype>%` | Shows the respawn countdown or ready state. |
| `%twibosses_<mobtype>_spawned%` | Shows whether the boss is currently spawned. |
| `%twibosses_<mobtype>_cooldown%` | Shows the remaining respawn cooldown. |
| `%twibosses_<mobtype>_needed%` | Shows kill requirements for boss spawning. |
| `%twibosses_top_<mobtype>_<position>%` | Shows the top damage entry for a boss. |

## Security

TwiBosses is designed for production servers where clients may be modified or hostile.

- Reward commands are checked before and after placeholder replacement.
- Unsafe reward command fragments are blocked.
- Reward command count and length are limited.
- Permission reward package count is capped per mob and permission nodes are validated before use.
- Reward drop providers and item ids are validated before resolution.
- Reward drops are capped per reward and per boss death.
- Active boss damage sessions and tracked players per boss are capped to prevent bot-driven memory growth.
- Bedrock visual proxy damage is rate-limited and capped before forwarding to the real boss.
- Bedrock visual visibility refresh is interval, radius, and viewer-count limited.
- Bedrock proxies remain dormant without nearby Bedrock viewers and active proxies share one scheduler.
- World/cell interest indexing prevents all-player/all-boss pair scans.
- Bedrock spatial cells, candidate sets, and viewer buffers are reused to reduce hot-path allocation pressure.
- Bedrock model detection retries are bounded to cover delayed ModelEngine/BetterModel parts without runaway tasks.
- Bedrock visual fallback proxy creation prevents invisible modeled bosses when model detection cannot confirm provider metadata.
- Diagnostic debug logging is opt-in and size-capped.
- Error and debug disk writes use bounded non-blocking queues with size-capped records and bounded shutdown draining.
- Drop stack amounts, item id length, pickup delay, chance, and scaling are clamped.
- Player names are validated before command dispatch.
- Boss death processing is guarded against duplicate death events.
- Damage tracking is keyed by entity UUID to avoid same-type boss desync.
- Cancelled, zero, NaN, and infinite damage events are ignored before ranking or rewards.
- Reward drop spawning validates world/chunk state and fails closed on provider/runtime exceptions.
- Killall and timeout logic only target configured tracked MythicMobs.
- Timeout removal clears boss sessions without executing reward logic.
- Manual commands and manual boss spawns are rate-limited.
- Webhook URLs must be HTTPS Discord webhook URLs.
- Webhook payloads are length-limited and sanitized.
- Plugin-related errors are captured in a rotating `error.log` with stack traces.
- Reload and shutdown paths cancel plugin tasks and clear runtime state.

## Build

```bash
mvn test
mvn clean package
```

The automated suite covers bounded logging backpressure, FIFO shutdown draining, worker-thread isolation, and Bedrock spatial/rotation boundary math.

The production jar is generated at:

```text
target/TwiBosses-1.0.14.jar
```

## Support

Use the GitHub Issues tab for bug reports, feature requests, and configuration support. Please include your server version, Java version, TwiBosses version, MythicMobs version, relevant configuration snippets with secrets removed, and the latest console log section around the issue.
