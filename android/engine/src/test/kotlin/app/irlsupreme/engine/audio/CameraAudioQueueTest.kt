package app.irlsupreme.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera audio decoder's input: the RTMP read thread queues and moves on; a feeder thread waits on the decoder.
 * A fake decoder withholds its input buffers until the test releases them, standing in for a decoder that stalls.
 */
class CameraAudioQueueTest {
    private class StallingDecoder : DecoderInput {
        val release = CountDownLatch(1)
        val firstAsk = CountDownLatch(1)
        val got = CopyOnWriteArrayList<Long>()
        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            firstAsk.countDown()
            // A real decoder blocks up to the timeout when it has no free buffer.
            return if (release.await(timeoutUs, TimeUnit.MICROSECONDS)) 0 else -1
        }
        override fun queue(index: Int, data: ByteArray, ptsUs: Long) { got += ptsUs / 1000 }
    }

    private fun startFeeder(q: AacFrameQueue, d: DecoderInput, running: () -> Boolean): Thread =
        Thread { feedDecoder(q, d, running) }.apply { isDaemon = true; start() }

    @Test fun `a stalled decoder never holds up the caller, and nothing below the cap is lost`() {
        val q = AacFrameQueue()
        val d = StallingDecoder()
        val running = AtomicBoolean(true)
        val t = startFeeder(q, d) { running.get() }
        var worstNs = 0L
        repeat(40) { i ->
            val start = System.nanoTime()
            q.offer(ByteArray(300), i * 21L)
            worstNs = maxOf(worstNs, System.nanoTime() - start)
        }
        // All 40 were handed over while the decoder still had no buffer to give: the caller never waited on it.
        assertEquals(1L, d.release.count)
        assertTrue("worst offer ${worstNs / 1000} us", worstNs < 5_000_000L)
        d.release.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (d.got.size < 40 && System.nanoTime() < deadline) Thread.yield()
        running.set(false)
        t.join(1000)
        assertEquals(List(40) { it * 21L }, d.got.toList())
        assertEquals(0, q.drops)
    }

    @Test fun `past the cap the oldest frames go, and are counted`() {
        val q = AacFrameQueue(capacity = 50)
        val d = StallingDecoder()
        val running = AtomicBoolean(true)
        val t = startFeeder(q, d) { running.get() }
        q.offer(ByteArray(10), 0)
        assertTrue(d.firstAsk.await(5, TimeUnit.SECONDS)) // the feeder holds frame 0, waiting for a buffer
        for (i in 1..60) q.offer(ByteArray(10), i.toLong())
        assertEquals(10, q.drops)
        assertEquals(50, q.queued)
        d.release.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (d.got.size < 51 && System.nanoTime() < deadline) Thread.yield()
        running.set(false)
        t.join(1000)
        assertEquals(listOf(0L) + (11L..60L).toList(), d.got.toList())
    }

    @Test fun `clear empties the queue and a stopped feeder exits`() {
        val q = AacFrameQueue()
        repeat(5) { q.offer(ByteArray(1), it.toLong()) }
        q.clear()
        assertEquals(0, q.queued)
        assertNull(q.poll(0))
        val d = StallingDecoder()
        val t = startFeeder(q, d) { false }
        t.join(1000)
        assertTrue(!t.isAlive)
        assertTrue(d.got.isEmpty())
    }

    @Test fun `the camera's AudioSpecificConfig is read in full`() {
        // AAC-LC, 48 kHz, stereo: the Pocket 3's expected config.
        val lc = CameraAudio.parseAsc(byteArrayOf(0x11, 0x90.toByte()))!!
        assertEquals(2, lc.objectType); assertEquals(48_000, lc.rate); assertEquals(2, lc.channels)
        // AAC-LC, 44.1 kHz, mono.
        val mono = CameraAudio.parseAsc(byteArrayOf(0x12, 0x08))!!
        assertEquals(44_100, mono.rate); assertEquals(1, mono.channels)
        // An explicit 24-bit rate (index 15): 46 000 Hz, stereo.
        val explicit = CameraAudio.parseAsc(byteArrayOf(0x17, 0x80.toByte(), 0x59, 0xD8.toByte(), 0x10))!!
        assertEquals(46_000, explicit.rate); assertEquals(2, explicit.channels)
        assertNull(CameraAudio.parseAsc(byteArrayOf(0x11)))
    }
}
