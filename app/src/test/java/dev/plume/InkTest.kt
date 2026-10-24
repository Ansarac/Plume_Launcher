package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class InkTest {
    @Test
    fun brightPhotoGetsDarkText() = assertEquals(0xFF111214.toInt(), inkFor(0.8f))

    @Test
    fun darkPhotoGetsWhiteText() = assertEquals(-1, inkFor(0.05f)) // Color.WHITE
}
