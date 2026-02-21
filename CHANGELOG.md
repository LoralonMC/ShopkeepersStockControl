# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.0] - 2026-02-21

### Added

- Add per-command permission nodes (replaces single `shopkeepersstock.admin`)
- Add PlaceholderAPI detection log when not installed

### Changed

- Make all command and error messages configurable via config.yml
- Consolidate message text and display mode into nested config structure (`messages.<key>.text` / `messages.<key>.display`)
- Migrate all config keys from snake_case to kebab-case (auto-migration from v1 configs)
- Update default messages to use Message Design Standards color palette and `[ꜱʜᴏᴘ]` prefix
- Add section separators and MiniMessage documentation link to config.yml
- Add config-version footer to config.yml for future migrations
- Flush player trade data asynchronously on quit instead of blocking the main thread
- Use Bukkit scheduler for async shop data preloading instead of ForkJoinPool
- Separate cooldown reset logic from trade permission check (`canTrade` is now read-only)
- Resolve player last-played times on the main thread during inactive player purge

### Fixed

- Add null guard for reset-day config values to prevent startup crash
- Add UTF-8 charset to InputStreamReader when loading default config
- Add missing shutdown log message in plugin onDisable
- Store initial purge task reference so it can be cancelled on shutdown
- Use insertion-ordered LinkedHashSet for config validation errors

### Removed

- Remove unused `loadShopData(String shopId)` method from DataStore interface and SQLiteDataStore

## [1.2.0] - 2026-02-13

### Added

- Add shared global stock mode — all players draw from a single stock pool per trade
- Add per-player purchase caps for shared shops (`max-per-player` at shop or trade level)
- Add `none` cooldown mode for manual-restock-only shops
- Add `/ssc restock <shop> [trade]` command for restocking shared shops
- Add live stock updates when shared stock changes (debounced for performance)
- Add auto-purge for inactive player data (`purge-inactive-days` config option)

### Changed

- Migrate to `paper-plugin.yml` as primary descriptor
- Register commands via Brigadier and Paper's LifecycleEventManager
- Add MessageManager with MiniMessage TagResolver API for player-facing messages
- Refactor main class to constructor injection (no static `getInstance()`)
- Rewrite ConfigManager with Bukkit's FileConfiguration (preserves comments on save)
- Update Paper API from 1.21 to 1.21.10
- Update Shadow plugin from 8.3.9 to 9.3.1
- Use Paper's bundled SQLite JDBC driver instead of shading it

## [1.1.0] - 2026-02-10

### Added

- Add per-trade cooldown mode overrides (daily/weekly/rolling) with shop-level fallback
- Add PlaceholderAPI expansion (`%ssc_remaining_<shop>:<trade>%`, `%ssc_used_...%`, `%ssc_cooldown_...%`, etc.)
- Add `/ssc info <shop>` command to inspect shop configuration in-game
- Add action bar message support (configurable per message type via `message-display`)
- Add tab completion using shop display names instead of UUIDs
- Add orphaned shop data auto-cleanup on `/ssc reload`
- Add configurable fixed daily reset time (`daily_reset_time`)

### Changed

- Make cooldown field optional for daily/weekly modes (only required for rolling)
- Return raw values from placeholders (data-only, no embedded formatting)
- Only show detailed trade loading messages in debug mode

### Fixed

- Fix thread safety: synchronize SQLite methods, atomic dirty-key flush, volatile cross-thread fields
- Fix daily reset not triggering (canTrade incorrectly used rolling cooldown logic)
- Fix `/ssc check` showing stale data by flushing pending writes before querying
- Fix `/ssc check` showing active cooldown after daily reset had passed
- Fix single-trade shops incorrectly matching clicks on any slot
- Fix MiniMessage tag injection in commands via unsanitized user input
- Fix rolling cooldowns resetting timer on limit hit instead of starting from first trade
- Fix `/ssc debug` triggering full config reload with orphan cleanup
- Fix bStats relocation warning

## [1.0.0] - 2025-11-02

### Added

- Initial release
