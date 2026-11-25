package com.isshoniband.v2.net

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 客户端侧：连主机、收发消息。
 */
class ClientLink(
    private val host: String,
    private val port: Int,
    private val selfName: String,
    private val listener: Listener
) {

    interface Listener {
        fun onMessage(msg: Msg)
        fun onDisconnected(reason: String)
        fun onLog(text: String)
    }

    @Volatile private var lastReceivedNs = 0L
    private var socket: Socket? = null
    private var link: JsonLineLink? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    fun connect(timeoutMs: Int = 4000): Boolean {
        close()
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            link = JsonLineLink(s, READ_TIMEOUT_MS)
            lastReceivedNs = System.nanoTime()
            running = true

            link!!.write(
                Msg(type = MsgType.JOIN, from = "client", name = selfName, ver = PROTOCOL_VERSION)
            )

            thread = Thread({
                while (running) {
                    val msg = link?.read()
                    if (msg == null) {
                        if (running) Log.w(TAG, "读循环结束：对端关闭或读异常")
                        break
                    }
                    try {
                        lastReceivedNs = System.nanoTime()
                        listener.onMessage(msg)
                    } catch (e: Exception) {
                        Log.e(TAG, "处理消息异常（${msg.type}）", e)
                    }
                }
                if (running) {
                    running = false
                    listener.onDisconnected("读取中断，距上条消息 ${(System.nanoTime() - lastReceivedNs) / 1_000_000}ms")
                }
            }, "band-client-read").apply { start() }
            Log.i(TAG, "已连接 $host:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "连接失败 $host:$port — ${e.message}")
            try {
                s.close()
            } catch (_: Exception) {
            }
            listener.onLog("连接失败：${e.message}")
            false
        }
    }

    fun send(msg: Msg): Boolean {
        val ok = link?.write(msg) ?: false
        if (!ok && running) {
            running = false
            Log.w(TAG, "写失败，判定断开")
            try {
                link?.close()
            } catch (_: Exception) {
            }
            listener.onDisconnected("连接已断开")
        }
        return ok
    }

    fun close() {
        running = false
        try {
            link?.close()
        } catch (_: Exception) {
        }
        link = null
        socket = null
        thread = null
    }

    private companion object {
        const val TAG = "IsshoniClient"

        /** 主机每 500ms 回一次 clockPong，5 秒没消息就是断了 */
        const val READ_TIMEOUT_MS = 5_000
    }
}
