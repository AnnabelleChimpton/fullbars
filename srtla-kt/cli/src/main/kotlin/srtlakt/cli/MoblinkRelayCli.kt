// SPDX-License-Identifier: Apache-2.0
package srtlakt.cli

import srtlakt.moblink.MoblinkRelay
import java.io.File
import java.util.UUID

/**
 * `srtla-send-kt moblink-relay --streamer HOST[:PORT] --password-file FILE [--name NAME]`: a Moblink helper on a
 * desktop, for testing a streaming phone's helper support without a second phone. Forwards over this machine's
 * default route. The password is read from a file so it never appears in a process list or shell history.
 */
fun moblinkRelayMain(args: List<String>) {
    var streamer: String? = null
    var passwordFile: String? = null
    var name = "Desktop helper"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--streamer" -> streamer = args[++i]
            "--password-file" -> passwordFile = args[++i]
            "--name" -> name = args[++i]
            else -> error("unknown option ${args[i]}")
        }
        i++
    }
    val s = streamer ?: error("--streamer is required")
    val password = File(passwordFile ?: error("--password-file is required")).readText().trim()
    val host = s.substringBeforeLast(':').takeIf { ':' in s } ?: s
    val port = s.substringAfterLast(':').toIntOrNull()?.takeIf { ':' in s } ?: 7777
    val relay = MoblinkRelay(
        host, port, UUID.nameUUIDFromBytes(name.toByteArray()).toString().uppercase(), name, password,
        onState = { println("state: $it") },
        log = { println(it) },
    ).start()
    Runtime.getRuntime().addShutdownHook(Thread { relay.stop() })
    var lastUp = 0L
    while (true) {
        Thread.sleep(5_000)
        val up = relay.bytesUp.get()
        println("up %.2f Mb/s, total up %d KB, down %d KB".format((up - lastUp) * 8 / 5e6, up / 1024, relay.bytesDown.get() / 1024))
        lastUp = up
    }
}
