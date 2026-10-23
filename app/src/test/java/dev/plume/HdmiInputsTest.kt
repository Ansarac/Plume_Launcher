package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiInputsTest {
    @Test fun icons() {
        assertEquals("playstation", hdmiIcon("PlayStation 4", true))
        assertEquals("xbox", hdmiIcon("Xbox Series X", true))
        assertEquals("nintendoswitch", hdmiIcon("NintendoSwitch", true))
        assertEquals("appletv", hdmiIcon("Apple TV", true))
        assertEquals("settop", hdmiIcon("Sky Q", true))
        assertEquals("playback", hdmiIcon("BD Player", true))
        assertEquals("hdmi", hdmiIcon("HDMI 2", false))
        assertEquals("xbox", hdmiIcon("xbox", false)) // a port renamed in TV settings
        assertEquals("settop", hdmiIcon("Cable box", false))
        assertTrue(isAudioDevice("Sonos Arc"))
        assertFalse(isAudioDevice("PlayStation 5"))
    }

    @Test fun row() {
        assertTrue(inRow("com.app", emptySet()))
        assertFalse(inRow("com.app", setOf("com.app")))
    }
}
