package app.irlsupreme.service

import app.irlsupreme.engine.video.AlertTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertOptionsCodecTest {
    private fun decode(m: Map<String, Any?>) = AlertOptionsCodec.decode(MapReader(m))

    @Test fun `with nothing stored everything is as it always was`() {
        val o = decode(emptyMap())
        assertEquals(AlertPlace.AUTO, o.place)
        assertEquals(AlertSize.NORMAL, o.size)
        for (k in AlertKind.entries) {
            assertEquals(AlertKindOptions(), o.of(k))
            assertFalse(o.of(k).isCustom)
        }
        assertEquals(AlertKindOptions(), AlertOptions().of(AlertKind.RAID))
    }

    @Test fun `an install from before keeps its words and gets the defaults for the rest`() {
        val o = decode(mapOf("alert_raid_label" to "Incoming", "alert_raid_message" to "{name} is here", "alerts_on" to true))
        assertEquals(AlertKindOptions(), o.of(AlertKind.RAID))
    }

    @Test fun `what's stored reads back`() {
        val raid = AlertKindOptions(
            on = false, minimum = 25, length = AlertLength.LONG,
            paint = AlertPaint(plate = 0xFF1F3A5F.toInt(), text = 0xFFFFC2DA.toInt(), accent = AlertAccent.STRIPE, accentColor = 0xFFFF5FA2.toInt()),
        )
        val sub = AlertKindOptions(skipPrime = true, resubMonths = 6, length = AlertLength.SHORT)
        val stored = AlertOptionsCodec.encodeKind(AlertKind.RAID, raid) + AlertOptionsCodec.encodeKind(AlertKind.SUB, sub) +
            AlertOptionsCodec.encodeGlobal(AlertPlace.TOP_CENTER, AlertSize.LARGE)
        val o = decode(stored.filterValues { it != null })
        assertEquals(raid, o.of(AlertKind.RAID))
        assertEquals(sub, o.of(AlertKind.SUB))
        assertEquals(AlertPlace.TOP_CENTER, o.place)
        assertEquals(AlertSize.LARGE, o.size)
        assertEquals(AlertKindOptions(), o.of(AlertKind.FOLLOW))
    }

    @Test fun `defaults take no space`() {
        for (k in AlertKind.entries) assertTrue(AlertOptionsCodec.encodeKind(k, AlertKindOptions()).values.all { it == null })
        assertTrue(AlertOptionsCodec.encodeGlobal(AlertPlace.AUTO, AlertSize.NORMAL).values.all { it == null })
    }

    @Test fun `numbers are held to their bounds`() {
        val o = decode(
            mapOf(
                "alert_bits_min" to -5L, "alert_raid_min" to 9_999_999_999L, "alert_tip_min" to 9_999_999_999L,
                "alert_sub_resub_months" to 1, "alert_gift_min" to 3L,
            ),
        )
        assertEquals(0L, o.of(AlertKind.BITS).minimum)
        assertEquals(AlertOptionsCodec.MAX_COUNT, o.of(AlertKind.RAID).minimum)
        assertEquals(AlertOptionsCodec.MAX_TIP, o.of(AlertKind.TIP).minimum)
        assertEquals(2, o.of(AlertKind.SUB).resubMonths)
        assertEquals(3L, o.of(AlertKind.GIFT).minimum)
        assertEquals(AlertOptionsCodec.MAX_MONTHS, AlertOptionsCodec.boundMonths(10_000))
        assertEquals(0, AlertOptionsCodec.boundMonths(-1))
    }

    @Test fun `odd stored values read as the default`() {
        val o = decode(
            mapOf(
                "alert_place" to "SOMEWHERE", "alert_size" to "HUGE", "alert_follow_length" to "FOREVER",
                "alert_follow_accent" to "GLITTER", "alert_follow_on" to "yes",
            ),
        )
        assertEquals(AlertOptions(kinds = o.kinds), o)
        assertEquals(AlertKindOptions(), o.of(AlertKind.FOLLOW))
    }

    @Test fun `options that don't apply to a kind are dropped`() {
        val follow = AlertOptionsCodec.normalize(AlertKind.FOLLOW, AlertKindOptions(minimum = 50, skipPrime = true, resubMonths = 4))
        assertEquals(AlertKindOptions(), follow)
        val bits = AlertOptionsCodec.normalize(AlertKind.BITS, AlertKindOptions(minimum = 50, skipPrime = true))
        assertEquals(AlertKindOptions(minimum = 50), bits)
        val colour = AlertOptionsCodec.normalize(AlertKind.TIP, AlertKindOptions(paint = AlertPaint(plate = 0x00ABCDEF)))
        assertEquals(0xFFABCDEF.toInt(), colour.paint.plate)
    }

    @Test fun `length scales the dwell, Normal being today's and Short and Long about 0_6 and 1_6 times`() {
        assertEquals(4_000L, AlertTiming.dwellMs(AlertTier.QUIET, AlertLength.NORMAL))
        assertEquals(6_000L, AlertTiming.dwellMs(AlertTier.MEDIUM, AlertLength.NORMAL))
        assertEquals(7_200L, AlertTiming.dwellMs(AlertTier.LOUD, AlertLength.NORMAL))
        assertEquals(2_400L, AlertTiming.dwellMs(AlertTier.QUIET, AlertLength.SHORT))
        assertEquals(4_320L, AlertTiming.dwellMs(AlertTier.LOUD, AlertLength.SHORT))
        assertEquals(9_600L, AlertTiming.dwellMs(AlertTier.MEDIUM, AlertLength.LONG))
        assertEquals(11_520L, AlertTiming.dwellMs(AlertTier.LOUD, AlertLength.LONG))
        val card = AlertText.card(SampleAlert.RAID.alert(1), AlertLook(), AlertKindOptions(length = AlertLength.LONG))
        assertEquals(11_520L, card.dwellMs)
    }
}
