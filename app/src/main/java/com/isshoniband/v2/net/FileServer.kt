package com.isshoniband.v2.net

import android.content.Context
import android.util.Log
import com.isshoniband.v2.sync.Session
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 分轨分发（独立端口，不占控制通道）。
 *
 * 限速 1MB/s，保证控制消息随时能挤过去；
 * 一旦进入排练状态（播放中 / 节拍器运行中），立即停止发送。
 */
class FileServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var running = false

    @Volatile var sharedFile: File? = null
    @Volatile var sharedName: String = ""

    fun start(port: Int = PORT) {
        if (running) return
        val socket = try {
            ServerSocket(port)
        } catch (e: Exception) {
            Log.w(TAG, "端口 $port 监听失败：${e.message}")
            return
        }
        serverSocket = socket
        running = true
        acceptThread = Thread({ acceptLoop() }, "band-files").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "分发服务监听 $port")
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            Thread({ serve(socket) }, "band-file-conn").start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val request = readLine(input) ?: return
            val name = request.removePrefix("GET ").trim()

            val file = sharedFile
            if (Session.isRehearsing) {
                Log.w(TAG, "拒绝分发：排练中")
                socket.getOutputStream().write("ERR 排练中，分发已暂停\n".toByteArray())
                socket.getOutputStream().flush()
                return
            }
            if (file == null || !file.exists()) {
                Log.w(TAG, "拒绝分发：主机还没共享分轨（请求 $name）")
                socket.getOutputStream().write("ERR 主机还没选分轨\n".toByteArray())
                socket.getOutputStream().flush()
                return
            }
            if (name != sharedName) {
                Log.w(TAG, "拒绝分发：名字不匹配 请求=[$name] 共享=[$sharedName]")
                socket.getOutputStream().write("ERR 文件名不匹配\n".toByteArray())
                socket.getOutputStream().flush()
                return
            }

            val size = file.length()
            val stream = try {
                FileInputStream(file)
            } catch (_: Exception) {
                null
            }
            if (stream == null || size <= 0) {
                Log.w(TAG, "打开文件失败 size=$size")
                socket.getOutputStream().write("ERR 主机打不开这个文件\n".toByteArray())
                return
            }

            val out = DataOutputStream(socket.getOutputStream())
            out.write("OK $size\n".toByteArray())
            out.flush()
            Log.i(TAG, "开始分发 $name（$size 字节）")

            val buffer = ByteArray(CHUNK)
            var sent = 0L
            val start = System.currentTimeMillis()

            stream.use { s ->
                while (running && !Session.isRehearsing) {
                    val n = s.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    sent += n

                    // 限速：按已发送量推算应当耗时
                    val expectedMs = sent * 1000 / RATE_BYTES_PER_SEC
                    val elapsedMs = System.currentTimeMillis() - start
                    if (expectedMs > elapsedMs) {
                        try {
                            Thread.sleep(expectedMs - elapsedMs)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            out.flush()
            Log.i(TAG, "分发结束 $name，共 $sent 字节")
        } catch (e: Exception) {
            Log.w(TAG, "分发中断：${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
            }
            if (b == '\n'.code) return String(out.toByteArray(), Charsets.UTF_8)
            if (b != '\r'.code) out.write(b)
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "分发服务停止")
    }

    companion object {
        const val PORT = 9091
        private const val CHUNK = 64 * 1024
        private const val RATE_BYTES_PER_SEC = 1024 * 1024
        private const val TAG = "IsshoniFile"
    }
}
