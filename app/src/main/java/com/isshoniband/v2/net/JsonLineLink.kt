package com.isshoniband.v2.net

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Socket + JSON Lines 读写。一行一条消息，UTF-8。
 *
 * @param readTimeoutMs 读超时：对端这么久一条消息都没有就当掉线（0 = 不超时）。
 *   两端都有 500ms 一次的对时消息，正常情况下不会触发。
 */
class JsonLineLink(private val socket: Socket, readTimeoutMs: Int = 0) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val reader: BufferedReader
    private val writer: BufferedWriter

    init {
        socket.tcpNoDelay = true
        if (readTimeoutMs > 0) socket.soTimeout = readTimeoutMs
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    }

    /**
     * 阻塞读一条。
     * 对端关闭返回 null；解析失败的消息**跳过**，不当作断开（否则一条脏数据就断线）。
     */
    fun read(): Msg? {
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                Log.w(TAG, "readLine 异常：${e.message}")
                return null
            } ?: return null

            if (line.isBlank()) continue

            try {
                return json.decodeFromString<Msg>(line)
            } catch (e: Exception) {
                Log.w(TAG, "跳过无法解析的消息：${e.message} / $line")
                continue
            }
        }
    }

    @Synchronized
    fun write(msg: Msg): Boolean = try {
        val text = json.encodeToString(Msg.serializer(), msg)
        writer.write(text)
        writer.write("\n")
        writer.flush()
        true
    } catch (e: Exception) {
        Log.w(TAG, "write 失败：${e.javaClass.name} / ${e.message}", e)
        false
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TAG = "IsshoniNet"
    }
}
