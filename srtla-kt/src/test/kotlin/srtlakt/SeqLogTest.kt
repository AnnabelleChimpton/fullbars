// SPDX-License-Identifier: Apache-2.0
package srtlakt

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeqLogTest {
    private fun SeqLog.entries(): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var s = first()
        while (s != SeqLog.NIL) { out += key(s) to value(s); s = next(s) }
        return out
    }

    @Test
    fun `keeps insertion order, moves re-entered keys to the tail and drops the oldest when full`() {
        val log = SeqLog(4)
        for (k in 1L..4L) log.put(k, k * 10)
        log.put(2, 99) // update in place
        assertEquals(listOf(1L to 10L, 2L to 99L, 3L to 30L, 4L to 40L), log.entries())
        log.putLast(2, 7) // a retransmission re-enters at the tail
        assertEquals(listOf(1L, 3L, 4L, 2L), log.entries().map { it.first })
        log.put(5, 50) // full: the oldest goes
        assertEquals(listOf(3L, 4L, 2L, 5L), log.entries().map { it.first })
        assertFalse(1L in log)
        assertEquals(7L, log.remove(2, -1))
        assertEquals(-1L, log.remove(2, -1))
        assertEquals(3, log.size)
        log.clear()
        assertEquals(0, log.size)
        log.put(8, 1)
        assertEquals(listOf(8L to 1L), log.entries())
    }

    @Test
    fun `behaves like a LinkedHashMap under random use, sequence wrap included`() {
        val rnd = Random(7)
        val max = 64
        val log = SeqLog(max)
        val ref = LinkedHashMap<Long, Long>()
        var next = 0x7fffff00L
        repeat(200_000) { step ->
            when (rnd.nextInt(10)) {
                in 0..4 -> { // new packet
                    val k = next and 0x7fffffff; next++
                    if (k !in ref && ref.size == max) ref.remove(ref.keys.first())
                    ref.remove(k); ref[k] = step.toLong()
                    log.putLast(k, step.toLong())
                }
                5 -> { // resend an old one
                    val k = ref.keys.elementAtOrNull(rnd.nextInt(ref.size + 1)) ?: return@repeat
                    ref.remove(k); ref[k] = -step.toLong()
                    log.putLast(k, -step.toLong())
                }
                6, 7 -> { // ack something
                    val k = (next - rnd.nextInt(80)) and 0x7fffffff
                    assertEquals(ref.remove(k) ?: Long.MIN_VALUE, log.remove(k, Long.MIN_VALUE))
                }
                8 -> { // prune from the head while walking
                    var s = log.first()
                    var n = rnd.nextInt(4)
                    while (s != SeqLog.NIL && n-- > 0) { ref.remove(log.key(s)); s = log.removeSlot(s) }
                }
                else -> {
                    val k = (next - rnd.nextInt(100)) and 0x7fffffff
                    assertEquals(ref[k] ?: -1L, log.get(k, -1L))
                    assertEquals(k in ref, k in log)
                }
            }
            if (step % 997 == 0) assertEquals(ref.entries.map { it.key to it.value }, log.entries())
        }
        assertEquals(ref.size, log.size)
        assertTrue(log.size <= max)
    }
}
