package com.isshoniband.v2.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.prefs.DevicePreset
import com.isshoniband.v2.prefs.PresetStore
import com.isshoniband.v2.sync.Session
import java.util.Locale

@Composable
fun PresetScreen(context: Context) {

    val store = remember { PresetStore(context) }
    var devices by remember { mutableStateOf(store.devices()) }
    var tracks by remember { mutableStateOf(store.tracks()) }
    var selectedDevice by remember { mutableStateOf(store.selectedDevice()) }
    var editingDevice by remember { mutableStateOf<DevicePreset?>(null) }
    var selectedTrack by remember { mutableStateOf(store.selectedTrack()) }

    var alignments by remember { mutableStateOf(store.alignments()) }
    var selectedAlignment by remember { mutableStateOf(store.selectedAlignment()) }
    var showAlignmentInfo by remember { mutableStateOf(false) }

    NavigationScaffold(
        title = { Text("预设管理", style = MaterialTheme.typography.titleMedium) },
        onBack = Session::goHome
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {


            // ---------------- 曲目预设 ----------------
            SectionTitle(
                title = "曲目预设",
                modifier = Modifier.besideNavigationCapsule(),
                subtitle = "房间共用",
                info = "一首歌一份：BPM、拍号、首拍位置和预备拍。\n\n" +
                    "主机在歌曲设置中套用预设。" +
                    "\n\n" +
                    "在排练房里套用或保存。"
            )

            if (tracks.isEmpty()) {
                EmptyHint("暂无曲目预设")
            } else {
                tracks.forEach { t ->
                    PresetRow(
                        name = t.name,
                        detail = String.format(
                            Locale.US,
                            "%d BPM · %d 拍 · 首拍 %.2fs · 预备 %d 小节",
                            t.bpm,
                            t.beatsPerBar,
                            t.anchorUs / 1_000_000.0,
                            t.countInBars
                        ),
                        selected = t.name == selectedTrack,
                        onDelete = {
                            store.deleteTrack(t.name)
                            tracks = store.tracks()
                            selectedTrack = store.selectedTrack()
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---------------- 音轨对齐（从属于曲目） ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "音轨对齐",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = { showAlignmentInfo = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "说明",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                "各机自己的对齐偏移",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            if (alignments.isEmpty()) {
                EmptyHint("暂无对齐预设")
            } else {
                alignments.forEach { t ->
                    PresetRow(
                        name = t.name,
                        detail = String.format(
                            Locale.US,
                            "偏移 %+d ms",
                            t.trackStartOffsetMs
                        ),
                        selected = t.name == selectedAlignment,
                        onDelete = {
                            store.deleteAlignment(t.name)
                            alignments = store.alignments()
                            selectedAlignment = store.selectedAlignment()
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---------------- 设备预设 ----------------
            SectionTitle(
                title = "设备预设",
                subtitle = "耳机 / 输出链路",
                info = "每套设备记录这台手机接该耳机时的延迟补偿、震动开关、震动强度、" +
                    "震动补偿和节拍器音量。\n\n" +
                    "在首页或排练房的「设备设置」里调好后保存。\n\n" +
                    "这里可以点开修改、设为当前设备，或删除。只影响本机，不会同步给其他人。"
            )

            if (devices.isEmpty()) {
                EmptyHint("暂无设备预设")
            } else {
                devices.forEach { d ->
                    PresetRow(
                        name = d.name,
                        detail = String.format(
                            Locale.US,
                            "延迟 %d ms · 震动 %s · 节拍 %d",
                            d.audioOffsetMs,
                            if (d.hapticEnabled) {
                                "${d.hapticStrength}% / ${d.hapticOffsetMs}ms"
                            } else {
                                "关"
                            },
                            d.metronomeVolume
                        ),
                        selected = d.name == selectedDevice,
                        onClick = {
                            store.selectDevice(d.name)
                            selectedDevice = d.name
                            editingDevice = d
                        },
                        onDelete = {
                            store.deleteDevice(d.name)
                            devices = store.devices()
                            selectedDevice = store.selectedDevice()
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (editingDevice != null) {
        val d = editingDevice!!
        var name by remember(d.name) { mutableStateOf(d.name) }
        var delayMs by remember(d.name) { mutableIntStateOf(d.audioOffsetMs) }
        var hapticOn by remember(d.name) { mutableStateOf(d.hapticEnabled) }
        var hapticStr by remember(d.name) { mutableIntStateOf(d.hapticStrength) }
        var hapticOff by remember(d.name) { mutableIntStateOf(d.hapticOffsetMs) }
        var metroVol by remember(d.name) { mutableIntStateOf(d.metronomeVolume) }

        AlertDialog(
            onDismissRequest = { editingDevice = null },
            title = { Text("修改设备预设") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    OffsetStepper(
                        label = "延迟补偿",
                        value = delayMs,
                        onValueChange = { delayMs = it },
                        signed = false,
                        min = 0,
                        max = 2500,
                        smallStep = 10,
                        bigStep = 100
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("节拍震动", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = hapticOn, onCheckedChange = { hapticOn = it })
                    }

                    if (hapticOn) {
                        MiniSlider(
                            label = "震动强度",
                            value = hapticStr,
                            onValueChange = { hapticStr = it }
                        )
                        OffsetStepper(
                            label = "震动补偿",
                            value = hapticOff,
                            onValueChange = { hapticOff = it },
                            min = -300,
                            max = 300,
                            smallStep = 10,
                            bigStep = 50
                        )
                    }

                    MiniSlider(
                        label = "节拍器音量",
                        value = metroVol,
                        onValueChange = { metroVol = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = name.trim()
                        if (newName.isNotEmpty()) {
                            // 改名时先删掉旧条目，避免留下两份
                            if (newName != d.name) store.deleteDevice(d.name)
                            store.saveDevice(
                                DevicePreset(
                                    name = newName,
                                    audioOffsetMs = delayMs,
                                    hapticOffsetMs = hapticOff,
                                    hapticEnabled = hapticOn,
                                    hapticStrength = hapticStr,
                                    metronomeVolume = metroVol
                                )
                            )
                            devices = store.devices()
                            selectedDevice = store.selectedDevice()
                            editingDevice = null
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingDevice = null }) { Text("取消") }
            }
        )
    }

    if (showAlignmentInfo) {
        AlertDialog(
            onDismissRequest = { showAlignmentInfo = false },
            title = { Text("音轨对齐") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "分轨开头有空白或被裁剪时，可调整偏移。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "进晚了按 ＋，进早了按 −。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "· 本机文件前面多了 2 秒空白 → +2000",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "· 本机文件开头被裁掉 1.5 秒 → −1500",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "在排练房的「音轨对齐」卡片里调好后保存，播放中可以边听边调。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "在排练房里套用或保存。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "分轨已对齐时保持 0 ms。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlignmentInfo = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String = "", info: String? = null, modifier: Modifier = Modifier) {
    var showInfo by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (info != null) {
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "说明",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }

    if (subtitle.isNotBlank()) {
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(10.dp))

    if (showInfo && info != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = {
                Text(
                    info,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun PresetRow(
    name: String,
    detail: String,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDelete) {
                Text("删", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
