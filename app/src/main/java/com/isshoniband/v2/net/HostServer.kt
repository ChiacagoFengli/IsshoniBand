package com.isshoniband.v2.net

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 主机侧：TCP Server，一个客户端一条连接 + 一个读线程。
 *
 * 客户端每 500ms 发一次 clockPing，相当于心跳；
 * 读超时 [READ_TIMEOUT_MS] 内一条都没收到就判定掉线（WiFi 断了 TCP 不一定会报错）。
 */
class HostServer(
    private val hostName: String,
    private val roomName: String,
    private val listener: Listener
) {

    interface Listener {
        fun onMembersChanged(members: List<MemberInfo>)
        fun onClientMessage(fromId: String, msg: Msg)
        fun onLog(text: String)
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val clients = ConcurrentHashMap<String, ClientConn>()

    @Volatile private var hostTrackName = ""
    @Volatile private var hostEndUs = 0L

    /** 新成员加入（或重连）时补发的当前状态，由 Session 提供 */
    @Volatile
    var initialMessages: (() -> List<Msg>)? = null

    var port: Int = 9090
        private set

    private inner class ClientConn(
        val id: String,
        val socket: Socket,
        val link: JsonLineLink,
        @Volatile var name: String,
        @Volatile var trackName: String,
        @Volatile var ready: Boolean = false,
        @Volatile var endUs: Long = 0L
    ) {
        fun readLoop() {
            while (true) {
                val msg = link.read() ?: break
                listener.onClientMessage(id, msg)
            }
            disconnect(id)
        }
    }

    fun start(preferredPort: Int = 9090): Boolean {
        stop()
        var socket: ServerSocket? = null
        var usedPort = preferredPort
        for (candidate in preferredPort..(preferredPort + 9)) {
            try {
                socket = ServerSocket(candidate).apply { reuseAddress = true }
                usedPort = candidate
                break
            } catch (_: Exception) {
            }
        }
        val server = socket ?: return false
        serverSocket = server
        port = usedPort
        Log.i(TAG, "监听 $usedPort")

        acceptThread = Thread({
            while (true) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                onAccepted(s)
            }
        }, "band-accept").apply { start() }

        return true
    }

    private fun onAccepted(socket: Socket) {
        val id = "c" + System.nanoTime().toString(36)
        val link = JsonLineLink(socket, READ_TIMEOUT_MS)
        val conn = ClientConn(id, socket, link, "成员", "")

        Thread({
            // 第一条必须是 join
            val join = conn.link.read()
            if (join == null || join.type != MsgType.JOIN) {
                conn.link.close()
                return@Thread
            }
            if (join.ver != PROTOCOL_VERSION) {
                conn.link.write(
                    Msg(
                        type = MsgType.REJECTED,
                        from = "host",
                        reason = "版本不一致（主机协议 $PROTOCOL_VERSION，你的 ${join.ver}），请都更新到同一版本"
                    )
                )
                conn.link.close()
                listener.onLog("${join.name.ifBlank { "有人" }} 的版本不一致，已拒绝")
                return@Thread
            }
            conn.name = join.name.ifBlank { "成员" }

            conn.link.write(
                Msg(
                    type = MsgType.ACCEPTED,
                    from = "host",
                    name = roomName,
                    reason = id
                )
            )
            clients[id] = conn
            listener.onLog("${conn.name} 加入了房间")
            Log.i(TAG, "客户端加入 ${conn.id} (${conn.name})")
            runCatching {
                initialMessages?.invoke()?.forEach { conn.link.write(it) }
            }
            broadcastMembers()
            conn.readLoop()
            Log.i(TAG, "客户端读循环结束 ${conn.id}")
        }, "band-client-$id").start()
    }

    fun membersSnapshot(): List<MemberInfo> = buildList {
        add(
            MemberInfo(
                id = "host",
                name = hostName,
                trackName = hostTrackName,
                ready = true,
                endUs = hostEndUs
            )
        )
        clients.values.forEach { c ->
            add(MemberInfo(c.id, c.name, c.trackName, c.ready, c.endUs))
        }
    }

    fun updateHostTrack(trackName: String, endUs: Long) {
        hostTrackName = trackName
        hostEndUs = endUs
        broadcastMembers()
    }

    fun updateTrack(id: String, trackName: String, endUs: Long, ready: Boolean) {
        val c = clients[id] ?: return
        if (c.trackName == trackName && c.endUs == endUs && c.ready == ready) return
        c.trackName = trackName
        c.endUs = endUs
        c.ready = ready
        broadcastMembers()
    }

    @Synchronized
    fun broadcastMembers() {
        val members = membersSnapshot()
        listener.onMembersChanged(members)
        // 先发完这一版快照，再处理失联成员，避免嵌套广播把旧名单送到最后。
        val message = Msg(type = MsgType.MEMBERS, from = "host", members = members)
        val failed = clients.values.toList().filterNot { it.link.write(message) }
        failed.forEach { disconnect(it.id) }
    }

    fun broadcast(msg: Msg) {
        clients.values.toList().forEach { c ->
            if (!c.link.write(msg)) {
                Log.w(TAG, "广播失败，断开 ${c.id}（type=${msg.type}）")
                disconnect(c.id)
            }
        }
    }

    fun sendTo(id: String, msg: Msg) {
        clients[id]?.let { c ->
            if (!c.link.write(msg)) disconnect(id)
        }
    }

    private fun disconnect(id: String) {
        val c = clients.remove(id) ?: return
        Log.i(TAG, "客户端断开 $id (${c.name})")
        c.link.close()
        listener.onLog("${c.name} 离开了房间")
        broadcastMembers()
    }

    fun stop() {
        Log.i(TAG, "关闭房间")
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.let {
            try {
                it.join(300)
            } catch (_: InterruptedException) {
            }
        }
        acceptThread = null
        clients.values.toList().forEach { it.link.close() }
        clients.clear()
    }

    private companion object {
        const val TAG = "IsshoniHost"
        const val READ_TIMEOUT_MS = 8_000
    }
}
