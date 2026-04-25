package dev.oakheart.stockcontrol.util;

import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.RotationMode;
import dev.oakheart.stockcontrol.data.SubpoolConfig;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Computes pool rotation periods and deterministic active-item selections.
 *
 * <p>Selection is a pure function of (shopId, poolName, periodIndex, pool config).
 * The same inputs always yield the same output, so rotations survive restarts
 * within a period without requiring persistence of the random seed.</p>
 */
public final class RotationScheduler {

    private RotationScheduler() {}

    /**
     * Returns the period index for the given pool at the given moment.
     * Period index changes exactly once per scheduled boundary.
     *
     * @param pool The pool configuration
     * @param now  The reference time
     * @return A monotonically non-decreasing period index
     */
    public static long currentPeriodIndex(PoolConfig pool, ZonedDateTime now) {
        return switch (pool.getSchedule()) {
            case DAILY -> previousDailyBoundary(now, pool.getResetTime()).toEpochSecond() / 86400L;
            case WEEKLY -> previousWeeklyBoundary(now, pool.getResetDay(), pool.getResetTime()).toEpochSecond() / 604800L;
            case MONTHLY -> {
                ZonedDateTime boundary = previousMonthlyBoundary(now, pool.getResetTime());
                yield boundary.getYear() * 12L + boundary.getMonthValue();
            }
            case INTERVAL -> Math.floorDiv(now.toEpochSecond(), pool.getIntervalSeconds());
        };
    }

    /**
     * Returns the epoch second at which the given period ends (and the next period begins).
     *
     * @param pool         The pool configuration
     * @param periodIndex  The period whose end boundary is requested
     * @param reference    Any moment during {@code periodIndex} (used for daily/weekly anchoring)
     * @return Epoch seconds of the next boundary
     */
    public static long advancesAt(PoolConfig pool, long periodIndex, ZonedDateTime reference) {
        // Always anchored to the next natural wall-clock boundary. periodIndex may be a monotonic
        // counter bumped by force-advance, but the NEXT rotation should fire on schedule regardless.
        return switch (pool.getSchedule()) {
            case DAILY -> previousDailyBoundary(reference, pool.getResetTime()).plusDays(1).toEpochSecond();
            case WEEKLY -> previousWeeklyBoundary(reference, pool.getResetDay(), pool.getResetTime())
                    .plusWeeks(1).toEpochSecond();
            case MONTHLY -> previousMonthlyBoundary(reference, pool.getResetTime())
                    .plusMonths(1).toEpochSecond();
            case INTERVAL -> {
                long nowEpoch = reference.toEpochSecond();
                long clockPeriod = Math.floorDiv(nowEpoch, pool.getIntervalSeconds());
                yield (clockPeriod + 1) * pool.getIntervalSeconds();
            }
        };
    }

    /**
     * Picks the active items for a given period.
     * Returned list has exactly {@code pool.visible} entries, ordered to match
     * {@code pool.uiSlots} (first item → first uiSlot, and so on).
     *
     * @param shopId       The owning shop ID
     * @param pool         The pool configuration
     * @param periodIndex  The period to compute for
     * @return Ordered item keys (size == pool.getVisible())
     */
    public static List<String> selectActive(String shopId, PoolConfig pool, long periodIndex) {
        if (pool.hasSubpools()) {
            return selectActiveFromSubpools(shopId, pool, periodIndex);
        }
        return selectFromItems(shopId, pool.getName(), pool.getMode(),
                new ArrayList<>(pool.getItems().keySet()), pool.getVisible(), periodIndex);
    }

