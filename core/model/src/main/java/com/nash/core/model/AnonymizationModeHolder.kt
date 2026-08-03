package com.nash.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the active anonymization mode.
 *
 * Lives in core/model so both the renderer (core/blurring) and the
 * feature layer (via core/domain use cases) can share it without
 * violating dependency rules. Will be backed by DataStore later.
 */
class AnonymizationModeHolder {

    private val _mode = MutableStateFlow(AnonymizationModeEnum.BLUR)
    val mode: StateFlow<AnonymizationModeEnum> = _mode.asStateFlow()

    fun set(mode: AnonymizationModeEnum) {
        _mode.value = mode
    }
}
