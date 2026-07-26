package dev.oakheart.stockcontrol.api;

/**
 * Why a pool's active-item list changed.
 *
 * <p>Consumers that mirror rotation into the world (signage, displays, shelves) can usually
 * treat every cause identically — the active list is authoritative regardless of how it was
 * reached. The distinction exists for callers that want to react differently to an operator
 * action than to a scheduled boundary.</p>
 */
public enum RotationCause {

    /** First time this pool was seen; no prior persisted state existed. Counters were not reset. */
    SEED,

    /** A scheduled rotation boundary passed. Counters for the newly-active items were reset. */
    ADVANCE,

    /** An operator ran {@code /ssc rotation advance}. Counters were reset, same as {@link #ADVANCE}. */
    FORCE,

    /**
     * The period did not change, but the cached active list no longer matched what selection
     * would pick — typically because items were added to the pool and the config was reloaded.
     * Counters were left alone.
     */
    REPICK
}
