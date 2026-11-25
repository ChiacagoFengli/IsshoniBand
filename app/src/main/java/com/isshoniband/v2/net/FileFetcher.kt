package com.isshoniband.v2.net

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 从主机拉取分轨，存到 App 私有目录 filesDir/tracks/。
 */
class FileFetcher(private val context: Context) {

    fun fetch(
        host: String,
        name: String,
        onProgress: (received: Long, total: Long) -> Unit,
        onDone: (File?, String?) -> Unit
    ) {
        Thread({
            var socket: Socket? = null
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, FileServer.PORT), 4000)
                s.tcpNoDelay = true
                socket = s

                s.getOutputStream().apply {
                    write("GET $name\n".toByteArray())
                    flush()
                }

                val input = s.getInputStream()
                val header = readLine(input)
                if (header == null || !header.startsWith("OK ")) {
                    onDone(null, header?.removePrefix("ERR ") ?: "主机没有响应")
                    return@Thread
                }
                val size = header.substring(3).trim().toLongOrNull() ?: 0L
                if (size <= 0) {
                    onDone(null, "文件大小异常")
                    return@Thread
                }

                val dir = File(context.filesDir, "tracks").apply { mkdirs() }
                val target = File(dir, name)
                val temp = File(dir, "$name.part")

                val buffer = ByteArray(64 * 1024)
                var received = 0L

                FileOutputStream(temp).use { fos ->
                    while (received < size) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        fos.write(buffer, 0, n)
                        received += n
                        onProgress(received, size)
                    }
                    fos.flush()
                }

                if (received != size) {
                    temp.delete()
                    onDone(null, "传输不完整")
                    return@Thread
                }

                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    onDone(null, "保存失败")
                    return@Thread
                }

                Log.i(TAG, "下载完成 $name（$size 字节）")
                onDone(target, null)
            } catch (e: Exception) {
                Log.w(TAG, "下载失败：${e.message}")
                onDone(null, e.message ?: "下载失败")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }, "band-fetch").start()
    }

    private fun readLine(input: InputStream): String? {
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

    private companion object {
        const val TAG = "IsshoniFile"
    }
}
