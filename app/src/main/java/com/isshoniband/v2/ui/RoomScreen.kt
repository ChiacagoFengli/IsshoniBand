package com.isshoniband.v2.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.net.FileFetcher
import com.isshoniband.v2.prefs.AlignmentPreset
import com.isshoniband.v2.prefs.PresetStore
import com.isshoniband.v2.rehearsal.RoomController
import com.isshoniband.v2.sync.PlanState
import com.isshoniband.v2.sync.Role
import com.isshoniband.v2.sync.RoomPlan
import com.isshoniband.v2.sync.Session
import com.isshoniband.v2.sync.SessionState
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.ceil

internal fun fmtTime(us: Double): String {
    val total = (us / 1_000_000.0).toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

internal fun localTracks(context: Context): List<File> =
    File(context.filesDir, "tracks").listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

internal fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 房间当前处于哪个阶段（只用于显示） */
private enum class Phase { STOPPED, PAUSED, WAITING, COUNT_IN, PLAYING }

private fun phaseOf(plan: RoomPlan, now: Double): Phase = when {
    plan.state == PlanState.STOPPED -> Phase.STOPPED
    plan.state == PlanState.PAUSED -> Phase.PAUSED
    now < plan.positionUs -> Phase.WAITING
    now < plan.musicFromUs -> Phase.COUNT_IN
    else -> Phase.PLAYING
}

/**
 * 排练房。
 *
 * 主界面只放排练时要盯着 / 随手要调的东西：播放与进度、拍点、音轨对齐、曲目参数摘要、成员。
 * 「歌曲设置」（仅主机）和「本机设置」收进底部面板。
 * 所有出声逻辑在 [RoomController] / RehearsalPlayer 里，这里不碰时间计算。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(state: SessionState, context: Context) {
    val controller by Session.controller.collectAsState()
    val ctrl = controller ?: return
    val plan by Session.plan.collectAsState()
    val isHost = state.role == Role.HOST
    val presetStore = remember { PresetStore(context) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 界面用的「此刻时间轴位置」：播放中 30fps 刷新，停着就是停的位置
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(plan) {
        if (!plan.isPlaying) {
            now = plan.positionUs.toDouble()
            return@LaunchedEffect
        }
        while (true) {
            now = ctrl.timelineNowUs()
            delay(33)
        }
    }
    val phase = phaseOf(plan, now)

    var showSongSheet by remember { mutableStateOf(false) }
    var showMySheet by remember { mutableStateOf(false) }
    var showTrackPicker by remember { mutableStateOf(false) }

    val roomTitle = state.roomName.ifBlank { "排练房" }
    val roomStatus = (if (isHost) "主机" else "成员") + " · ${state.members.size} 人" + if (isHost) "" else " · " + syncLabel(state)
    var confirmLeave by remember { mutableStateOf(false) }
    NavigationScaffold(
        title = {
            Column {
                Text(roomTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(roomStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onBack = { confirmLeave = true },
        backLabel = "离开",
        backDescription = "离开房间",
        bottomBar = {
            if (!landscape) TransportBar(isHost = isHost, plan = plan, phase = phase, compact = false)
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            val availableHeight = maxHeight
            val gap = 12.dp
            val twoPanes = landscape && maxWidth >= 460.dp && maxHeight >= 238.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val paneWidth = if (twoPanes) (maxWidth - gap) / 2 else maxWidth
            FlowRow(
                modifier = Modifier.fillMaxSize().then(
                    if (twoPanes) Modifier else Modifier.verticalScroll(rememberScrollState())
                ),
                maxItemsInEachRow = if (twoPanes) 2 else 1,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Column(
                    modifier = Modifier.width(paneWidth).then(
                        if (twoPanes) Modifier.height(availableHeight) else Modifier
                    ).padding(top = if (landscape) 44.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${plan.grid.bpm} BPM", style = MaterialTheme.typography.headlineSmall)
                        androidx.compose.material3.FilterChip(
                            selected = ctrl.accentEnabled, onClick = ctrl::toggleAccent,
                            label = { Text("首拍重音") },
                            leadingIcon = if (ctrl.accentEnabled) ({
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            }) else null
                        )
                    }
                    BeatIndicator(plan, now, phase)
                    if (landscape && isHost) TransportBar(true, plan, phase, compact = true)
                }
                Column(
                    modifier = Modifier.width(paneWidth).then(
                        if (twoPanes) Modifier.height(availableHeight).verticalScroll(rememberScrollState())
                        else Modifier
                    ).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    if (landscape) {
                        Text("$roomTitle · $roomStatus", style = MaterialTheme.typography.titleSmall)
                    }
                    StatusBanner(state, plan.isPlaying)
                    PlayerCard(ctrl, state, plan, now, context) { showTrackPicker = true }
                    AlignmentCard(ctrl = ctrl, store = presetStore)
                    SongCard(plan = plan, isHost = isHost, onEdit = { showSongSheet = true })
                    MyDeviceCard(ctrl = ctrl, store = presetStore, onOpen = { showMySheet = true })
                    MembersCard(state)
                }
            }
        }
    }

    if (confirmLeave) {
        val leaveLabel = if (isHost) "结束排练房" else "离开排练房"
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("$leaveLabel？") },
            text = if (isHost) ({ Text("所有成员将断开连接。") }) else null,
            confirmButton = { TextButton(onClick = Session::leaveRoom) { Text(if (isHost) "结束" else "离开") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } }
        )
    }

    if (showSongSheet) {
        SongSheet(
            plan = plan,
            now = now,
            isHost = isHost,
            roomEndUs = state.roomEndUs,
            store = presetStore,
            onDismiss = { showSongSheet = false }
        )
    }

    if (showMySheet) {
        MySettingsSheet(ctrl = ctrl, store = presetStore, onDismiss = { showMySheet = false })
    }

    if (showTrackPicker) {
        TrackPickerDialog(
            context = context,
            onPick = { f ->
                if (ctrl.loadTrack(f) && isHost) Session.shareTrack(f, f.name)
                showTrackPicker = false
            },
            onDismiss = { showTrackPicker = false }
        )
    }
}

private fun syncLabel(state: SessionState): String = when {
    state.reconnecting -> "重连中"
    !state.connected -> "未连接"
    !state.clockReady -> "对时中…"
    else -> String.format(Locale.US, "已同步 · 往返 %.0fms", state.rttMs)
}

// ------------------------------------------------------------------
// 底部播放控制
// ------------------------------------------------------------------

@Composable
private fun TransportBar(isHost: Boolean, plan: RoomPlan, phase: Phase, compact: Boolean) {
    val controlSize = if (compact) 52.dp else 76.dp
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = if (compact) 0.dp else 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier else Modifier.navigationBarsPadding())
                .padding(vertical = if (compact) 4.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isHost) {
                Text(
                    "跟随主机 · " + when (phase) {
                        Phase.STOPPED -> "已停止"
                        Phase.PAUSED -> "已暂停"
                        Phase.WAITING -> "准备起播"
                        Phase.COUNT_IN -> "预备拍"
                        Phase.PLAYING -> "播放中"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Row
            }
            // 停止
            Box(
                modifier = Modifier
                    .size(controlSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = "停止" }
                    .clickable { Session.stop() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp))
                )
            }
            Spacer(Modifier.width(48.dp))
            // 播放 / 暂停
            Box(
                modifier = Modifier
                    .size(controlSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = if (plan.isPlaying) "暂停" else "播放" }
                    .clickable { if (plan.isPlaying) Session.pause() else Session.play() },
                contentAlignment = Alignment.Center
            ) {
                val fg = MaterialTheme.colorScheme.onPrimary
                if (plan.isPlaying) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(width = 6.dp, height = 26.dp).background(fg, RoundedCornerShape(2.dp)))
                        Box(Modifier.size(width = 6.dp, height = 26.dp).background(fg, RoundedCornerShape(2.dp)))
                    }
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = fg, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(state: SessionState, playing: Boolean) {
    val text = when {
        state.error.isNotBlank() -> state.error
        state.role == Role.CLIENT && state.reconnecting ->
            if (playing) "正在重连，播放继续。" else "正在重连…"
        state.role == Role.CLIENT && state.connected && !state.clockReady ->
            "正在同步，同步后出声。"
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (state.error.isNotBlank()) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.error.isNotBlank()) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

// ------------------------------------------------------------------
// 播放卡片：分轨、进度、拍点
// ------------------------------------------------------------------

@Composable
private fun PlayerCard(
    ctrl: RoomController,
    state: SessionState,
    plan: RoomPlan,
    now: Double,
    context: Context,
    onPickTrack: () -> Unit
) {
    val isHost = state.role == Role.HOST
    RoomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ctrl.fileName.ifBlank { "未选择分轨" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (ctrl.loaded) "本机分轨 · ${fmtTime(ctrl.trackDurationUs.toDouble())}"
                    else "仅节拍器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onPickTrack) { Text(if (ctrl.loaded) "换分轨" else "选分轨") }
            if (ctrl.loaded) {
                TextButton(onClick = {
                    ctrl.clearTrack()
                    if (isHost) Session.shareTrack(null, "")
                }) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        if (ctrl.errorText.isNotBlank()) {
            Text(ctrl.errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (!isHost) DownloadRow(ctrl, state, plan, context)

        Spacer(Modifier.height(12.dp))

        // 进度：房间时间轴（长度取所有人分轨里最长的）
        val endUs = state.roomEndUs.toDouble()
        val shownUs = now.coerceAtLeast(0.0)
        var seeking by remember { mutableStateOf(false) }
        var seekValue by remember { mutableFloatStateOf(0f) }
        val progress = if (endUs > 0) (shownUs / endUs).toFloat().coerceIn(0f, 1f) else 0f
        Slider(
            value = if (seeking) seekValue else progress,
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                seeking = false
                if (endUs > 0) Session.seekTo((seekValue * endUs).toLong())
            },
            enabled = isHost && !plan.isPlaying && endUs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                fmtTime(if (seeking) seekValue * endUs else shownUs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (endUs > 0) fmtTime(endUs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (ctrl.loaded && state.roomEndUs > 0 && now > 0) {
            val myEnd = (ctrl.trackDurationUs - ctrl.trackOffsetMs * 1000L).toDouble()
            if (myEnd < endUs - 1_000_000 && now >= myEnd) {
                Text(
                    "本机分轨已放完，等其他人",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }


    }
}

@Composable
private fun BeatIndicator(plan: RoomPlan, now: Double, phase: Phase) {
    val g = plan.grid
    val bp = g.beatsPerBar.coerceAtLeast(1)
    val clicking = plan.isPlaying && g.active && now >= plan.clicksFromUs
    val k = g.beatIndexAtOrBefore(now)
    val pos = if (clicking) g.posInBar(k) else -1

    val count = bp.coerceAtMost(12)
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                contentDescription = if (pos >= 0) "第 ${pos + 1} 拍，共 $bp 拍" else "每小节 $bp 拍"
            }
        ) {
            // 留出一整格给每拍；较多拍或窄屏时，间距和圆点一起缩小。
            val step = minOf(50.dp.toPx(), size.width / count)
            val firstX = center.x - (count - 1) * step / 2f
            for (i in 0 until count) {
                val dotCenter = Offset(firstX + i * step, center.y)
                val radius = step * if (i == 0) 0.4f else 0.32f
                if (i == pos) {
                    drawCircle(accent, radius, dotCenter)
                } else {
                    val stroke = minOf(2.dp.toPx(), radius * 0.4f)
                    drawCircle(idle, radius - stroke / 2, dotCenter, style = Stroke(stroke))
                }
            }
        }
        val label = when (phase) {
            Phase.STOPPED -> "已停止"
            Phase.PAUSED -> "已暂停"
            Phase.WAITING -> "准备…"
            Phase.COUNT_IN -> {
                val left = ceil((plan.musicFromUs - now) / g.beatUs).toInt().coerceAtLeast(1)
                "预备拍 · 还有 $left 拍"
            }
            Phase.PLAYING -> if (g.active) "第 ${Math.floorDiv(k, bp.toLong()) + 1} 小节" else "播放中"
        }
        Text(
            label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadRow(ctrl: RoomController, state: SessionState, plan: RoomPlan, context: Context) {
    val hostTrack = state.members.firstOrNull { it.id == "host" }?.trackName ?: ""
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf("") }
    if (ctrl.loaded || hostTrack.isBlank()) return

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            progress = 0f
            error = ""
            val handler = Handler(Looper.getMainLooper())
            FileFetcher(context).fetch(
                state.lastIp,
                hostTrack,
                onProgress = { r, t -> handler.post { progress = if (t > 0) r.toFloat() / t else 0f } },
                onDone = { file, err ->
                    handler.post {
                        progress = -1f
                        if (file != null) ctrl.loadTrack(file) else error = err ?: "下载失败"
                    }
                }
            )
        },
        enabled = progress < 0f && !plan.isPlaying,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) { Text("从主机下载分轨：$hostTrack", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (plan.isPlaying) {
        Text("暂停后可下载分轨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (progress >= 0f) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ------------------------------------------------------------------
// 音轨对齐：每个成员自己的事，随时可调（播放中也行）
// ------------------------------------------------------------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AlignmentCard(ctrl: RoomController, store: PresetStore) {
    var alignments by remember { mutableStateOf(store.alignments()) }
    var selected by remember { mutableStateOf(store.selectedAlignment()) }
    var showPick by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    val current = alignments.firstOrNull { it.name == selected }
    val label = when {
        current == null -> if (selected.isBlank()) "未选择预设" else "自定义"
        current.trackStartOffsetMs != ctrl.trackOffsetMs -> "自定义（改自 ${current.name}）"
        else -> current.name
    }

    var editing by remember { mutableStateOf(false) }
    RoomCard(modifier = Modifier.clickable { editing = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("音轨对齐", style = MaterialTheme.typography.titleMedium)
                Text(String.format(Locale.US, "%+d ms · %s", ctrl.trackOffsetMs, label),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("调整", color = MaterialTheme.colorScheme.primary)
        }
    }

    if (editing) {
        RoomSettingsSheet("音轨对齐", onDismiss = { editing = false }) {
            OffsetStepper(
                label = "本机分轨", hint = "进晚了按 ＋，进早了按 −。",
                value = ctrl.trackOffsetMs, onValueChange = ctrl::updateTrackOffset,
                min = -60000, max = 60000, smallStep = 10, bigStep = 100, fineStep = 1
            )
            CenteredControls {
                TextButton(onClick = { showPick = true }) { Text("选择预设") }
                TextButton(onClick = { showSave = true }) { Text("存为预设") }
            }
        }
    }

    if (showPick) {
        AlertDialog(
            onDismissRequest = { showPick = false },
            title = { Text("对齐预设") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (alignments.isEmpty()) {
                        Text(
                            "暂无对齐预设",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    alignments.forEach { a ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ctrl.updateTrackOffset(a.trackStartOffsetMs)
                                    store.selectAlignment(a.name)
                                    selected = a.name
                                    showPick = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(a.name, modifier = Modifier.weight(1f))
                            Text(
                                String.format(Locale.US, "%+d ms", a.trackStartOffsetMs),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPick = false }) { Text("关闭") } }
        )
    }

    if (showSave) {
        NameDialog(
            title = "保存对齐预设",
            detail = String.format(Locale.US, "当前偏移 %+d ms", ctrl.trackOffsetMs),
            placeholder = "例如：某歌-鼓",
            onSave = { n ->
                store.saveAlignment(AlignmentPreset(n, ctrl.trackOffsetMs))
                alignments = store.alignments()
                selected = n
                showSave = false
            },
            onDismiss = { showSave = false }
        )
    }


}

// ------------------------------------------------------------------
// 歌曲参数摘要
// ------------------------------------------------------------------

internal fun gridSummary(plan: RoomPlan): String {
    val g = plan.grid
    if (!g.active) return "节拍器关闭"
    val countIn = if (g.countInBars > 0) "预备 ${g.countInBars} 小节" else "无预备拍"
    return String.format(Locale.US, "%d BPM · %d 拍/小节 · 首拍 %.2fs · %s", g.bpm, g.beatsPerBar, g.anchorUs / 1_000_000.0, countIn)
}

@Composable
private fun SongCard(plan: RoomPlan, isHost: Boolean, onEdit: () -> Unit) {
    RoomCard(modifier = Modifier.clickable(enabled = isHost) { onEdit() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("歌曲设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    gridSummary(plan),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (isHost) "调整" else "跟随主机",
                style = MaterialTheme.typography.labelMedium,
                color = if (isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

    }
}

// ------------------------------------------------------------------
// 本机设置摘要
// ------------------------------------------------------------------

@Composable
private fun MyDeviceCard(ctrl: RoomController, store: PresetStore, onOpen: () -> Unit) {
    RoomCard(modifier = Modifier.clickable { onOpen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("本机设置", style = MaterialTheme.typography.titleMedium)
                val music = if (ctrl.musicMuted) "音乐关" else "音乐 ${ctrl.musicVolume}%" +
                    (if (ctrl.musicGainDb > 0) " +${ctrl.musicGainDb}dB" else "")
                val metro = if (ctrl.metroMuted) "节拍器关" else "节拍器 ${ctrl.metroVolume}%" +
                    (if (ctrl.metroGainDb > 0) " +${ctrl.metroGainDb}dB" else "")
                Text(
                    "$music · $metro",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    deviceSummary(store, ctrl.audioOffsetMs, ctrl.hapticEnabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("调整", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ------------------------------------------------------------------
// 选分轨
// ------------------------------------------------------------------

@Composable
private fun TrackPickerDialog(context: Context, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val files = remember { localTracks(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择分轨") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (files.isEmpty()) {
                    Text(
                        "曲库为空，可在首页导入分轨。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                files.forEach { f ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(f) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(sizeText(f.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ------------------------------------------------------------------
// 成员
// ------------------------------------------------------------------

@Composable
private fun MembersCard(state: SessionState) {
    RoomCard {
        Text("房间成员", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (state.members.isEmpty()) {
            Text(
                if (state.role == Role.HOST) "还没有人加入" else "等待主机的成员列表…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.members.forEach { m ->
            val isHostMember = m.id == "host"
            val isSelf = m.id == state.selfId
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !m.ready -> MaterialTheme.colorScheme.error
                                isHostMember -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        m.name + (if (isHostMember) " · 主机" else "") + (if (isSelf) " · 本机" else ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        (if (m.trackName.isBlank()) "未选分轨" else m.trackName) +
                            (if (m.endUs > 0) " · 到 ${fmtTime(m.endUs.toDouble())}" else "") +
                            (if (!m.ready) " · 对时中" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (state.role == Role.HOST && state.hostIp.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "房间地址  ${state.hostIp}:${state.hostPort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------
// 通用小部件
// ------------------------------------------------------------------

@Composable
internal fun RoomCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().then(modifier)
    ) {
        Column(modifier = Modifier.padding(18.dp)) { content() }
    }
}

@Composable
internal fun NameDialog(
    title: String,
    detail: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    placeholder = { Text(placeholder) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { name.trim().takeIf { it.isNotEmpty() }?.let(onSave) }, enabled = name.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun SheetSection(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

