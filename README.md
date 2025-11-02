# ShopkeepersStockControl

A powerful Paper plugin that adds **per-player trade limits** to [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) with accurate real-time stock display in the vanilla villager trading UI.

[![bStats](https://img.shields.io/bstats/servers/27827)](https://bstats.org/plugin/bukkit/ShopkeepersStockControl/27827)
[![License](https://img.shields.io/github/license/LoralonMC/ShopkeepersStockControl)](LICENSE)

## Features

- ✨ **Per-Player Trade Limits** - Each player has their own independent trade limits
- ⏰ **Configurable Cooldowns** - Set different cooldown periods for each trade (hours, days, weeks)
- 📊 **Real-Time Stock Display** - Players see their remaining trades directly in the villager UI
- 🎯 **Slot-Based Tracking** - Track specific trades in each Shopkeeper independently
- 💾 **SQLite Database** - Persistent data storage with automatic backups
- 🔧 **Easy Configuration** - YAML-based configuration with helpful comments
- 🎨 **MiniMessage Support** - Modern text formatting with gradients and colors
- 📈 **bStats Metrics** - Anonymous usage statistics for development priorities

## Screenshots

When a player opens a Shopkeeper, they see **their personal stock** for each trade:
- ✅ Trades show exact remaining count (e.g., "2/4 remaining")
- ❌ Depleted trades automatically cross out when limit reached
- 🔄 Stock refreshes on cooldown expiry

## Requirements

- **Server**: Paper 1.21+ (or any Paper fork)
- **Dependencies**:
  - [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers) 2.24.0+
  - [PacketEvents](https://github.com/retrooper/packetevents) 2.10.0+ (as a plugin on your server)
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

### Example trades.yml

```yaml
shops:
  # Replace with your actual Shopkeeper UUID
  e8f4a3c2-1234-5678-9abc-def012345678:
    name: "Diamond Merchant"  # Friendly display name
    enabled: true
    respect_shop_stock: false  # Set to true for finite-stock shops
    trades:
      diamond_trade:
        slot: 0  # Position in villager UI (0 = first trade)
        max_trades: 4  # Player can do this trade 4 times
        cooldown: 86400  # 24 hours in seconds

      netherite_trade:
        slot: 1
        max_trades: 1
        cooldown: 604800  # 7 days
```

### Cooldown Reference

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
  trade_limit_reached: "<red>You've reached your limit! <gray>(%time_remaining% remaining)"
  trades_remaining: "<gray>Trades remaining: <green>%remaining%<gray>/<green>%max%"
  cooldown_active: "<red>Cooldown active. Time remaining: <yellow>%time_remaining%"
```

**Available Placeholders:**
- `%time_remaining%` - Time until cooldown expires (e.g., "23h 45m")
- `%remaining%` - Trades remaining for the player
- `%max%` - Maximum trades allowed
- `%player%` - Player's name

**Tip:** Set any message to `""` (empty string) to disable it.

## Commands

All commands use the `/ssc` base (aliases: `/shopkeepersstock`, `/stockcontrol`)

| Command | Description | Permission |
|---------|-------------|------------|
| `/ssc reload` | Reload configuration | `shopkeepersstock.admin` |
| `/ssc reset <player> [shop] [trade]` | Reset player's trades | `shopkeepersstock.admin` |
| `/ssc check <player> [shop] [trade]` | Check player's trade data | `shopkeepersstock.admin` |
| `/ssc debug` | Toggle debug mode | `shopkeepersstock.admin` |
| `/ssc cleanup` | Manually trigger cleanup | `shopkeepersstock.admin` |
| `/ssc help` | Show command help | `shopkeepersstock.admin` |

### Command Examples

```bash
# Reset all trades for Notch
/ssc reset Notch

# Reset only diamond trades for Notch in the miner shop
/ssc reset Notch e8f4a3c2-1234-5678-9abc-def012345678 diamond_trade

# Check all trade data for Notch
/ssc check Notch

# Check Notch's trades in a specific shop
/ssc check Notch e8f4a3c2-1234-5678-9abc-def012345678
```

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `shopkeepersstock.use` | Use the plugin | `true` |
| `shopkeepersstock.admin` | Admin access to all commands | `op` |

## How It Works

1. **Player opens Shopkeeper** → Plugin caches the player-shop mapping
2. **Server sends trade offers** → Plugin intercepts the packet via PacketEvents
3. **Plugin modifies stock** → Shows player's personal remaining trades
4. **Player trades** → Plugin validates limits and records the trade
5. **Stock updates** → Client automatically shows updated numbers
6. **Cooldown expires** → Trades reset automatically

### Trade Key System

The plugin uses **stable trade keys** (not slot numbers) to track trades. This means:
- ✅ You can reorder trades in Shopkeepers without breaking player data
- ✅ Trade history persists even if you change the villager UI layout
- ✅ Just update the `slot:` number in trades.yml when reordering

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

# Download Shopkeepers dependency (required for compilation)
# Download Shopkeepers-2.24.0.jar from:
# https://github.com/Shopkeepers/Shopkeepers/releases/tag/v2.24.0
# Place it in the libs/ directory

./gradlew clean build
```

JAR will be in `build/libs/ShopkeepersStockControl.jar`

**Note**: The Shopkeepers JAR is only needed for compilation. At runtime, Shopkeepers must be installed as a separate plugin on your server.

### Project Structure

```
ShopkeepersStockControl/
├── src/main/java/dev/oakheart/stockcontrol/
│   ├── ShopkeepersStockControl.java  # Main plugin class
│   ├── managers/                      # Business logic layer
│   │   ├── TradeDataManager.java     # Trade tracking & cooldowns
│   │   ├── PacketManager.java        # Packet interception
│   │   └── CooldownManager.java      # Automatic cleanup
│   ├── listeners/                     # Event handlers
│   │   ├── ShopkeepersListener.java  # Trade validation
│   │   └── PacketListener.java       # Packet modification
│   ├── data/                          # Data layer
│   │   ├── SQLiteDataStore.java      # Database operations
│   │   └── PlayerTradeData.java      # Data models
│   ├── config/                        # Configuration
│   │   ├── ConfigManager.java
│   │   └── Messages.java             # Message constants
│   └── commands/                      # Command handlers
│       └── StockControlCommand.java
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
- **Dependencies**: [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers), [PacketEvents](https://github.com/retrooper/packetevents)
- **Metrics**: [bStats](https://bstats.org/)

## Changelog

### v1.0.0 (Initial Release)
- ✨ Per-player trade limits with configurable cooldowns
- 📊 Real-time stock display in villager UI
- 💾 SQLite database with automatic persistence
- 🎨 MiniMessage support for custom messages
- 🔧 Full command suite for administration
- 📈 bStats metrics integration
- 🐛 Thread-safe packet modification
- ⚡ Optimized with caching and batch writes
