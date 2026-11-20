// SPDX-License-Identifier: Apache-2.0
package srtlakt.cli

import srtlakt.SrtlaConfig
import srtlakt.SrtlaEvent
import srtlakt.SrtlaSender
import srtlakt.abr.BitrateController
import srtlakt.abr.DelayPreset
import java.io.File
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.system.exitProcess

private const val USAGE = """
srtla-send-kt — SRTLA sender (srtla-kt) for desktop/container testing

  --listen HOST:PORT              where the local SRT caller sends (default 0.0.0.0:6000)
  --link LABEL=LOCAL_IP@HOST:PORT uplink bound to LOCAL_IP (use * for any), repeatable
  --stats FILE                    append one JSON line of stats per second (default: stdout only)
  --conn-timeout MS               link timeout (default 5000)
  --dedup-ms MS                   drop duplicate receiver control packets within MS (0 = forward all copies)
  --synth HOST:PORT               built-in encoder-shaped source sending MPEG-TS-sized datagrams to an SRT caller
  --bitrate BPS                   synth bitrate (fixed), or the ceiling with --abr (default 6000000)
  --min-bitrate BPS               ABR floor (default 1000000)
  --abr fast|balanced|rock        drive the synth bitrate with the delay-based controller (srtlakt.abr)
"""

fun main(args: Array<String>) {
    if (args.firstOrNull() == "moblink-relay") return moblinkRelayMain(args.drop(1))
    var listen = InetSocketAddress("0.0.0.0", 6000)
    val links = mutableListOf<Triple<String, String, InetSocketAddress>>()
    var statsFile: File? = null
    var connTimeout = 5_000L
    var dedupMs = SrtlaConfig().dedupWindowMs
    var synthTo: InetSocketAddress? = null
    var bitrate = 6_000_000
    var minBitrate = 1_000_000
    var abr: DelayPreset? = null

    var i = 0
    fun next(): String = args.getOrNull(++i) ?: fail("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (args[i]) {
            "--listen" -> listen = hostPort(next())
            "--link" -> {
                val spec = next()
                val label = spec.substringBefore('=')
                val local = spec.substringAfter('=').substringBefore('@')
                links += Triple(label, local, hostPort(spec.substringAfter('@')))
            }
            "--stats" -> statsFile = File(next())
            "--conn-timeout" -> connTimeout = next().toLong()
            "--dedup-ms" -> dedupMs = next().toLong()
            "--synth" -> synthTo = hostPort(next())
            "--bitrate" -> bitrate = next().toInt()
            "--min-bitrate" -> minBitrate = next().toInt()
            "--abr" -> abr = when (next()) {
                "fast" -> DelayPreset.FAST
                "balanced" -> DelayPreset.BALANCED
                "rock" -> DelayPreset.ROCK_SOLID
                else -> fail("--abr must be fast, balanced or rock")
            }
            "-h", "--help" -> { println(USAGE); return }
            else -> fail("unknown argument ${args[i]}")
        }
        i++
    }
    if (links.isEmpty()) fail("at least one --link is required")

    val t0 = System.currentTimeMillis()
    fun ts() = "%7.1f".format((System.currentTimeMillis() - t0) / 1000.0)
    val sender = SrtlaSender(listen, SrtlaConfig(connTimeoutMs = connTimeout, dedupWindowMs = dedupMs), onEvent = { e ->
        println("${ts()} EVENT ${describe(e)}")
    }).start()
    links.forEach { (label, local, remote) ->
        sender.addLink(label, remote) {
            DatagramChannel.open().apply {
                if (local != "*") bind(InetSocketAddress(InetAddress.getByName(local), 0))
            }
        }
    }
    println("${ts()} listening on ${listen.hostString}:${sender.localPort}, ${links.size} link(s)")

    val out: PrintWriter? = statsFile?.let { PrintWriter(it.outputStream().buffered(), true) }
    val controller = abr?.let { BitrateController(minBitrate, bitrate, preset = it) }
    val synth = synthTo?.let { SynthSource(it, controller?.targetBps ?: bitrate).start() }
    Runtime.getRuntime().addShutdownHook(Thread { synth?.stop(); sender.close(); out?.close() })
    if (synth != null) println("${ts()} synth source -> ${synthTo!!.hostString}:${synthTo!!.port} at ${synth.bitrateBps / 1000} kbps" + (abr?.let { ", ABR $it" } ?: ""))
    var nextStatsMs = System.currentTimeMillis() + 1_000
    while (true) {
        Thread.sleep(250)
        if (controller != null && synth != null) synth.bitrateBps = controller.update(System.nanoTime() / 1_000_000, sender.stats)
        if (System.currentTimeMillis() < nextStatsMs) continue
        nextStatsMs += 1_000
        val s = sender.stats
        val linksJson = s.links.joinToString(",") { l ->
            """{"label":"${l.label}","phase":"${l.phase}","window":${l.window},"inFlight":${l.inFlight},""" +
                """"rttMs":${l.rttMs ?: "null"},"kbps":${l.txBitsPerSec / 1000},"naks":${l.naks},"pkts":${l.packetsSent},""" +
                """"minRttMs":${l.minRttMs ?: "null"},"queueMs":${l.queueDelayMs},"deliveredKbps":${l.deliveredBps / 1000},""" +
                """"own":${l.ownInFlight},"cap":${l.capPackets},"overCap":${l.overCapSent}}"""
        }
        val line = """{"t":${(System.currentTimeMillis() - t0) / 1000.0},"group":${s.groupRegistered},""" +
            """"connected":${s.connectedLinks},"kbps":${s.totalTxBitsPerSec / 1000},"dropped":${s.droppedNoLink},""" +
            """"sendErrors":${sender.sendErrors},"queueMs":${s.queueDelayMs},"deliveredKbps":${s.deliveredBps / 1000},""" +
            """"capacityKbps":${s.capacityBps / 1000},"overCap":${s.overCapSent},""" +
            """"targetKbps":${(synth?.bitrateBps ?: 0) / 1000},"abr":"${controller?.lastAction ?: "off"}","links":[$linksJson]}"""
        out?.println(line)
        println("${ts()} " + s.links.joinToString("  ") { "${it.label}:${it.phase.name.take(4)} ${it.txBitsPerSec / 1000}kbps q=${it.queueDelayMs}ms ${it.ownInFlight}/${it.capPackets}" } +
            "  total=${s.totalTxBitsPerSec / 1000}kbps" + (synth?.let { " target=${it.bitrateBps / 1000}kbps" } ?: "") +
            (controller?.let { " abr=${it.lastAction}" } ?: ""))
    }
}

private fun describe(e: SrtlaEvent): String = when (e) {
    is SrtlaEvent.GroupRegistered -> "group registered via link ${e.linkId}"
    SrtlaEvent.GroupLost -> "group lost (receiver forgot us)"
    is SrtlaEvent.LinkUp -> "link up ${e.label}"
    is SrtlaEvent.LinkDown -> "link down ${e.label} (${e.reason})"
    is SrtlaEvent.LinkRefused -> "link refused ${e.label}"
}

private fun hostPort(s: String): InetSocketAddress {
    val host = s.substringBeforeLast(':')
    val port = s.substringAfterLast(':').toIntOrNull() ?: fail("bad HOST:PORT '$s'")
    return InetSocketAddress(host, port)
}

private fun fail(msg: String): Nothing {
    System.err.println("error: $msg\n$USAGE")
    exitProcess(2)
}
