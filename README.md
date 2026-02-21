# ShopkeepersStockControl

Per-player trade limits and shared global stock for [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) with real-time stock display in the villager trading UI.

## Features

- **Per-player trade limits** with independent cooldowns per player
- **Shared global stock** where all players draw from a single pool
- **Four cooldown modes** — daily, weekly, rolling, or none (manual restock)
- **Per-trade overrides** — each trade can override the shop's cooldown mode
- **Real-time stock display** — players see remaining trades in the villager UI via PacketEvents
- **Live stock updates** — shared stock changes push to all viewers instantly
- **Per-player purchase caps** for shared stock shops
- **Auto-purge** of trade data for inactive players
- **Configurable message delivery** — chat or action bar per message
- **PlaceholderAPI support** for scoreboards, holograms, and tab lists
- **Auto-cleanup** of orphaned shop data on config reload
- **SQLite persistence** with batch writes and dirty tracking

## Requirements

- **Server**: Paper 1.21+ (or any Paper fork)
- **Java**: 21+
- **Required plugins**:
  - [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) 2.24.0+
  - [PacketEvents](https://github.com/retrooper/packetevents) 2.10.0+
- **Optional plugins**:
  - [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI)

## Installation

1. Install **Shopkeepers** and **PacketEvents** on your server
2. Place `ShopkeepersStockControl.jar` in your `plugins/` folder
3. Restart your server
4. Configure shops in `plugins/ShopkeepersStockControl/trades.yml`

## Commands

All commands use `/ssc` (aliases: `/shopkeepersstock`, `/stockcontrol`). Shop arguments accept display names (tab-completable) or UUIDs.

| Command | Description | Permission |
|---------|-------------|------------|
| `/ssc reload` | Reload configuration | `shopkeepersstock.reload` |
| `/ssc reset <player> [shop] [trade]` | Reset a player's trades | `shopkeepersstock.reset` |
| `/ssc check <player> [shop] [trade]` | Check a player's trade data | `shopkeepersstock.check` |
| `/ssc restock <shop> [trade]` | Restock a shared-mode shop | `shopkeepersstock.restock` |
| `/ssc info <shop>` | Show shop configuration details | `shopkeepersstock.info` |
| `/ssc debug` | Toggle debug mode (session only) | `shopkeepersstock.debug` |
| `/ssc cleanup` | Manually trigger expired data cleanup | `shopkeepersstock.cleanup` |
| `/ssc help` | Show command help | `shopkeepersstock.help` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `shopkeepersstock.*` | All ShopkeepersStockControl permissions | op |
| `shopkeepersstock.reload` | Reload configuration | op |
| `shopkeepersstock.reset` | Reset player trade data | op |
| `shopkeepersstock.check` | Check player trade data | op |
| `shopkeepersstock.restock` | Restock shared-mode shops | op |
| `shopkeepersstock.info` | View shop configuration | op |
| `shopkeepersstock.debug` | Toggle debug mode | op |
| `shopkeepersstock.cleanup` | Trigger expired data cleanup | op |
| `shopkeepersstock.help` | View command help | op |

## Configuration

The plugin uses two config files:

- **`config.yml`** — General settings, messages, and display preferences
- **`trades.yml`** — Shop definitions with trade limits and cooldown settings

### Stock modes

| Mode | Behavior |
|------|----------|
| `per_player` (default) | Each player has independent trade limits |
| `shared` | All players share a single stock pool |

### Cooldown modes

Configurable at the shop level with per-trade overrides:

| Mode | Behavior |
|------|----------|
| `daily` | Resets at a fixed time every day |
| `weekly` | Resets at a fixed time on a specific day |
| `rolling` | Resets X seconds after the player's first trade |
| `none` | Never restocks — admin uses `/ssc restock` |

### Trade keys

The plugin uses **stable string keys** (not slot numbers) to identify trades. You can reorder trades in Shopkeepers without breaking player data — just update the `slot:` number in `trades.yml`.

See the config files for full details and examples.

## Placeholders

Requires [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI). Format: `%ssc_<action>_<shop>:<trade>%`

The `<shop>` can be a shop UUID or display name (case-insensitive).

| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%ssc_remaining_<shop>:<trade>%` | Remaining trades for the player | `3` |
| `%ssc_used_<shop>:<trade>%` | Used trades by the player | `1` |
| `%ssc_max_<shop>:<trade>%` | Effective max (per-player cap for shared, otherwise max-trades) | `4` |
| `%ssc_cooldown_<shop>:<trade>%` | Time until reset, "Ready", or "Available"/"Sold out" | `23h 45m` |
| `%ssc_resettime_<shop>:<trade>%` | Reset time display | `00:00` |
| `%ssc_globalmax_<shop>:<trade>%` | Total global stock (shared shops) | `100` |
| `%ssc_globalremaining_<shop>:<trade>%` | Remaining global stock (shared shops) | `73` |
