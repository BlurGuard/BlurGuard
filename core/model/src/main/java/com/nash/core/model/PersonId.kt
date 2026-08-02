package com.nash.core.model

/**
 * Type-safe identifier for a trusted person (an identity), as opposed to
 * [TrackId] which identifies a short-lived tracker lifecycle.
 *
 * Trust is per-person: track IDs die and respawn, a PersonId survives across
 * them via face-embedding matching.
 */
@JvmInline
value class PersonId(val value: Long)
