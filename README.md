# ShopkeepersStockControl

A powerful Paper plugin that adds **per-player trade limits** and **shared global stock** to [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) with accurate real-time stock display in the vanilla villager trading UI.

[![bStats](https://img.shields.io/bstats/servers/27827)](https://bstats.org/plugin/bukkit/ShopkeepersStockControl/27827)
[![License](https://img.shields.io/github/license/LoralonMC/ShopkeepersStockControl)](LICENSE)

## Features

- **Per-Player Trade Limits** - Each player has their own independent trade limits
- **Shared Global Stock** - All players draw from a single stock pool (perfect for limited-edition event items)
- **Four Cooldown Modes** - Daily, weekly, rolling, or none (manual restock only)
- **Per-Trade Overrides** - Each trade can override the shop's cooldown mode (e.g., most trades reset daily, but a rare trade resets weekly)
- **Real-Time Stock Display** - Players see their remaining trades directly in the villager UI via PacketEvents
- **Live Stock Updates** - When shared stock changes, all viewers see updates instantly (debounced for scalability)
- **Per-Player Purchase Caps** - Limit how many items each player can buy from a shared pool
- **Auto-Purge** - Automatically remove trade data for players inactive beyond a configurable threshold
- **Action Bar Messages** - Trade feedback can appear on the action bar instead of chat
- **PlaceholderAPI Support** - Use trade data in scoreboards, holograms, tab lists, etc.
- **Auto-Cleanup** - Orphaned shop data is automatically removed on config reload
- **SQLite Database** - Persistent data storage with batch writes and prepared statements
- **MiniMessage Support** - Modern text formatting with gradients and colors
- **bStats Metrics** - Anonymous usage statistics for development priorities

## Screenshots

When a player opens a Shopkeeper, they see **their personal stock** for each trade:
- Trades show exact remaining count (e.g., "2/4 remaining")
- Depleted trades automatically cross out when limit reached
- Stock refreshes on cooldown expiry

## Requirements

- **Server**: Paper 1.21+ (or any Paper fork)
- **Required Dependencies**:
  - [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) 2.24.0+
  - [PacketEvents](https://github.com/retrooper/packetevents) 2.10.0+ (as a plugin on your server)
- **Optional Dependencies**:
  - [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) - for external placeholder support
- **Java**: 21+

## Installation

1. Download the latest `ShopkeepersStockControl.jar` from [Releases](https://github.com/LoralonMC/ShopkeepersStockControl/releases)
2. Install **Shopkeepers** and **PacketEvents** plugins on your server
3. Place `ShopkeepersStockControl.jar` in your `plugins/` folder
4. Restart your server
5. Configure `plugins/ShopkeepersStockControl/trades.yml` (see Configuration below)

## Configuration

### Finding Shopkeeper UUIDs

You need the UUID of each Shopkeeper you want to track. Two methods:

**Method 1: Via saves.yml**
```bash
# Look in plugins/Shopkeepers/saves.yml
# Find your shopkeeper by name, copy its UUID
```

**Method 2: Via command**
```
/shopkeeper
# Select the Shopkeeper, use the command to view its details
```

### Stock Modes

| Mode | Behavior | Use Case |
|------|----------|----------|
| `per_player` (default) | Each player has independent trade limits | Daily shops, personal cooldowns |
| `shared` | All players share a single stock pool | Limited-edition event items, server-wide stock |

### Cooldown Modes

The plugin supports four cooldown modes, configurable at the shop level with per-trade overrides:

| Mode | Behavior | Example |
|------|----------|---------|
| `daily` | Resets at a fixed time every day | All trades reset at midnight |
| `weekly` | Resets at a fixed time on a specific day | Rare trades reset Monday at 00:00 |
| `rolling` | Resets X seconds after the player's first trade | 24h personal cooldown per player |
| `none` | Never restocks automatically | Admin restocks manually with `/ssc restock` |

### Example trades.yml

```yaml
shops:
  # Daily reset shop - all trades reset at midnight
  e8f4a3c2-1234-5678-9abc-def012345678:
    name: "Miner"
    enabled: true
    cooldown_mode: daily
    reset_time: "00:00"
    trades:
      diamond_trade:
        slot: 0
        max_trades: 4
      netherite_trade:
        slot: 1
        max_trades: 1
        cooldown_mode: weekly    # Override: this rare trade resets weekly
        reset_day: "monday"

  # Rolling cooldown shop - each trade resets 24h after first use
  a1b2c3d4-5678-9abc-def0-123456789abc:
    name: "Farmer"
    enabled: true
    cooldown_mode: rolling
    trades:
      wheat_trade:
        slot: 0
        max_trades: 5
        cooldown: 86400          # 24 hours (required for rolling mode)
      carrot_trade:
        slot: 1
        max_trades: 5
        cooldown: 86400

  # Shared stock - all players draw from the same pool
  b2c3d4e5-6789-abcd-ef01-23456789abcd:
    name: "Christmas Shop"
    enabled: true
    stock_mode: shared
    cooldown_mode: none            # Never restocks (admin uses /ssc restock)
    max_per_player: 5              # Each player can buy at most 5 per trade
    trades:
      santa_hat:
        slot: 0
        max_trades: 100            # 100 total for ALL players combined
      candy_cane:
        slot: 1
        max_trades: 200
        max_per_player: 10         # Override: allow 10 per player for this trade
```

**Key points:**
- `cooldown_mode`, `reset_time`, and `reset_day` can be set at the shop level (defaults for all trades) or per-trade (overrides)
- `cooldown` (seconds) is **required** for `rolling` mode, **ignored** for `daily`/`weekly`/`none`
- If a trade doesn't specify `cooldown_mode`, it inherits from the shop
- If the shop doesn't specify `cooldown_mode`, it defaults to `rolling`
- `stock_mode: shared` enables global stock; `max_per_player` caps individual purchases

#### Rolling Cooldown Reference

| Duration | Seconds |
|----------|---------|
| 1 hour   | 3600    |
| 6 hours  | 21600   |
| 12 hours | 43200   |
| 24 hours | 86400   |
| 7 days   | 604800  |
| 30 days  | 2592000 |

### Auto-Purge

Remove trade data for players who haven't logged in within a configurable number of days. Set in `config.yml`:

```yaml
# Set to 0 to disable
purge_inactive_days: 90
```

Purge runs automatically on startup (after 5 seconds) and then every 24 hours.

### Message Customization

Edit `config.yml` to customize player messages using [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format:

```yaml
messages:
  trade_limit_reached: "<red>You've reached your limit! <gray>Cooldown: <yellow><time_remaining><gray> (Resets at <reset_time>)"
  trades_remaining: "<gray>Trades remaining: <green><remaining><gray>/<green><max>"
  cooldown_active: "<red>Cooldown active. <yellow><time_remaining><gray> (Resets at <reset_time>)"
```

**Available placeholders:**
- `<time_remaining>` - Time until cooldown expires (e.g., "23h 45m")
- `<reset_time>` - Reset time display (e.g., "00:00" for daily, "Monday 00:00" for weekly, "Never" for none)
- `<remaining>` - Trades remaining for the player
- `<max>` - Maximum trades allowed

**Tip:** Set any message to `""` (empty string) to disable it.

### Action Bar Messages

Control where each message type appears using the `message_display` section in `config.yml`:

```yaml
message_display:
  trade_limit_reached: chat        # "chat" or "action_bar"
  trades_remaining: action_bar
  cooldown_active: action_bar
```

## PlaceholderAPI

If [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) is installed, the following placeholders are available for use in scoreboards, holograms, tab lists, etc.

**Format:** `%ssc_<action>_<shop>:<trade>%`

The `<shop>` identifier can be either the shop ID (from trades.yml) or the display name (case-insensitive).

| Placeholder | Description | Example Output |
|-------------|-------------|----------------|
| `%ssc_remaining_Miner:diamond_trade%` | Remaining trades for the player | `3` |
| `%ssc_used_Miner:diamond_trade%` | Used trades by the player | `1` |
| `%ssc_max_Miner:diamond_trade%` | Effective max (per-player cap for shared, otherwise max_trades) | `4` |
| `%ssc_cooldown_Miner:diamond_trade%` | Time until reset, "Ready", or "Available"/"Sold out" (none mode) | `23h 45m` |
| `%ssc_resettime_Miner:diamond_trade%` | Reset time display | `00:00` |
| `%ssc_globalmax_Miner:diamond_trade%` | Total global stock for shared shops | `100` |
| `%ssc_globalremaining_Miner:diamond_trade%` | Remaining global stock for shared shops | `73` |

## Commands

All commands use the `/ssc` base (aliases: `/shopkeepersstock`, `/stockcontrol`).

Shop arguments accept **display names** (tab-completable) or shop IDs. Names with spaces use underscores in commands (e.g., `Christmas_Shop`).

| Command | Description | Permission |
|---------|-------------|------------|
| `/ssc reload` | Reload configuration | `shopkeepersstock.admin` |
| `/ssc reset <player> [shop] [trade]` | Reset player's trades | `shopkeepersstock.admin` |
| `/ssc check <player> [shop] [trade]` | Check player's trade data | `shopkeepersstock.admin` |
| `/ssc restock <shop> [trade]` | Restock a shared-mode shop | `shopkeepersstock.admin` |
| `/ssc info <shop>` | Show shop configuration details | `shopkeepersstock.admin` |
| `/ssc debug` | Toggle debug mode (session only) | `shopkeepersstock.admin` |
| `/ssc cleanup` | Manually trigger cleanup | `shopkeepersstock.admin` |
| `/ssc help` | Show command help | `shopkeepersstock.admin` |

### Command Examples

```bash
# Reset all trades for Notch
/ssc reset Notch

# Reset only diamond trades for Notch in the Miner shop (by display name)
/ssc reset Notch Miner diamond_trade

# Check all trade data for Notch
/ssc check Notch

# Check Notch's trades in a specific shop
/ssc check Notch Miner

# Inspect a shop's configuration
/ssc info Miner

# Restock all trades in a shared shop
/ssc restock Christmas_Shop

# Restock a specific trade
/ssc restock Christmas_Shop santa_hat
```

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `shopkeepersstock.*` | All ShopkeepersStockControl permissions | `op` |
| `shopkeepersstock.admin` | Admin access to all commands | `op` |

## How It Works

### Per-Player Mode
1. **Player opens Shopkeeper** - Plugin caches the player-shop mapping
2. **Server sends trade offers** - Plugin intercepts the packet via PacketEvents
3. **Plugin modifies stock** - Shows player's personal remaining trades
4. **Player trades** - Plugin validates limits and records the trade
5. **Stock updates** - Client automatically shows updated numbers
6. **Cooldown expires** - Trades reset automatically (daily/weekly at fixed time, rolling after duration)

### Shared Mode
1. **Player opens shared Shopkeeper** - Plugin caches mapping and pre-loads global stock data
2. **Server sends trade offers** - Plugin shows remaining global stock (capped by per-player limit if configured)
3. **Player trades** - Plugin validates global stock + per-player cap, records trade
4. **Other viewers update** - All other players viewing the same shop receive a live packet update (debounced to next tick)
5. **Stock depleted** - When global stock hits zero, all viewers see trades crossed out
6. **Admin restocks** - `/ssc restock` refills global stock and resets per-player caps

### Trade Key System

The plugin uses **stable trade keys** (not slot numbers) to track trades. This means:
- You can reorder trades in Shopkeepers without breaking player data
- Trade history persists even if you change the villager UI layout
- Just update the `slot:` number in trades.yml when reordering

## Troubleshooting

### Trades aren't being limited

1. Check that the Shopkeeper UUID in `trades.yml` is correct
2. Ensure the `slot:` numbers match the actual villager UI positions (0 = first)
3. Check `enabled: true` for the shop
4. Run `/ssc debug` to enable debug logging and watch the console

### Stock display shows wrong numbers

1. Verify PacketEvents 2.10.0+ is installed as a separate plugin
2. Check server console for PacketEvents errors on startup
3. Make sure you're using Paper (not Spigot)

### Players see "Internal Exception" when joining

- **Cause**: PacketEvents version conflict
- **Fix**: Remove any old PacketEvents versions, install 2.10.0+

### Database errors on startup

- **Cause**: SQLite file permissions or corruption
- **Fix**:
  1. Stop server
  2. Backup `plugins/ShopkeepersStockControl/stockcontrol.db`
  3. Delete the db file (will recreate on start)
  4. Restart server

## Performance

- **Memory**: ~50KB per 100 players
- **Database**: ~500 bytes per tracked trade
- **Packet Modification**: <5ms per player (read-only cache lookup)
- **Shared Stock Push**: Debounced to 1 tick — 100 rapid trades collapse into a single update
- **Thread-Safe**: Fully concurrent with ConcurrentHashMap and volatile fields
- **Optimized**: Batch writes, prepared statements, indexed queries, dirty tracking

## Development

### Building from Source

```bash
git clone https://github.com/LoralonMC/ShopkeepersStockControl.git
cd ShopkeepersStockControl
./gradlew clean build
```

JAR will be in `build/libs/ShopkeepersStockControl-<version>.jar`

**Note**: All dependencies (including Shopkeepers API) are automatically downloaded via Maven during the build. At runtime, Shopkeepers and PacketEvents must be installed as separate plugins on your server.

### Project Structure

```
ShopkeepersStockControl/
src/main/java/dev/oakheart/stockcontrol/
  ShopkeepersStockControl.java       # Main plugin class
  commands/
    StockControlCommand.java         # Brigadier commands & tab completion
  config/
    ConfigManager.java               # Config loading & validation
  data/
    CooldownMode.java                # Daily/weekly/rolling/none enum
    StockMode.java                   # Per-player/shared enum
    DataStore.java                   # Storage interface
    SQLiteDataStore.java             # SQLite implementation
    PlayerTradeData.java             # Per-player trade state
    GlobalTradeData.java             # Shared stock state
    ShopConfig.java                  # Shop configuration model
    TradeConfig.java                 # Trade configuration model
    ShopContext.java                 # Player-shop session context
  listeners/
    ShopkeepersListener.java         # Trade validation & feedback
    PacketListener.java              # Merchant packet interception
    PlayerQuitListener.java          # Cache eviction on disconnect
  managers/
    TradeDataManager.java            # Trade tracking, cooldowns & persistence
    PacketManager.java               # Packet modification & live stock push
    CooldownManager.java             # Periodic cleanup & auto-purge
  message/
    MessageManager.java              # MiniMessage formatting & delivery
  placeholders/
    StockControlExpansion.java       # PlaceholderAPI integration
src/main/resources/
  config.yml                         # Main configuration
  trades.yml                         # Trade limits config
  paper-plugin.yml                   # Plugin descriptor
  plugin.yml                         # Permissions
```

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Support

- **Issues**: [GitHub Issues](https://github.com/LoralonMC/ShopkeepersStockControl/issues)
- **Discussions**: [GitHub Discussions](https://github.com/LoralonMC/ShopkeepersStockControl/discussions)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits

- **Author**: [Loralon](https://github.com/LoralonMC)
- **Dependencies**: [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers), [PacketEvents](https://github.com/retrooper/packetevents), [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) (optional)
- **Metrics**: [bStats](https://bstats.org/)

## Changelog

### v1.2.0

**New Features**
- Shared global stock mode (`stock_mode: shared`) — all players draw from one pool
- Per-player purchase caps for shared shops (`max_per_player` at shop or trade level)
- `none` cooldown mode — stock never restocks until admin uses `/ssc restock`
- `/ssc restock <shop> [trade]` command for manual restocking of shared shops
- Live stock updates — other viewers see stock changes instantly via debounced packet push
- Auto-purge of inactive player data (`purge_inactive_days` in config.yml)

**Modernization (Paper Standards)**
- Migrated to `paper-plugin.yml` as primary plugin descriptor
- Brigadier command registration via Paper's LifecycleEventManager (removed plugin.yml commands)
- New `MessageManager` with MiniMessage TagResolver API (replaced `Messages.java` string replacement)
- Main class refactor: removed static `getInstance()`, removed `implements Listener`, proper `Level.SEVERE` logging
- Extracted `PlayerQuitListener` from main class into dedicated listener
- `plugin.yml` now contains only permissions (added `shopkeepersstock.*` wildcard)
- `getPluginMeta()` replacing deprecated `getDescription()` throughout

**Build & Dependencies**
- Paper API 1.21 → 1.21.10
- Shadow plugin 8.3.9 → 9.3.1
- Removed run-paper plugin (not used)
- SQLite JDBC changed from `implementation` (shaded) to `compileOnly` (uses Paper's bundled driver)
- Removed unnecessary repos (spigotmc, sonatype, codemc-snapshots)
- Clean Java toolchain configuration
- Compiler warnings enabled (`-Xlint:deprecation`, `-Xlint:unchecked`)

**Config & Data**
- ConfigManager rewritten with FileConfiguration (preserves comments and formatting)
- `mergeDefaults()` only saves when new keys are actually missing (prevents SnakeYAML reformatting)
- Validate-before-swap reload (old config kept on validation failure)
- Debug mode toggle is now session-only (doesn't save to disk)

**Performance & Cleanup**
- Single-query restock cleanup (replaces O(n) individual deletes)
- Removed dead code: `ShopConfig.isValid()`, `ShopContext.getRemainingTime()`
- Removed unnecessary `PRAGMA foreign_keys=ON`
- Fixed misleading `cooldown: 86400` defaults on daily-mode trades in `trades.yml`
- Fixed DataStore Javadoc referencing non-existent YAML backend

### v1.1.0
- Per-trade cooldown mode overrides (daily/weekly/rolling with shop-level fallback)
- PlaceholderAPI expansion (`%ssc_remaining_<shop>:<trade>%`, etc.)
- Tab completion uses display names instead of UUIDs
- `/ssc info <shop>` command for in-game config inspection
- Action bar message support (configurable per message type)
- Cooldown field now optional for daily/weekly modes
- Orphaned shop data auto-cleanup on `/ssc reload`
- Fix single-trade slot matching bug
- Remove dead code and stale comments

### v1.0.0
- Per-player trade limits with configurable cooldowns
- Real-time stock display in villager UI
- SQLite database with automatic persistence
- MiniMessage support for custom messages
- Full command suite for administration
- bStats metrics integration
- Thread-safe packet modification
- Optimized with caching and batch writes
