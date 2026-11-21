package app.irlsupreme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test fun `hex reads short and long forms, and nothing else`() {
        assertEquals(0xFF0A9396.toInt(), ColorMath.parseHex("#0a9396"))
        assertEquals(0xFFFF8800.toInt(), ColorMath.parseHex("f80"))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("teal"))
        assertEquals("#0A9396", ColorMath.toHex(0xFF0A9396.toInt()))
    }

    @Test fun `hsv round-trips to the same colour`() {
        for (c in listOf(0xFF0A9396, 0xFFD4489C, 0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFF0000).map { it.toInt() }) {
            val (h, s, v) = ColorMath.toHsv(c).let { Triple(it[0], it[1], it[2]) }
            assertEquals(ColorMath.toHex(c), ColorMath.toHex(ColorMath.fromHsv(h, s, v)))
        }
    }

    @Test fun `names say roughly what the colour is`() {
        assertEquals("Teal", ColorMath.name(0xFF0A9396.toInt()).removePrefix("Dark ").replaceFirstChar { it.uppercase() })
        assertEquals("Grey", ColorMath.name(0xFF808080.toInt()))
        assertEquals("White", ColorMath.name(0xFFFFFFFF.toInt()))
        assertEquals("Black", ColorMath.name(0xFF050505.toInt()))
        assertEquals("Red", ColorMath.name(0xFFE02020.toInt()))
    }

    @Test fun `strong red, amber and green look like signals, muted ones don't`() {
        assertTrue(ColorMath.looksLikeSignal(0xFFE02020.toInt()))
        assertTrue(ColorMath.looksLikeSignal(0xFFF0A800.toInt()))
        assertTrue(ColorMath.looksLikeSignal(0xFF20C040.toInt()))
        assertFalse(ColorMath.looksLikeSignal(0xFF0A9396.toInt()))
        assertFalse(ColorMath.looksLikeSignal(0xFF6E8796.toInt()))
    }

    @Test fun `words on a colour use whichever of light and dark reads better`() {
        assertEquals(0xFF000000.toInt(), ColorMath.readableOn(0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.readableOn(0xFF1A237E.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
    }
}
