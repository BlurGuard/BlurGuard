package com.nash.engine.impl.mapper

import com.nash.core.model.AnonymizationModeEnum
import com.nash.engine.api.AnonymizationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AnonymizationModeMapperTest {
    private val mapper = AnonymizationModeMapper()

    @Test
    fun `maps every public anonymization mode to core mode`() {
        assertEquals(AnonymizationModeEnum.BOUNDING, mapper.toCore(AnonymizationMode.BOUNDING))
        assertEquals(AnonymizationModeEnum.BLUR, mapper.toCore(AnonymizationMode.BLUR))
        assertEquals(AnonymizationModeEnum.PIXELATE, mapper.toCore(AnonymizationMode.PIXELATE))
        assertEquals(AnonymizationModeEnum.BLACKBOX, mapper.toCore(AnonymizationMode.BLACK_BOX))
    }
}