package com.stockwatch.app.panel

import android.content.Context
import android.util.Log
import com.stockwatch.app.core.stringifyJson
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class PanelRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String,
)

data class PanelResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
) {
    companion object {
        fun text(status: Int, body: String, contentType: String = "text/plain; charset=utf-8") =
            PanelResponse(status, contentType, body.toByteArray(Charsets.UTF_8))

        fun json(status: Int, body: String) = text(status, body, "application/json; charset=utf-8")

        fun error(status: Int, message: String) = json(status, stringifyJson(mapOf("error" to message)))
    }
}

fun interface PanelHandler {
    fun handle(request: PanelRequest): PanelResponse
}

class PanelServer(
    context: Context,
    private val handler: PanelHandler,
    private val port: Int = DEFAULT_PORT,
) : Closeable {
    private val context = context.applicationContext

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
        } catch (exc: Exception) {
            socket.close()
            throw exc
        }
        val pool = Executors.newFixedThreadPool(4)
        server = socket
        workers = pool
        running = true
        acceptThread = Thread({ acceptLoop(socket, pool) }, "stock-watch-panel").also { it.start() }
    }

    @Synchronized
    override fun close() {
        if (!running && server == null) return
        running = false
        server?.close()
        server = null
        workers?.shutdownNow()
        workers = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (exc: IOException) {
                if (running) Log.e(TAG, "接受本地连接失败", exc)
                break
            }
            if (!running) {
                client.close()
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 10_000
                val request = readRequest(socket.getInputStream())
                val host = request.headers["host"]
                if (host != "$LOOPBACK:$port" && host != "localhost:$port") {
                    writeResponse(socket.getOutputStream(), PanelResponse.error(403, "仅允许本地面板访问"))
                    return
                }
                val path = request.target.substringBefore('?')
                val response = when {
                    request.method == "GET" && path in STATIC_PATHS -> PanelResponse(
                        200,
                        "text/html; charset=utf-8",
                        context.assets.open("panel.html").use { it.readBytes() },
                    )
                    path.startsWith("/api/") -> handler.handle(request)
                    else -> PanelResponse.error(404, "页面不存在")
                }
                writeResponse(socket.getOutputStream(), response)
            } catch (exc: HttpException) {
                writeResponse(socket.getOutputStream(), PanelResponse.error(exc.status, exc.message ?: "请求无效"))
            } catch (exc: Exception) {
                Log.e(TAG, "处理本地面板请求失败", exc)
                writeResponse(socket.getOutputStream(), PanelResponse.error(500, "面板服务异常"))
            }
        }
    }

    private fun readRequest(input: InputStream): PanelRequest {
        val headerBytes = ByteArrayOutputStream()
        val marker = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (matched < marker.size) {
            val value = input.read()
            if (value < 0) throw EOFException("连接提前结束")
            headerBytes.write(value)
            matched = if (value.toByte() == marker[matched]) matched + 1 else 0
            if (headerBytes.size() > MAX_HEADER_BYTES) throw HttpException(431, "请求头过大")
        }
        val headerText = headerBytes.toString("ISO-8859-1")
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.split(' ') ?: throw HttpException(400, "缺少请求行")
        if (requestLine.size != 3) throw HttpException(400, "请求行无效")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpException(400, "请求头无效")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if ("chunked" in headers["transfer-encoding"].orEmpty().lowercase()) {
            throw HttpException(400, "不支持分块请求")
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw HttpException(413, "请求体不能超过 200 KB")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw HttpException(400, "请求体不完整")
            offset += count
        }
        return PanelRequest(
            method = requestLine[0].uppercase(),
            target = requestLine[1],
            headers = headers,
            body = body.toString(Charsets.UTF_8),
        )
    }

    private fun writeResponse(output: OutputStream, response: PanelResponse) {
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val headers = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private class HttpException(val status: Int, message: String) : IOException(message)

    companion object {
        const val DEFAULT_PORT = 8760
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 200 * 1024
        private val STATIC_PATHS = setOf("/", "/index.html", "/panel.html")
        private const val TAG = "PanelServer"
    }
}
