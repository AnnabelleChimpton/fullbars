package app.irlsupreme.service

import java.util.Locale

/**
 * Colour helpers for the colour pickers (cards and alerts): hex in and out, HSV both ways, a plain name, contrast,
 * and whether a colour could be read as one of the app's signal colours. Pure, so the rules are unit tested.
 * Colours are opaque ARGB ints throughout.
 */
object ColorMath {
    /** "#RRGGBB" for [argb], upper case. */
    fun toHex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** An opaque colour from "#RGB", "#RRGGBB", "RRGGBB" or "rgb" (spaces ignored), or null when it isn't one. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#").replace(" ", "")
        val six = when {
            t.length == 3 && t.all { it.isHex() } -> t.map { "$it$it" }.joinToString("")
            t.length == 6 && t.all { it.isHex() } -> t
            else -> return null
        }
        return (0xFF000000.toInt() or six.toInt(16))
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Hue 0..360, saturation and value 0..1. */
    fun toHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d).mod(6f))
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val hh = (h.mod(360f)) / 60f
        val c = v.coerceIn(0f, 1f) * s.coerceIn(0f, 1f)
        val x = c * (1 - kotlin.math.abs(hh.mod(2f) - 1))
        val m = v.coerceIn(0f, 1f) - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** WCAG relative luminance, 0..1. */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Whichever of [light] and [dark] reads better on [background]: the text colour for words on a filled colour. */
    fun readableOn(background: Int, light: Int, dark: Int): Int =
        if (contrast(background, light) >= contrast(background, dark)) light else dark

    /**
     * A plain name for TalkBack and the page ("Teal", "Dark red", "Grey"). Rough on purpose: it says roughly what the
     * colour is, so a choice never rests on seeing it.
     */
    fun name(argb: Int): String {
        val (h, s, v) = toHsv(argb).let { Triple(it[0], it[1], it[2]) }
        if (v < 0.12f) return "Black"
        if (s < 0.12f) return when {
            v > 0.92f -> "White"
            v > 0.6f -> "Light grey"
            v > 0.3f -> "Grey"
            else -> "Dark grey"
        }
        val hue = when {
            h < 15 || h >= 345 -> "Red"
            h < 40 -> "Orange"
            h < 65 -> "Yellow"
            h < 85 -> "Lime"
            h < 150 -> "Green"
            h < 185 -> "Teal"
            h < 205 -> "Cyan"
            h < 250 -> "Blue"
            h < 280 -> "Purple"
            h < 320 -> "Magenta"
            else -> "Pink"
        }
        return when {
            v < 0.45f -> "Dark ${hue.lowercase()}"
            s < 0.4f && v > 0.8f -> "Pale ${hue.lowercase()}"
            else -> hue
        }
    }

    /**
     * Whether [argb] could be taken for one of the stream's signal colours: a strong red (trouble), amber (weak
     * signal) or green (great). The page says so, but the choice stays the streamer's.
     */
    fun looksLikeSignal(argb: Int): Boolean {
        val (h, s, v) = toHsv(argb).let { Triple(it[0], it[1], it[2]) }
        if (s < 0.5f || v < 0.5f) return false
        return h < 12 || h >= 350 || h in 30f..50f || h in 100f..140f
    }
}
