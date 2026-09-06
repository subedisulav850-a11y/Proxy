package com.sulav.proxy

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal local forwarding proxy for debugging your own app's HTTP traffic.
 *
 * It listens on [listenHost]:[listenPort] and forwards every request it
 * receives to [targetUrl], relaying the response back to the caller and
 * reporting a summary of each exchange via [onEvent].
 *
 * This is a plain request/response relay - it does not perform TLS
 * interception, certificate injection, or any credential extraction.
 */
class ProxyServer(
    private val listenHost: String,
    private val listenPort: Int,
    private val targetUrl: String,
    private val onEvent: (ProxyEvent) -> Unit,
    private val onError: (String) -> Unit
) {
    data class ProxyEvent(
        val method: String,
        val endpoint: String,
        val status: Int,
        val requestHex: String,
        val responseHex: String
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val acceptThread = Thread {
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(listenHost, listenPort))
            serverSocket = socket
            while (running.get()) {
                try {
                    val client = socket.accept()
                    pool.execute { handleClient(client) }
                } catch (e: SocketException) {
                    if (running.get()) onError("Accept failed: ${e.message}")
                }
            }
        } catch (e: IOException) {
            onError("Could not bind $listenHost:$listenPort - ${e.message}")
            running.set(false)
        }
    }

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        acceptThread.start()
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 15_000
                val input = BufferedInputStream(c.getInputStream())
                val requestBytes = readHttpMessage(input) ?: return
                val requestLine = requestBytes.decodeToString()
                val firstLine = requestLine.substringBefore("\r\n")
                val parts = firstLine.split(" ")
                val method = parts.getOrElse(0) { "?" }
                val path = parts.getOrElse(1) { "/" }

                val target = URI(targetUrl)
                val targetHost = target.host ?: "127.0.0.1"
                val targetPort = if (target.port != -1) target.port else if (target.scheme == "https") 443 else 80

                var status = 0
                var responseBytes = ByteArray(0)

                try {
                    Socket().use { upstream ->
                        upstream.connect(InetSocketAddress(targetHost, targetPort), 10_000)
                        upstream.soTimeout = 15_000

                        val rewritten = rewriteHostHeader(requestBytes, "$targetHost:$targetPort")
                        upstream.getOutputStream().write(rewritten)
                        upstream.getOutputStream().flush()

                        val upstreamInput = BufferedInputStream(upstream.getInputStream())
                        responseBytes = readHttpMessage(upstreamInput) ?: ByteArray(0)
                        status = parseStatusCode(responseBytes)
                    }
                } catch (e: IOException) {
                    val body = "Upstream unreachable: ${e.message}"
                    val resp = buildString {
                        append("HTTP/1.1 502 Bad Gateway\r\n")
                        append("Content-Type: text/plain\r\n")
                        append("Content-Length: ${body.toByteArray().size}\r\n")
                        append("Connection: close\r\n\r\n")
                        append(body)
                    }
                    responseBytes = resp.toByteArray()
                    status = 502
                }

                val out: OutputStream = c.getOutputStream()
                out.write(responseBytes)
                out.flush()

                onEvent(
                    ProxyEvent(
                        method = method,
                        endpoint = path,
                        status = status,
                        requestHex = requestBytes.take(256).toByteArray().toHexString(),
                        responseHex = responseBytes.take(256).toByteArray().toHexString()
                    )
                )
            } catch (e: SocketTimeoutException) {
                onError("Client timed out: ${e.message}")
            } catch (e: IOException) {
                onError("Connection error: ${e.message}")
            }
        }
    }

    /** Reads a full HTTP message (headers + body, honoring Content-Length) off [input]. */
    private fun readHttpMessage(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var prev4 = IntArray(4) { -1 }
        var b: Int
        while (input.read().also { b = it } != -1) {
            buffer.write(b)
            prev4[0] = prev4[1]; prev4[1] = prev4[2]; prev4[2] = prev4[3]; prev4[3] = b
            if (prev4[0] == '\r'.code && prev4[1] == '\n'.code && prev4[2] == '\r'.code && prev4[3] == '\n'.code) {
                break
            }
        }
        if (buffer.size() == 0) return null

        val headerText = buffer.toByteArray().decodeToString()
        val contentLength = Regex("(?i)content-length:\\s*(\\d+)")
            .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var remaining = contentLength
        while (remaining > 0) {
            val chunk = ByteArray(minOf(remaining, 8192))
            val read = input.read(chunk)
            if (read == -1) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return buffer.toByteArray()
    }

    private fun rewriteHostHeader(request: ByteArray, newHost: String): ByteArray {
        val text = request.decodeToString()
        val rewritten = if (Regex("(?im)^host:.*$").containsMatchIn(text)) {
            Regex("(?im)^host:.*$").replaceFirst(text, "Host: $newHost")
        } else {
            text.replaceFirst("\r\n", "\r\nHost: $newHost\r\n")
        }
        return rewritten.encodeToByteArray()
    }

    private fun parseStatusCode(response: ByteArray): Int {
        if (response.isEmpty()) return 0
        val line = response.decodeToString().substringBefore("\r\n")
        return Regex("HTTP/\\d\\.\\d (\\d{3})").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
