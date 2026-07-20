package com.nash.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Feature-facing control surface of the anonymization engine.
 *
 * Deliberately NON-generic: the frame type F is an internal detail of the
 * pipeline wiring. Consumers of this interface (use cases, ViewModels) only
 * ever see tracked-box METADATA — never frames (architecture invariant).
 */
interface AnonymizationEngine {

    /**
     * Latest tracked boxes, normalized to the upright analysis frame.
     * Conflated latest-wins state: collectors always get the newest result,
     * never a backlog.
     */
    val trackedBoxes: StateFlow<List<TrackedBox>>


    /** Live pipeline performance counters (debug). */
    val stats: StateFlow<PipelineStats>


    /** Starts consuming frames. Resets tracking state from any previous session. */
    fun start()

    /** Stops consuming frames and clears tracking state. */
    fun stop()


}