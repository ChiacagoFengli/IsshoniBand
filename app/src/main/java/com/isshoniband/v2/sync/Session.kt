package com.isshoniband.v2.sync

import com.isshoniband.v2.rehearsal.RoomController
import android.content.Context
import android.os.Build
import android.util.Log
import com.isshoniband.v2.SessionService
import com.isshoniband.v2.net.ClientLink
import com.isshoniband.v2.net.DiscoveredRoom
import com.isshoniband.v2.net.Discovery
import com.isshoniband.v2.net.FileServer
import com.isshoniband.v2.net.HostServer
import com.isshoniband.v2.net.MemberInfo
import com.isshoniband.v2.net.Msg
import com.isshoniband.v2.net.MsgType
import com.isshoniband.v2.prefs.LocalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class Role { NONE, HOST, CLIENT }

private fun defaultDeviceName(): String = Build.MODEL ?: "我的设备"

/** 本机同一 WiFi 下的 IPv4，用于让别人手动连过来 */
fun localIpAddress(): String = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress
        ?: ""
} catch (_: Exception) {
    ""
}

data class SessionState(
    val screen: String = SCREEN_HOME,
    val role: Role = Role.NONE,
    val roomName: String = "",
    val selfName: String = defaultDeviceName(),
    val members: List<MemberInfo> = emptyList(),
    val connected: Boolean = false,
    /** 客户端：断线后正在自动重连 */
    val reconnecting: Boolean = false,
    /** 客户端：时钟已对上，可以按计划出声（主机恒为 true） */
    val clockReady: Boolean = false,
    val status: String = "",
    val offsetMs: Double = 0.0,
    val rttMs: Double = 0.0,
    val discovered: List<DiscoveredRoom> = emptyList(),
    val error: String = "",
    val hostIp: String = "",
    val hostPort: Int = 0,
    val lastIp: String = "",
    val lastPort: Int = 0,
    val selfId: String = ""
) {
    /** 房间时间轴长度：所有人分轨结束位置里最晚的那个 */
    val roomEndUs: Long get() = members.maxOfOrNull { it.endUs } ?: 0L

    companion object {
        const val SCREEN_HOME = "home"
        const val SCREEN_JOIN = "join"
        const val SCREEN_ROOM = "room"
        const val SCREEN_LIBRARY = "library"
        const val SCREEN_PRESETS = "presets"
    }
}

/**
 * 会话：房间、成员、对时、房间计划（RoomPlan）。
 *
 * 主机是唯一权威：所有播放状态变化都由主机生成一份新的 [RoomPlan] 整份广播。
 * 客户端只照着算，不做本地推测。音频 / 节拍器 / 震动怎么按计划出声，见 audio 包。
 */
object Session {

    private var appContext: Context? = null
    private var discovery: Discovery? = null
    private var host: HostServer? = null
    @Volatile private var client: ClientLink? = null
    @Volatile private var acceptNextPlan = false
    private var fileServer: FileServer? = null
    private var scope: CoroutineScope? = null

    /** 广播保序：所有发往网络的消息走同一个单线程 */
    private var netScope: CoroutineScope? = null

    /** 用户主动离开时置 true，阻止自动重连 */
    @Volatile private var leaving = false

    /** 排练中（计划处于 PLAYING）时禁止局域网分发 */
    @Volatile
    var isRehearsing = false
        private set

    private val clockSync = ClockSync()
    private val epochCounter = AtomicLong(0)

    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()

    private val _plan = MutableStateFlow(RoomPlan())
    val plan = _plan.asStateFlow()

    /** 客户端最后一次上报的分轨信息，重连 / 对时就绪后要重新报 */
    @Volatile private var lastTrackName = ""
    @Volatile private var lastEndUs = 0L

    fun init(context: Context) {
        if (appContext == null) {
            val app = context.applicationContext
            appContext = app
            discovery = Discovery(app)
            val saved = LocalPrefs(app).deviceName
            if (saved.isNotBlank()) {
                _state.update { it.copy(selfName = saved) }
            }
        }
    }

    // ---------- 时钟换算（音频线程会高频调用，保持轻量） ----------

    /** 本机时钟 − 主机时钟（微秒）。主机为 0 */
    fun offsetUs(): Double =
        if (_state.value.role == Role.CLIENT) clockSync.best()?.first ?: 0.0 else 0.0

