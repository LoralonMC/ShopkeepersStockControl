# ShopkeepersStockControl

A powerful Paper plugin that adds **per-player trade limits** to [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) with accurate real-time stock display in the vanilla villager trading UI.

[![bStats](https://img.shields.io/bstats/servers/27827)](https://bstats.org/plugin/bukkit/ShopkeepersStockControl/27827)
[![License](https://img.shields.io/github/license/LoralonMC/ShopkeepersStockControl)](LICENSE)

## Features

- **Per-Player Trade Limits** - Each player has their own independent trade limits
- **Three Cooldown Modes** - Daily reset (e.g., midnight), weekly reset (e.g., Monday), or rolling cooldown (e.g., 24h after first trade)
- **Per-Trade Overrides** - Each trade can override the shop's cooldown mode (e.g., most trades reset daily, but a rare trade resets weekly)
- **Real-Time Stock Display** - Players see their remaining trades directly in the villager UI via PacketEvents
- **Slot-Based Tracking** - Track specific trades in each Shopkeeper independently
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

### Cooldown Modes

The plugin supports three cooldown modes, configurable at the shop level with per-trade overrides:

| Mode | Behavior | Example |
|------|----------|---------|
| `daily` | Resets at a fixed time every day | All trades reset at midnight |
| `weekly` | Resets at a fixed time on a specific day | Rare trades reset Monday at 00:00 |
| `rolling` | Resets X seconds after the player's first trade | 24h personal cooldown per player |

### Example trades.yml

```yaml
shops:
  # Daily reset shop - all trades reset at midnight
  e8f4a3c2-1234-5678-9abc-def012345678:
    name: "Miner"
    enabled: true
    respect_shop_stock: false
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
    respect_shop_stock: false
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
```

**Key points:**
- `cooldown_mode`, `reset_time`, and `reset_day` can be set at the shop level (defaults for all trades) or per-trade (overrides)
- `cooldown` (seconds) is **required** for `rolling` mode, **optional** for `daily`/`weekly`
- If a trade doesn't specify `cooldown_mode`, it inherits from the shop
- If the shop doesn't specify `cooldown_mode`, it defaults to `rolling`

#### Rolling Cooldown Reference

| Duration | Seconds |
|----------|---------|
| 1 hour   | 3600    |
| 6 hours  | 21600   |
| 12 hours | 43200   |
| 24 hours | 86400   |
| 7 days   | 604800  |
| 30 days  | 2592000 |

### Message Customization

Edit `config.yml` to customize player messages using [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format:

```yaml
messages:
  trade_limit_reached: "<red>You've reached your limit! <gray>Cooldown: <yellow>{time_remaining}<gray> (Resets at {reset_time})"
  trades_remaining: "<gray>Trades remaining: <green>{remaining}<gray>/<green>{max}"
  cooldown_active: "<red>Cooldown active. <yellow>{time_remaining}<gray> (Resets at {reset_time})"
```

**Available placeholders:**
- `{time_remaining}` - Time until cooldown expires (e.g., "23h 45m")
- `{reset_time}` - Reset time display (e.g., "00:00" for daily, "Monday 00:00" for weekly)
- `{remaining}` - Trades remaining for the player
- `{max}` - Maximum trades allowed
- `{player}` - Player's name

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
| `%ssc_remaining_Miner:diamond_trade%` | Remaining trades | `3` |
| `%ssc_used_Miner:diamond_trade%` | Used trades | `1` |
| `%ssc_max_Miner:diamond_trade%` | Max trades from config | `4` |
| `%ssc_cooldown_Miner:diamond_trade%` | Time until reset or "Ready" | `23h 45m` |
| `%ssc_resettime_Miner:diamond_trade%` | Reset time display | `00:00` |

## Commands

All commands use the `/ssc` base (aliases: `/shopkeepersstock`, `/stockcontrol`).

Shop arguments accept **display names** (tab-completable) or shop IDs.

| Command | Description | Permission |
|---------|-------------|------------|
| `/ssc reload` | Reload configuration | `shopkeepersstock.admin` |
| `/ssc reset <player> [shop] [trade]` | Reset player's trades | `shopkeepersstock.admin` |
| `/ssc check <player> [shop] [trade]` | Check player's trade data | `shopkeepersstock.admin` |
| `/ssc info <shop>` | Show shop configuration details | `shopkeepersstock.admin` |
| `/ssc debug` | Toggle debug mode | `shopkeepersstock.admin` |
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
```

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `shopkeepersstock.use` | Use the plugin | `true` |
| `shopkeepersstock.admin` | Admin access to all commands | `op` |

## How It Works

1. **Player opens Shopkeeper** - Plugin caches the player-shop mapping
2. **Server sends trade offers** - Plugin intercepts the packet via PacketEvents
3. **Plugin modifies stock** - Shows player's personal remaining trades
4. **Player trades** - Plugin validates limits and records the trade
5. **Stock updates** - Client automatically shows updated numbers
6. **Cooldown expires** - Trades reset automatically (daily/weekly at fixed time, rolling after duration)

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
- **Thread-Safe**: Fully concurrent with ConcurrentHashMap
- **Optimized**: Batch writes, prepared statements, indexed queries

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
├── src/main/java/dev/oakheart/stockcontrol/
│   ├── ShopkeepersStockControl.java  # Main plugin class
│   ├── commands/
│   │   └── StockControlCommand.java  # Command handling & tab completion
│   ├── config/
│   │   ├── ConfigManager.java        # Config loading & validation
│   │   └── Messages.java             # Message key constants
│   ├── data/
│   │   ├── DataStore.java            # Storage interface
│   │   ├── SQLiteDataStore.java      # SQLite implementation
│   │   ├── PlayerTradeData.java      # Player trade state
│   │   ├── ShopConfig.java           # Shop configuration model
│   │   ├── TradeConfig.java          # Trade configuration model
│   │   ├── ShopContext.java           # Player-shop session context
│   │   └── CooldownMode.java         # Daily/weekly/rolling enum
│   ├── listeners/
│   │   ├── ShopkeepersListener.java  # Trade validation & feedback
│   │   └── PacketListener.java       # Merchant packet interception
│   ├── managers/
│   │   ├── TradeDataManager.java     # Trade tracking & cooldowns
│   │   ├── PacketManager.java        # Packet modification logic
│   │   └── CooldownManager.java      # Periodic cleanup
│   └── placeholders/
│       └── StockControlExpansion.java # PlaceholderAPI integration
└── src/main/resources/
    ├── config.yml                     # Main configuration
    ├── trades.yml                     # Trade limits config
    └── plugin.yml                     # Plugin metadata
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
