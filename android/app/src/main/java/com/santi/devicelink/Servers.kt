package com.santi.devicelink

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class WsServer(port: Int,
               private val onMsg: (String) -> Unit,
               private val onHello: () -> Unit) :
    WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
        isTcpNoDelay = true
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        try { onHello() } catch (_: Exception) {}
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (message != null) try { onMsg(message) } catch (_: Exception) {}
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {}
    override fun onError(conn: WebSocket?, ex: Exception?) {}
    override fun onStart() {}

    fun bcast(bytes: ByteArray) {
        try { broadcast(ByteBuffer.wrap(bytes)) } catch (_: Exception) {}
    }

    fun bcastTxt(s: String) {
        try { broadcast(s) } catch (_: Exception) {}
    }
}

class HttpBridge(private val port: Int, private val html: () -> String) : Thread("dlhttp") {
    @Volatile private var alive = true

    override fun run() {
        try {
            val srv = java.net.ServerSocket(port)
            while (alive) {
                val s = try { srv.accept() } catch (e: Exception) { if (alive) continue else break }
                Thread {
                    try {
                        s.soTimeout = 4000
                        val br = s.getInputStream().bufferedReader()
                        val line = br.readLine() ?: return@Thread
                        while (true) { val l = br.readLine() ?: break; if (l.isEmpty()) break }
                        val body = if (line.startsWith("GET /info")) {
                            "Content-Type: application/json\r\n" + "\r\n" +
                                """{"app":"DeviceLink proto","port_ws":8765}"""
                        } else {
                            val h = html()
                            "Content-Type: text/html; charset=utf-8\r\n" + "\r\n" + h
                        }
                        val out = s.getOutputStream()
                        out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n" + body).toByteArray())
                        out.flush(); s.close()
                    } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
                }.start()
            }
        } catch (_: Exception) {}
    }

    fun closeAll() { alive = false }
}
