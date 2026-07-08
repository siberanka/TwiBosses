# TwiBosses

TwiBosses is a production-oriented MythicMobs boss tracking plugin for Spigot and Paper servers. It tracks boss damage, announces spawns and deaths, distributes rank-based rewards, supports scheduled and manual spawns, and exposes PlaceholderAPI placeholders for live boss state.

## Features

- Per-boss damage tracking with top damage rankings
- Entity UUID based boss sessions to prevent same-type boss reward desync
- Rank, participation, and last-hit reward support
- Boss-location item drops with optional player-private pickup ownership
- Vanilla, MythicMobs, ItemsAdder, Nexo, and CraftEngine reward item providers
- Damage-percentage based reward scaling for drop amounts
- Optional Floodgate-aware Bedrock vanilla boss visuals for modeled MythicMobs
- Reward command validation with allowlists, blocked fragments, command length limits, and player name checks
- Manual spawn cooldowns and command rate limits
- Respawn timers with persistent `data.yml` storage
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
- Optional: Floodgate for Bedrock vanilla boss visuals
- Optional: ModelEngine or BetterModel when using modeled MythicMobs

## Installation

1. Download `TwiBosses-1.0.4.jar` from the latest GitHub release.
2. Place the jar in your server `plugins` folder.
3. Start the server once to generate the configuration files.
4. Edit `plugins/TwiBosses/config.yml` and `plugins/TwiBosses/languages/*.yml`.
5. Run `/twiboss reload` or restart the server.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/twiboss help` | none | Shows the command list. |
| `/twiboss reload` | `twibosses.reload` | Reloads config and language files. |
| `/twiboss toggle` | `twibosses.toggle` | Enables or disables damage tracking. |
| `/twiboss spawn <mobtype>` | `twibosses.spawn` | Spawns a tracked boss at your location. |
| `/twiboss setspawn <mobtype>` | `twibosses.setspawn` | Saves the current location as a boss spawn point. |
| `/twiboss deletespawn <mobtype>` | `twibosses.deletespawn` | Deletes a saved boss spawn point. |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `twibosses.reload` | `op` | Allows `/twiboss reload`. |
| `twibosses.toggle` | `op` | Allows `/twiboss toggle`. |
| `twibosses.spawn` | `op` | Allows manual boss spawning. |
| `twibosses.setspawn` | `op` | Allows saving boss spawn points. |
| `twibosses.deletespawn` | `op` | Allows deleting boss spawn points. |
| `twibosses.update` | `op` | Shows update notifications on join. |

## Configuration

Core runtime settings are stored in:

```text
plugins/TwiBosses/config.yml
```

`config.yml` controls mechanics, security limits, tracked mobs, rewards, spawn rules, webhook endpoints, hologram provider selection, metrics, and update checks.

Rank rewards can use the legacy command list format or the structured format below. Structured rewards support console commands and physical drops at the boss death location.

```yaml
tracked-mobs:
  EliteSkeleton:
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

TwiBosses can hide configured modeled MythicMobs from Floodgate players and show a synchronized vanilla mob proxy instead. Java players keep seeing the original MythicMobs, ModelEngine, or BetterModel presentation.

This feature is disabled by default and requires Floodgate on the backend server:

```yaml
bedrock-visuals:
  enabled: true

tracked-mobs:
  EliteSkeleton:
    bedrock-visual:
      enabled: true
      vanilla-entity: SKELETON
      modeled: auto
      only-when-modeled: true
      forward-proxy-damage: true
      equipment:
        main-hand:
          provider: VANILLA
          item: BOW
          amount: 1
        helmet: AIR
```

`modeled: auto` checks common ModelEngine and BetterModel markers and nearby model part entities. Use `modeled: true` only when a specific boss must always use the Bedrock proxy. `only-when-modeled: true` keeps non-modeled MythicMobs unchanged for Bedrock players.

Bedrock hits on the proxy can be forwarded to the real MythicMob, so existing damage rankings, thresholds, and rewards continue to use the real boss session. Proxy hit forwarding is rate-limited and damage-capped:

```yaml
security:
  bedrock-visuals:
    max-proxy-hits-per-player-per-second: 8
    max-forwarded-damage: 1000.0
```

Equipment slots support vanilla items and the same custom item providers used by reward drops: `VANILLA`, `MYTHICMOBS`, `ITEMSADDER`, `NEXO`, and `CRAFTENGINE`.

The Bedrock proxy syncs position, yaw, pitch, fire state, configured equipment, visible health ratio, hurt animation, and death animation from the real boss session. Java players never receive the proxy view.

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

On startup and `/twiboss reload`, TwiBosses validates `config.yml` and bundled language files. Missing entries are added, invalid entries are removed, and the previous file is backed up to:

```text
plugins/TwiBosses/file-backups
```

Dynamic boss, reward, webhook, and per-mob language sections are preserved when they match the supported schema.

Plugin-related warnings, severe errors, uncaught plugin exceptions, and detailed stack traces are written to:

```text
plugins/TwiBosses/error.log
```

The file is size-limited and rotated with `error.log.1`, `error.log.2`, and so on:

```yaml
logging:
  error-log:
    enabled: true
    include-warnings: true
    max-size-kb: 1024
    max-archives: 3
```

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
- Reward drop providers and item ids are validated before resolution.
- Reward drops are capped per reward and per boss death.
- Bedrock visual proxy damage is rate-limited and capped before forwarding to the real boss.
- Drop stack amounts, item id length, pickup delay, chance, and scaling are clamped.
- Player names are validated before command dispatch.
- Boss death processing is guarded against duplicate death events.
- Damage tracking is keyed by entity UUID to avoid same-type boss desync.
- Manual commands and manual boss spawns are rate-limited.
- Webhook URLs must be HTTPS Discord webhook URLs.
- Webhook payloads are length-limited and sanitized.
- Plugin-related errors are captured in a rotating `error.log` with stack traces.
- Reload and shutdown paths cancel plugin tasks and clear runtime state.

## Build

```bash
mvn clean package
```

The production jar is generated at:

```text
target/TwiBosses-1.0.4.jar
```

## Support

Use the GitHub Issues tab for bug reports, feature requests, and configuration support. Please include your server version, Java version, TwiBosses version, MythicMobs version, relevant configuration snippets with secrets removed, and the latest console log section around the issue.
