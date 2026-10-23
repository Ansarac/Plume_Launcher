package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun aFlatPictureStaysFlat() {
        val flat = IntArray(12 * 8) { px(40, 120, 200) }
        assertTrue(boxBlur(flat, 12, 8, 2).all { it == px(40, 120, 200) })
    }

    @Test fun aBrightPixelSpreadsToItsNeighboursAndDims() {
        val w = 15
        val h = 15
        val pic = IntArray(w * h) { px(0, 0, 0) }
        pic[7 * w + 7] = px(255, 255, 255)
        val out = boxBlur(pic, w, h, 2)
        val centre = out[7 * w + 7] and 0xFF
        assertTrue("the centre dims", centre in 1..254)
        assertTrue("a neighbour lights up", (out[7 * w + 8] and 0xFF) > 0)
        assertTrue("the blur is about the same both ways", Math.abs((out[7 * w + 8] and 0xFF) - (out[8 * w + 7] and 0xFF)) <= 3)
        assertTrue("far away stays black", (out[0] and 0xFF) == 0)
    }

    @Test fun theInputIsLeftAlone() {
        val pic = IntArray(9 * 9) { px(it, 0, 0) }
        val copy = pic.copyOf()
        boxBlur(pic, 9, 9, 1)
        assertTrue(pic.contentEquals(copy))
    }

    @Test fun darkModeKeepsTheColoursAndLightModeLightensThem() {
        val pic = intArrayOf(px(200, 100, 50))
        assertEquals(pic[0], glassTone(pic, dark = true)[0])
        assertEquals(px(211, 131, 91), glassTone(pic, dark = false)[0])
    }

    @Test fun aSurfaceFindsItsPlaceInTheBlur() {
        // On a 1920x1080 screen the 120x68 blur is scaled by 16 and 15.88; a view at (300, 200) shifts it back.
        val m = glassMapping(1920, 1080, 300, 200)
        assertEquals(16f, m[0], 0.001f)
        assertEquals(1080f / 68, m[1], 0.001f)
        assertEquals(-300f, m[2], 0.001f)
        assertEquals(-200f, m[3], 0.001f)
    }
}
