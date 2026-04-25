# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Bootstrap-friendly validation — pools with zero items (or subpools with zero items) now load successfully so the operator can declare pool/subpool structure in `trades.yml` *before* running `/ssc bulk add`. Previously empty pools were rejected, which created a chicken-and-egg setup problem. Pools with at least one item still enforce the "items >= visible" rule.
- `/ssc bulk add` now falls back to the pool's default `price` when the named subpool isn't declared in `trades.yml` yet (the common bootstrap case). The output snippet is also now wrapped in a complete subpool YAML block (with the right indentation) so the operator can paste it directly under `pools.<pool>.subpools:`.
- `/ssc bulk add` now writes the new subpool entries (item keys + source slots + max-trades) **directly into `trades.yml`** via the OakheartLib config wrapper (which preserves comments and formatting). Replaces the earlier "paste a snippet" workflow — the operator just runs the command and then `/ssc reload`. If a subpool's `visible` is already set, it's left alone so hand-tuned values aren't clobbered.

### Added

- `max-trades: -1` is now a valid "unlimited" sentinel — semantically "no per-period purchase cap." Use it for rotation-pool collectibles where the rotation itself is the throttle (one item appears per period; the player can buy as many as they want during that window). Validation still rejects `0` (typo guard — a zero cap would block every trade). Display surfaces (`/ssc info`, `/ssc check`, PlaceholderAPI `max`/`remaining`/`globalmax`) render unlimited as `∞`. The merchant offer is painted as `0/Integer.MAX_VALUE` so the trade always shows in stock.
- Flow-style YAML lists (`ui-slots: [0, 1, 2]`) are now supported for read paths, fixing a silent parse failure where pools with flow-style `ui-slots` reported `ui-slots must list at least one UI position` after a config save. Requires the matching OakheartLib 1.1.1 update — block style still works.

### Fixed

- `/ssc bulk add` no longer fails when the target pool's `subpools` (or `items`) is declared as the flow-style empty placeholder `{}` — the wrapper now strips that placeholder and recreates it as an extensible block section before writing the new entries.
- `/ssc bulk add` now writes `max-trades: -1` (unlimited) for new pool items instead of `0`. Existing `max-trades` values are preserved on re-runs so hand-tuned caps aren't clobbered.
- Reload now re-picks a pool's active items when the cached list goes stale within the current period. Previously, if a pool was reloaded while empty (active list seeded as `[]`), then items were added via `/ssc bulk add` and the operator reloaded again, the active list stayed empty until the next scheduled boundary because the period index hadn't advanced. Reload now also re-picks when the cached size doesn't match `visible` or when any cached key is no longer a valid pool/subpool item — without wiping counters or burning a rotation tick.
- `PoolConfig.getItem(key)` now searches subpool items in addition to the flat items list. Without this, the merchant UI rebuild silently dropped every rotation slot whose item lived in a subpool — the active item key was correct, but the resolver returned null and the UI fell through with no offer for that slot. Trade limits already merged subpool items into the shop-level lookup, so stock tracking was unaffected.

### Added

- Rotation pools — shops can now declare named pools that cycle through a set of items on a daily, weekly, monthly, or interval schedule. Each pool owns UI slots and shows only `visible` items per period; non-active items are fully hidden. Selection is deterministic across restarts and server-wide.
- New `monthly` schedule for rotation pools — advances on the 1st of each month at `reset-time`. Period index uses calendar months so varied month lengths are handled correctly.
- Subpools — pools can now declare named subpools (each with its own item list), and each rotation period spotlights exactly one subpool. Item selection happens within the active subpool only, producing themed rotations like "March is Dogs month" rather than random mixing across the entire pool. Each subpool can override the parent pool's `visible` count and `price`.
- Per-pool `price` field — declares a default emerald (or other ItemStack) cost for items added via the bulk-add command. Subpools can override with their own `price`.
- New command: `/ssc bulk add <shop> <pool> <subpool> <items-file>` — reads a YAML file of Nexo item IDs, resolves each via the Nexo API, and adds them as Shopkeepers `TradeOffer`s to the named admin shop using the pool's default price. Outputs a paste-ready `trades.yml` snippet showing the assigned source slots so the operator can drop it into the matching pool/subpool's `items:` section.
- New command: `/ssc bulk clear <shop> <count>` — removes the trailing N offers from a Shopkeepers admin shop. Useful for re-running bulk-add cleanly when a pool's item list changes.
- New permission: `shopkeepersstock.bulk` (op default).
- New optional dependency: Nexo (only required when using bulk-add commands; runtime-checked).
- Static trades can now split the UI slot a player sees from the Shopkeepers editor source slot via an optional `source:` field.
- New command: `/ssc rotation peek <shop>` shows current active items and time-to-next for each pool.
- New command: `/ssc rotation force <shop> [pool]` manually advances all pools (or one) in a shop for testing.
- New permission: `shopkeepersstock.rotation`.
- New placeholders: `%ssc_poolactive_<shop>:<pool>%` (active items) and `%ssc_poolnext_<shop>:<pool>%` (time until next rotation).
- Rotation counter semantics: each scheduled advance wipes per-player and global counters for the newly-active items so each rotation feels like a fresh shop.

### Changed

- Migrate to OakheartLib shared library (config, messages, commands)
- Move messages from config.yml to separate messages.yml — existing servers migrate automatically on first startup
- All config files (config.yml, messages.yml, trades.yml) now perfectly preserve comments, formatting, and quoting on every save

### Fixed

- Periodic cooldown cleanup now deletes expired entries from the database instead of only evicting them from the cache, so the same row no longer gets rediscovered and re-logged every minute
- Serialize trade-data resets and batch writes. Without this, the async batch-write task could snapshot a stock counter just before a concurrent reset wiped the row, then write the stale value back and silently undo the reset
- Reject trade keys and pool item keys that contain a comma, since rotation state is persisted as a comma-separated list of active items and a key containing a comma would corrupt the round-trip parse
- `/ssc info <shop>` now lists rotation pools and their items alongside static trades

### Added

- `/ssc diag` — admin diagnostic command that dumps cache sizes, pending-queue depths, DB row counts, and current rotation states
- `/ssc stress <shop> <trade> <players> <duration>` — admin stress test that spawns N concurrent virtual players hammering a trade (optionally with rotation forces interleaved) and reports ops/sec, p50/p99 latency, and any exceptions caught. Cleans up its fake-player data afterward. Run on staging or during maintenance; writes to the live DB briefly.
- New permission `shopkeepersstock.admin` gates both of the above

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
