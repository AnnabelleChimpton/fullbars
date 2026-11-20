// SPDX-License-Identifier: Apache-2.0
package srtlakt.cli

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A stand-in for a hardware encoder whose bitrate can change every frame (Android's MediaCodec follows a new
 * target within ~0.5 s), which ffmpeg/x264 can't do mid-stream. Emits encoder-shaped bursts: 30 fps, a keyframe 8x the
 * size of a P-frame every [gop] frames, each frame sent back to back as 1316-byte datagrams (7 MPEG-TS null
 * packets) to a local SRT caller, like a muxer would.
 *
 * Only the SRT layer is meaningful downstream (the payload is padding), which is exactly what the bonding
 * benchmark measures: unrecoverable SRT packets at the receiving end.
 */
class SynthSource(
    private val to: InetSocketAddress,
    @Volatile var bitrateBps: Int,
    private val fps: Int = 30,
    private val gop: Int = 60,
    private val keyframeRatio: Int = 8,
) {
    @Volatile private var running = false
    @Volatile var bytesSent = 0L
        private set
    private val thread = Thread(::run, "synth-source").apply { isDaemon = true }

    fun start(): SynthSource {
        running = true
        thread.start()
        return this
    }

    fun stop() {
        running = false
    }

    private fun run() {
        val ch = DatagramChannel.open()
        val datagram = ByteBuffer.allocate(DATAGRAM)
        repeat(DATAGRAM / TS) { i ->
            datagram.put(i * TS, 0x47); datagram.put(i * TS + 1, 0x1F); datagram.put(i * TS + 2, 0xFF.toByte())
            datagram.put(i * TS + 3, 0x10)
            for (b in 4 until TS) datagram.put(i * TS + b, 0xFF.toByte())
        }
        val frameNs = 1_000_000_000L / fps
        val start = System.nanoTime()
        var frame = 0L
        var carry = 0.0 // fractional datagrams carried between frames so the average rate is exact
        while (running) {
            // Average frame = bitrate / fps; within a GOP, (ratio + gop - 1) P-frame units.
            val pUnitBytes = bitrateBps / 8.0 / fps * gop / (keyframeRatio + gop - 1)
            val frameBytes = if (frame % gop == 0L) pUnitBytes * keyframeRatio else pUnitBytes
            val exact = frameBytes / DATAGRAM + carry
            val n = max(1, exact.roundToInt())
            carry = exact - n
            repeat(n) {
                datagram.clear()
                runCatching { ch.send(datagram, to) }
            }
            bytesSent += n.toLong() * DATAGRAM
            frame++
            val wait = start + frame * frameNs - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        ch.close()
    }

    private companion object {
        const val TS = 188
        const val DATAGRAM = 7 * TS
    }
}