    /** 时钟能不能用来按计划出声 */
    fun clockReady(): Boolean = when (_state.value.role) {
        Role.HOST -> true
        Role.CLIENT -> clockSync.ready
        Role.NONE -> false
    }

    fun hostNowUs(): Long = (Clock.nowUs() - offsetUs()).toLong()

    // ---------- 导航 ----------

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _state.update { it.copy(selfName = trimmed) }
        appContext?.let { LocalPrefs(it).deviceName = trimmed }
    }

    fun goJoin() {
        _state.update { it.copy(screen = SessionState.SCREEN_JOIN, error = "") }
        refreshRooms()
    }

    fun goHome() {
        discovery?.stopDiscovery()
        _state.update { it.copy(screen = SessionState.SCREEN_HOME, error = "") }
    }

    fun goLibrary() {
        _state.update { it.copy(screen = SessionState.SCREEN_LIBRARY, error = "") }
    }

    fun goPresets() {
        _state.update { it.copy(screen = SessionState.SCREEN_PRESETS, error = "") }
    }

    fun refreshRooms() {
        _state.update { it.copy(discovered = emptyList()) }
        discovery?.startDiscovery(
            onRoom = { room ->
                _state.update { st ->
                    if (st.discovered.any { it.host == room.host && it.port == room.port }) {
                        st
                    } else {
                        st.copy(discovered = st.discovered + room)
                    }
                }
            },
            onLost = { name ->
                _state.update { st ->
                    st.copy(discovered = st.discovered.filterNot { it.name == name })
                }
            }
        )
    }

    /** 主机共享当前分轨，供其他人拉取 */
    fun shareTrack(file: File?, name: String) {
        fileServer?.sharedFile = file
        fileServer?.sharedName = name
        Log.i(TAG, "共享分轨=$name")
    }

    // ---------- 建房 / 加入 / 离开 ----------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun newScopes() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        netScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    }

    fun createRoom(roomName: String) {
        leaveRoom()
        leaving = false
        val selfName = _state.value.selfName

        val newHost = HostServer(selfName, roomName, object : HostServer.Listener {
            override fun onMembersChanged(members: List<MemberInfo>) {
                _state.update { it.copy(members = members) }
            }

            override fun onClientMessage(fromId: String, msg: Msg) {
                when (msg.type) {
                    MsgType.CLOCK_PING -> {
                        val now = Clock.nowUs()
                        host?.sendTo(
                            fromId,
                            Msg(type = MsgType.CLOCK_PONG, from = "host", t1 = msg.t1, t2 = now, t3 = now)
                        )
                    }

                    MsgType.TRACK -> host?.updateTrack(fromId, msg.trackName, msg.endUs, msg.ready)
                }
            }

            override fun onLog(text: String) {
                _state.update { it.copy(status = text) }
            }
        })

        if (!newHost.start()) {
            _state.update { it.copy(error = "端口被占用，房间创建失败") }
            return
        }

        // 新成员（含重连）进来：直接给他当前整份计划，他自己算该从哪接
        newHost.initialMessages = {
            listOf(Msg(type = MsgType.PLAN, from = "host", plan = _plan.value))
        }

        host = newHost
        discovery?.register(roomName, newHost.port)
        appContext?.let { ctx -> fileServer = FileServer(ctx).apply { start() } }
        newScopes()
        epochCounter.set(0)
        setPlan(RoomPlan())

        _state.update {
            it.copy(
                screen = SessionState.SCREEN_ROOM,
                role = Role.HOST,
                roomName = roomName,
                connected = true,
                clockReady = true,
                status = "房间里还没有别人",
                members = newHost.membersSnapshot(),
                error = "",
                hostIp = localIpAddress(),
                hostPort = newHost.port,
                selfId = "host"
            )
        }
        newHost.broadcastMembers()
        appContext?.let {
            startRoomAudio(it)
            SessionService.start(it, "主持中 · $roomName")
        }
    }

    fun joinRoom(roomName: String, ip: String, port: Int) {
        leaveRoom()
        leaving = false
        newScopes()
        setPlan(RoomPlan())
        _state.update {
            it.copy(
                screen = SessionState.SCREEN_ROOM,
                role = Role.CLIENT,
                roomName = roomName,
                connected = false,
                clockReady = false,
                status = "连接中…",
                error = "",
                lastIp = ip,
                lastPort = port
            )
        }
        appContext?.let {
            startRoomAudio(it)
            SessionService.start(it, "已加入 · $roomName")
        }
        scope?.launch { connectLoop(ip, port, firstAttempt = true) }
    }

    /** 连上为止；断线后由 onDisconnected 再次进入 */
    private suspend fun connectLoop(ip: String, port: Int, firstAttempt: Boolean) {
        var attempt = 0
        while (!leaving && _state.value.role == Role.CLIENT) {
            // 读线程和写失败都可能报断开，同一条连接只处理一次
            val handled = AtomicBoolean(false)
            val link = ClientLink(ip, port, _state.value.selfName, object : ClientLink.Listener {
                override fun onMessage(msg: Msg) = handleHostMessage(msg)

                override fun onDisconnected(reason: String) {
                    if (!handled.compareAndSet(false, true)) return
                    Log.w(TAG, "连接断开：$reason")
                    // 不清对时样本、不停计划：短暂断网时照旧按原计划出声，排练不中断
                    _state.update {
                        it.copy(connected = false, reconnecting = !leaving, status = "已断开，正在重连…")
                    }
                    if (!leaving) scope?.launch {
                        delay(1_000)
                        connectLoop(ip, port, firstAttempt = false)
                    }
                }

                override fun onLog(text: String) {
                    _state.update { it.copy(status = text) }
                }
            })
            acceptNextPlan = true
            client = link
            val ok = withContext(Dispatchers.IO) { link.connect() }
            if (ok) {
                // 主机可能已经重开过房间（epoch 从头算），重连后的第一份计划无条件接受
                _state.update { it.copy(connected = true, reconnecting = false, status = "已连接，对时中…", error = "") }
                startClientClock(link)
                return
            }
            attempt++
            if (firstAttempt && attempt == 1) {
                _state.update { it.copy(error = "连不上 $ip:$port，正在重试…", reconnecting = true) }
            }
            delay(2_000)
        }
    }

    private val _controller = MutableStateFlow<RoomController?>(null)
    val controller = _controller.asStateFlow()

    fun startRoomAudio(context: Context) {
        if (_controller.value == null && _state.value.role != Role.NONE) {
            _controller.value = RoomController(context.applicationContext).also { it.start() }
        }
    }

    fun releaseRoomAudio() {
        _controller.value?.release()
        _controller.value = null
    }

    fun leaveRoom() {
        releaseRoomAudio()
        leaving = true
        appContext?.let { SessionService.stop(it) }
        fileServer?.stop()
        fileServer = null
        scope?.cancel()
        scope = null
        netScope?.cancel()
        netScope = null
        host?.stop()
        host = null
        client?.close()
        client = null
        discovery?.unregister()
        clockSync.reset()
        lastTrackName = ""
        lastEndUs = 0L
        setPlan(RoomPlan())
        _state.update { SessionState(selfName = it.selfName) }
    }

    // ---------- 主机：播放控制 ----------

    private fun isHost() = _state.value.role == Role.HOST

    /** 起播提前量：给网络传输和各端解码定位留时间。迟到的设备不会被丢弃，只是晚一点接进来 */
    private const val START_LEAD_US = 600_000L

    fun play() {
        if (!isHost()) return
        val cur = _plan.value
        if (cur.isPlaying) return
        val resume = if (cur.state == PlanState.STOPPED) 0L else cur.positionUs
        val p = PlanMath.planPlay(
            resumeUs = resume,
            grid = cur.grid,
            startAtHostUs = Clock.nowUs() + START_LEAD_US,
            epoch = epochCounter.incrementAndGet()
        )
        Log.i(TAG, "播放 resume=${resume}us start=${p.positionUs}us clicksFrom=${p.clicksFromUs}us")
        publish(p)
    }

    fun pause() {
        if (!isHost()) return
        val cur = _plan.value
        if (!cur.isPlaying) return
        publish(PlanMath.planPause(cur, Clock.nowUs(), epochCounter.incrementAndGet()))
    }

    fun stop() {
        if (!isHost()) return
        publish(PlanMath.planStop(_plan.value, epochCounter.incrementAndGet()))
    }

    /** 停着时拖进度（播放中不允许，界面也会禁用） */
    fun seekTo(positionUs: Long) {
        if (!isHost()) return
        val cur = _plan.value
        if (cur.isPlaying) return
        publish(PlanMath.planSeek(cur, positionUs.coerceAtLeast(0L), epochCounter.incrementAndGet()))
    }

    /**
     * 改节拍网格。停着时随便改；播放中只允许改首拍位置（界面负责把其他项锁住），
     * 改完全房间立刻按新网格出拍，时间轴不动。
     */
    fun setGrid(grid: Grid) {
        if (!isHost()) return
        val cur = _plan.value
        val g = grid.copy(
            bpm = grid.bpm.coerceIn(30, 325),
            beatsPerBar = grid.beatsPerBar.coerceIn(1, 12),
            countInBars = grid.countInBars.coerceIn(0, 8)
        )
        if (cur.grid == g) return
        val next = if (cur.isPlaying) {
            cur.copy(
                epoch = epochCounter.incrementAndGet(),
                grid = cur.grid.copy(anchorUs = g.anchorUs)
            )
        } else {
            cur.copy(epoch = epochCounter.incrementAndGet(), grid = g)
        }
        publish(next)
    }

    private fun publish(p: RoomPlan) {
        setPlan(p)
        netScope?.launch {
            host?.broadcast(Msg(type = MsgType.PLAN, from = "host", plan = p))
        }
    }

    private fun setPlan(p: RoomPlan) {
        _plan.value = p
        val rehearsing = p.isPlaying
        if (isRehearsing != rehearsing) {
            isRehearsing = rehearsing
            Log.i(TAG, if (rehearsing) "进入排练状态，分发停止" else "退出排练状态，可以分发")
        }
    }

    // ---------- 成员分轨上报 ----------

    /** @param endUs 本机分轨在房间时间轴上的结束位置，0 = 没有分轨 */
    fun reportTrack(trackName: String, endUs: Long) {
        lastTrackName = trackName
        lastEndUs = endUs
        when (_state.value.role) {
            Role.HOST -> host?.updateHostTrack(trackName, endUs)
            Role.CLIENT -> sendTrackInfo()
            Role.NONE -> Unit
        }
    }

    private fun sendTrackInfo() {
        val link = client ?: return
        val ready = clockSync.ready
        netScope?.launch {
            link.send(Msg(type = MsgType.TRACK, from = "client", trackName = lastTrackName, endUs = lastEndUs, ready = ready))
        }
    }

    // ---------- 客户端 ----------

    private fun handleHostMessage(msg: Msg) {
        when (msg.type) {
            MsgType.ACCEPTED -> {
                _state.update { st ->
                    st.copy(
                        connected = true,
                        status = "已加入房间，对时中…",
                        roomName = msg.name.ifBlank { st.roomName },
                        selfId = msg.reason
                    )
                }
                sendTrackInfo()
            }

            MsgType.REJECTED -> {
                leaving = true
                _state.update { it.copy(error = msg.reason, status = "被主机拒绝", reconnecting = false) }
            }

            MsgType.MEMBERS -> _state.update { it.copy(members = msg.members) }

            MsgType.CLOCK_PONG -> {
                val t4 = Clock.nowUs()
                val wasReady = clockSync.ready
                clockSync.onPong(msg.t1, msg.t2, msg.t3, t4)
                val nowReady = clockSync.ready
                clockSync.best()?.let { (offset, rtt) ->
                    _state.update { st ->
                        st.copy(
                            offsetMs = offset / 1000.0,
                            rttMs = rtt / 1000.0,
                            clockReady = nowReady,
                            status = if (nowReady) "已同步" else st.status
                        )
                    }
                }
                if (nowReady && !wasReady) sendTrackInfo()
            }

            MsgType.PLAN -> {
                val p = msg.plan ?: return
                // 只接受更新的计划，迟到的旧消息直接忽略
                if (acceptNextPlan || p.epoch >= _plan.value.epoch) {
                    acceptNextPlan = false
                    setPlan(p)
                    Log.i(TAG, "收到计划 epoch=${p.epoch} ${p.state} pos=${p.positionUs}us")
                }
            }
        }
    }

    /** 进房先连发一串对时（尽快就绪），之后每 500ms 一次，同时充当心跳 */
    private fun startClientClock(link: ClientLink) {
        scope?.launch {
            var n = 0
            while (isActive && client === link) {
                if (!_state.value.connected) break
                val ok = link.send(Msg(type = MsgType.CLOCK_PING, from = "client", t1 = Clock.nowUs()))
                if (!ok) break
                n++
                delay(if (n < 10) 60L else 500L)
            }
        }
    }

    private const val TAG = "IsshoniSession"
}
