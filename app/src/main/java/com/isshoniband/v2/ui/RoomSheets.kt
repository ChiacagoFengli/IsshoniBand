package com.isshoniband.v2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.audio.Gain
import com.isshoniband.v2.prefs.PresetStore
import com.isshoniband.v2.prefs.TrackPreset
import com.isshoniband.v2.rehearsal.RoomController
import com.isshoniband.v2.sync.Grid
import com.isshoniband.v2.sync.RoomPlan
import com.isshoniband.v2.sync.Session
import java.util.Locale

// ------------------------------------------------------------------
// 歌曲设置（仅主机可改）
// ------------------------------------------------------------------

/**
 * 锁定规则：
 * - 停着：全部可改；
 * - 播放中：BPM / 拍号 / 预备拍 / 节拍器开关 / 曲目预设锁定（改了全房间的拍子会乱）；
 *   首拍位置可以改（边听边对），但预备拍阶段不能改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongSheet(
    plan: RoomPlan,
    now: Double,
    isHost: Boolean,
    roomEndUs: Long,
    store: PresetStore,
    onDismiss: () -> Unit
) {
    val g = plan.grid
    val locked = !isHost || plan.isPlaying
    // 只有音乐真的在放（过了预备拍）才允许动首拍
    val anchorEditable = isHost && (!plan.isPlaying || now >= plan.musicFromUs)
    var anchorError by remember { mutableStateOf("") }

    fun applyGrid(grid: Grid) {
        if (!isHost) return
        Session.setGrid(grid)
    }

    fun applyAnchor(us: Long) {
        if (!anchorEditable) return
        val v = us.coerceIn(-30_000_000L, 30_000_000L)
        if (roomEndUs > 0 && v >= roomEndUs) {
            anchorError = "首拍不能超过歌曲长度（${fmtTime(roomEndUs.toDouble())}）"
            return
        }
        anchorError = ""
        applyGrid(g.copy(anchorUs = v))
    }

    RoomSettingsSheet("歌曲设置", onDismiss) {
        if (plan.isPlaying) {
            LockNote("暂停后可调整速度和拍号。")
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("节拍器", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "关掉后只放音乐，没有拍子和震动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = g.active, onCheckedChange = { applyGrid(g.copy(active = it)) }, enabled = !locked)
        }

        SheetColumns(
            first = {
                SheetSection("速度")
                Text(
                    "${g.bpm} BPM",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                CenteredControls {
                    listOf(-10, -1, 1, 10).forEach { delta ->
                        OutlinedButton(
                            onClick = { applyGrid(g.copy(bpm = g.bpm + delta)) },
                            enabled = !locked,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            val label = if (delta < 0) "−${-delta}" else "+$delta"
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Slider(
                    value = g.bpm.coerceIn(30, 300).toFloat(),
                    onValueChange = { applyGrid(g.copy(bpm = it.toInt())) },
                    valueRange = 30f..300f,
                    enabled = !locked,
                    colors = sliderColors()
                )
            },
            second = {
                SheetSection("拍号与预备拍")
                Text("每小节", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { applyGrid(g.copy(beatsPerBar = g.beatsPerBar - 1)) }, enabled = !locked) {
                        Text("−")
                    }
                    Text("${g.beatsPerBar} 拍", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { applyGrid(g.copy(beatsPerBar = g.beatsPerBar + 1)) }, enabled = !locked) {
                        Text("+")
                    }
                }
                Text("预备拍", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "从任意位置按播放，都先打这么多小节再进音乐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CenteredControls {
                    listOf(0, 1, 2, 4).forEach { bars ->
                        FilterChip(
                            selected = g.countInBars == bars,
                            onClick = { applyGrid(g.copy(countInBars = bars)) },
                            enabled = !locked && g.active,
                            label = { Text(if (bars == 0) "无" else "$bars 小节", maxLines = 1, softWrap = false) }
                        )
                    }
                }
            }
        )

        SheetSection("首拍位置")
        OffsetStepper(
            label = "第一小节第一拍在歌里的位置",
            hint = "负值 = 在文件开头之前（音乐会让位，先打拍子）",
            value = (g.anchorUs / 1000L).toInt(),
            onValueChange = { applyAnchor(it.toLong() * 1000L) },
            min = -30000,
            max = 30000,
            smallStep = 10,
            bigStep = 500,
            unit = "ms",
            enabled = anchorEditable
        )
        Button(
            onClick = { applyAnchor(now.toLong()) },
            enabled = isHost && plan.isPlaying && anchorEditable,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("听到第一拍时点这里") }
        Text(
            "听到第一拍时点一下。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (anchorError.isNotBlank()) {
            Text(anchorError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (plan.isPlaying && !anchorEditable) {
            Text("预备拍阶段不能改首拍", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        SheetSection("曲目预设")
        TrackPresetList(store = store, grid = g, locked = locked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomSettingsSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 880.dp,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().statusBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                content = content
            )
        }
    }
}

@Composable
private fun TrackPresetList(store: PresetStore, grid: Grid, locked: Boolean) {
    var tracks by remember { mutableStateOf(store.tracks()) }
    var selected by remember { mutableStateOf(store.selectedTrack()) }
    var showSave by remember { mutableStateOf(false) }

    if (tracks.isEmpty()) {
        Text(
            "暂无曲目预设",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    tracks.forEach { t ->
        val same = t.bpm == grid.bpm && t.beatsPerBar == grid.beatsPerBar &&
            t.anchorUs == grid.anchorUs && t.countInBars == grid.countInBars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !locked) {
                    Session.setGrid(
                        grid.copy(
                            bpm = t.bpm,
                            beatsPerBar = t.beatsPerBar,
                            anchorUs = t.anchorUs,
                            countInBars = t.countInBars
                        )
                    )
                    store.selectTrack(t.name)
                    selected = t.name
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t.name + if (t.name == selected && same) "  ✓" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    String.format(
                        Locale.US,
                        "%d BPM · %d 拍 · 首拍 %.2fs · 预备 %d",
                        t.bpm, t.beatsPerBar, t.anchorUs / 1_000_000.0, t.countInBars
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                store.deleteTrack(t.name)
                tracks = store.tracks()
                selected = store.selectedTrack()
            }) { Text("删", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (locked) {
        Text("排练进行中不能套用预设", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = { showSave = true }) { Text("把当前参数存为预设") }

    if (showSave) {
        NameDialog(
            title = "保存曲目预设",
            detail = String.format(
                Locale.US,
                "%d BPM · %d 拍 · 首拍 %.2fs · 预备 %d 小节",
                grid.bpm, grid.beatsPerBar, grid.anchorUs / 1_000_000.0, grid.countInBars
            ),
            placeholder = "歌名",
            onSave = { n ->
                // 音量是个人设置，不再跟着曲目预设走
                store.saveTrack(
                    TrackPreset(
                        name = n,
                        anchorUs = grid.anchorUs,
                        bpm = grid.bpm,
                        beatsPerBar = grid.beatsPerBar,
                        countInBars = grid.countInBars
                    )
                )
                tracks = store.tracks()
                selected = n
                showSave = false
            },
            onDismiss = { showSave = false }
        )
    }
}

// ------------------------------------------------------------------
// 本机设置
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MySettingsSheet(ctrl: RoomController, store: PresetStore, onDismiss: () -> Unit) {
    var showDevice by remember { mutableStateOf(false) }

    RoomSettingsSheet("本机设置", onDismiss) {
        Text(
            "手机音量键控制总音量。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SheetColumns(
            first = {
                SheetSection("分轨音乐")
                SwitchRow("播放分轨声音", !ctrl.musicMuted) { ctrl.updateMusicMuted(!it) }
                VolumeSlider("音量", ctrl.musicVolume, enabled = !ctrl.musicMuted) { ctrl.updateMusicVolume(it) }
                GainChips(ctrl.musicGainDb, enabled = !ctrl.musicMuted) { ctrl.updateMusicGain(it) }
            },
            second = {
                SheetSection("节拍器")
                SwitchRow("播放节拍器声音", !ctrl.metroMuted) { ctrl.updateMetroMuted(!it) }
                VolumeSlider("音量", ctrl.metroVolume, enabled = !ctrl.metroMuted) { ctrl.updateMetroVolume(it) }
                GainChips(ctrl.metroGainDb, enabled = !ctrl.metroMuted) { ctrl.updateMetroGain(it) }
                Text(
                    "拍声偏小时可增加增益。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        SheetSection("耳机延迟与震动")
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { showDevice = true }
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("延迟补偿 · 震动 · 设备预设", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        deviceSummary(store, ctrl.audioOffsetMs, ctrl.hapticEnabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("调整", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (ctrl.hapticEnabled && !ctrl.haptics.supportsAccent) {
            Text(
                "这台手机的马达不能调强弱，震动不区分重音",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            String.format(
                Locale.US,
                "调试：音频时间戳%s · 音乐误差 %.1fms",
                if (ctrl.player.timestampOk) "可用" else "不可用（精度下降）",
                ctrl.player.lastMusicErrorMs
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
    }

    if (showDevice) {
        DeviceSettingsDialog(
            store = store,
            audioOffset = ctrl.audioOffsetMs,
            hapticOffset = ctrl.hapticOffsetMs,
            hapticEnabled = ctrl.hapticEnabled,
            hapticStrength = ctrl.hapticStrength,
            metroVolume = ctrl.metroVolume,
            onAudioOffsetChange = { ctrl.updateAudioOffset(it) },
            onHapticOffsetChange = { ctrl.updateHapticOffset(it) },
            onHapticEnabledChange = { ctrl.updateHapticEnabled(it) },
            onHapticStrengthChange = { ctrl.updateHapticStrength(it) },
            onMetroVolumeChange = { ctrl.updateMetroVolume(it) },
            onDismiss = { showDevice = false }
        )
    }
}

/** 两组设置在宽窗口并排；字体放大时，给每组预留相应的宽度。 */
@Composable
private fun SheetColumns(
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pairedWidth = 576.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth >= pairedWidth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), content = first)
                Column(Modifier.weight(1f), content = second)
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                first()
                second()
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(12.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
            colors = sliderColors(),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text("$value%", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GainChips(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Text("增益（dB）", style = MaterialTheme.typography.labelMedium)
    CenteredControls {
        Gain.STEPS_DB.forEach { db ->
            FilterChip(
                selected = value == db,
                onClick = { onChange(db) },
                enabled = enabled,
                label = { Text(if (db == 0) "原声" else "+$db", maxLines = 1, softWrap = false) }
            )
        }
    }
}

@Composable
private fun LockNote(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.outline
)

