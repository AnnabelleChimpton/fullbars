// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSocketTest {
    @Test
    fun acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun clientAndServerExchangeMessagesOfEverySize() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val echo = thread {
            WebSocket.accept(server.accept()).use { ws ->
                while (true) ws.send("echo:" + (ws.receive() ?: break))
            }
        }
        val ws = WebSocket.connect(Socket(InetAddress.getLoopbackAddress(), server.localPort), "127.0.0.1", server.localPort)
        for (n in listOf(0, 1, 125, 126, 127, 65535, 65536, 200_000)) {
            val msg = "x".repeat(n) + "é"
            ws.send(msg)
            assertEquals("echo:$msg", ws.receive())
        }
        ws.ping()
        ws.send("after ping")
        assertEquals("echo:after ping", ws.receive())
        ws.close()
        echo.join(2_000)
        server.close()
    }

    @Test
    fun receiveReturnsNullWhenPeerCloses() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread { WebSocket.accept(server.accept()).use { it.send("bye") } }
        val ws = WebSocket.connect(Socket(InetAddress.getLoopbackAddress(), server.localPort), "127.0.0.1", server.localPort)
        assertEquals("bye", ws.receive())
        assertNull(ws.receive())
        server.close()
    }
}