    /**
     * Returns the subpool that is spotlighted for the given period, or null if
     * the pool has no subpools. Subpool selection is deterministic by period.
     *
     * @param shopId       The owning shop ID
     * @param pool         The pool configuration
     * @param periodIndex  The period to compute for
     * @return The active subpool for this period, or null if the pool is flat
     */
    public static @Nullable SubpoolConfig selectActiveSubpool(String shopId, PoolConfig pool, long periodIndex) {
        if (!pool.hasSubpools()) return null;
        List<String> subpoolNames = new ArrayList<>(pool.getSubpools().keySet());
        if (subpoolNames.isEmpty()) return null;

        if (pool.getMode() == RotationMode.SEQUENTIAL) {
            int idx = (int) Math.floorMod(periodIndex, subpoolNames.size());
            return pool.getSubpool(subpoolNames.get(idx));
        }
        // RANDOM: deterministic seed picks one subpool per period.
        long seed = seedFor(shopId, pool.getName() + "::subpool", periodIndex);
        int idx = (int) Math.floorMod(new Random(seed).nextLong(), subpoolNames.size());
        if (idx < 0) idx += subpoolNames.size();
        return pool.getSubpool(subpoolNames.get(idx));
    }

    private static List<String> selectActiveFromSubpools(String shopId, PoolConfig pool, long periodIndex) {
        SubpoolConfig sub = selectActiveSubpool(shopId, pool, periodIndex);
        if (sub == null) return Collections.emptyList();

        // Subpool inherits parent's mode; visible count may be overridden per subpool.
        int subVisible = sub.effectiveVisible(pool);
        // Seed the inner item selection with the subpool name so different subpools picked
        // in the same period produce different inner shuffles (avoids correlation across pools).
        return selectFromItems(shopId, pool.getName() + "::" + sub.getName(),
                pool.getMode(), new ArrayList<>(sub.getItems().keySet()), subVisible, periodIndex);
    }

    private static List<String> selectFromItems(String shopId, String poolKey, RotationMode mode,
                                                List<String> allKeys, int visible, long periodIndex) {
        if (visible <= 0 || allKeys.isEmpty()) {
            return Collections.emptyList();
        }
        if (allKeys.size() <= visible) {
            return allKeys;
        }

        if (mode == RotationMode.SEQUENTIAL) {
            int start = (int) Math.floorMod(periodIndex * visible, allKeys.size());
            List<String> picked = new ArrayList<>(visible);
            for (int i = 0; i < visible; i++) {
                picked.add(allKeys.get((start + i) % allKeys.size()));
            }
            return picked;
        }

        // RANDOM: seed a Random deterministically.
        long seed = seedFor(shopId, poolKey, periodIndex);
        List<String> shuffled = new ArrayList<>(allKeys);
        Collections.shuffle(shuffled, new Random(seed));
        return new ArrayList<>(shuffled.subList(0, visible));
    }

    private static long seedFor(String shopId, String poolName, long periodIndex) {
        long h = 1469598103934665603L; // FNV-1a 64-bit offset basis
        for (char c : shopId.toCharArray()) {
            h ^= c;
            h *= 1099511628211L;
        }
        h ^= ':';
        h *= 1099511628211L;
        for (char c : poolName.toCharArray()) {
            h ^= c;
            h *= 1099511628211L;
        }
        h ^= periodIndex;
        h *= 1099511628211L;
        return h;
    }

    private static ZonedDateTime previousDailyBoundary(ZonedDateTime now, String resetTime) {
        int[] hm = parseHm(resetTime);
        ZonedDateTime today = now.withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
        return today.isAfter(now) ? today.minusDays(1) : today;
    }

    private static ZonedDateTime previousWeeklyBoundary(ZonedDateTime now, String resetDay, String resetTime) {
        int[] hm = parseHm(resetTime);
        DayOfWeek targetDay = DayOfWeek.valueOf(resetDay);
        ZonedDateTime candidate = now.with(TemporalAdjusters.previousOrSame(targetDay))
                .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
        return candidate.isAfter(now)
                ? now.with(TemporalAdjusters.previous(targetDay))
                        .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0)
                : candidate;
    }

    private static ZonedDateTime previousMonthlyBoundary(ZonedDateTime now, String resetTime) {
        int[] hm = parseHm(resetTime);
        ZonedDateTime thisMonth = now.withDayOfMonth(1)
                .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
        return thisMonth.isAfter(now) ? thisMonth.minusMonths(1) : thisMonth;
    }

    private static int[] parseHm(String resetTime) {
        String[] parts = resetTime.split(":");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }
}
