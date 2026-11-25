package com.isshoniband.v2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.prefs.DevicePreset
import com.isshoniband.v2.prefs.PresetStore

/**
 * 设备设置弹窗：首页和房间共用同一份数据。
 *
 * 延迟补偿 / 震动开关 / 震动强度 / 震动补偿 / 节拍器音量，
 * 改了就变「自定义参数」，点保存才能覆盖到某套预设。
 */
@Composable
fun DeviceSettingsDialog(
    store: PresetStore,
    audioOffset: Int,
    hapticOffset: Int,
    hapticEnabled: Boolean,
    hapticStrength: Int,
    metroVolume: Int,
    onAudioOffsetChange: (Int) -> Unit,
    onHapticOffsetChange: (Int) -> Unit,
    onHapticEnabledChange: (Boolean) -> Unit,
    onHapticStrengthChange: (Int) -> Unit,
    onMetroVolumeChange: (Int) -> Unit,
    /** 传 null 表示当前场景不提供测试入口（比如房间里），只显示一行说明 */
    onStartLatencyTest: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var devices by remember { mutableStateOf(store.devices()) }
    var selected by remember { mutableStateOf(store.selectedDevice()) }
    var showSave by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    // 当前参数是否还等于选中的那套预设
    val current = devices.firstOrNull { it.name == selected }
    val label = when {
        current == null -> if (selected.isBlank()) "未选择" else "自定义参数"
        current.audioOffsetMs != audioOffset ||
            current.hapticOffsetMs != hapticOffset ||
            current.hapticEnabled != hapticEnabled ||
            current.hapticStrength != hapticStrength ||
            current.metronomeVolume != metroVolume -> "自定义参数"
        else -> selected
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OffsetStepper(
                    label = "延迟补偿",
                    value = audioOffset,
                    onValueChange = onAudioOffsetChange,
                    onInfo = { showInfo = true },
                    signed = false,
                    min = 0,
                    max = 2500,
                    smallStep = 10,
                    bigStep = 100
                )

                if (onStartLatencyTest != null) {
                    TextButton(
                        onClick = onStartLatencyTest,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text("测一下延迟")
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("节拍震动", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "实验性功能，精度受设备限制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hapticEnabled,
                        onCheckedChange = onHapticEnabledChange
                    )
                }

                if (hapticEnabled) {
                    MiniSlider(
                        label = "震动强度",
                        value = hapticStrength,
                        onValueChange = onHapticStrengthChange
                    )
                    OffsetStepper(
                        label = "震动补偿",
                        value = hapticOffset,
                        onValueChange = onHapticOffsetChange,
                        min = -300,
                        max = 300,
                        smallStep = 10,
                        bigStep = 50
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                MiniSlider(
                    label = "节拍器音量",
                    value = metroVolume,
                    onValueChange = onMetroVolumeChange
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设备预设", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showSave = true }) { Text("保存") }
                }

                if (devices.isNotEmpty()) {
                    Text(
                        "点一条套用它的参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    devices.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.saveDevice(d)
                                    onAudioOffsetChange(d.audioOffsetMs)
                                    onHapticOffsetChange(d.hapticOffsetMs)
                                    onHapticEnabledChange(d.hapticEnabled)
                                    onHapticStrengthChange(d.hapticStrength)
                                    onMetroVolumeChange(d.metronomeVolume)
                                    store.selectDevice(d.name)
                                    selected = d.name
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "延迟 ${d.audioOffsetMs}ms · 震动 " +
                                        if (d.hapticEnabled) "${d.hapticStrength}%" else "关" +
                                        " · 节拍 ${d.metronomeVolume}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                store.deleteDevice(d.name)
                                devices = store.devices()
                                selected = store.selectedDevice()
                            }) {
                                Text("删", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    if (showSave) {
        SaveDevicePresetDialog(
            store = store,
            audioOffset = audioOffset,
            hapticOffset = hapticOffset,
            hapticEnabled = hapticEnabled,
            hapticStrength = hapticStrength,
            metroVolume = metroVolume,
            onSaved = { name ->
                devices = store.devices()
                selected = name
                showSave = false
            },
            onDismiss = { showSave = false }
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("延迟补偿") },
            text = {
                Text(
                    "声音偏晚时，增加补偿。",
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
private fun SaveDevicePresetDialog(
    store: PresetStore,
    audioOffset: Int,
    hapticOffset: Int,
    hapticEnabled: Boolean,
    hapticStrength: Int,
    metroVolume: Int,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var devices by remember { mutableStateOf(store.devices()) }
    var newName by remember { mutableStateOf("") }

    fun persist(name: String) {
        store.saveDevice(
            DevicePreset(
                name = name,
                audioOffsetMs = audioOffset,
                hapticOffsetMs = hapticOffset,
                hapticEnabled = hapticEnabled,
                hapticStrength = hapticStrength,
                metronomeVolume = metroVolume
            )
        )
        onSaved(name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存设备预设") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "覆盖已有预设，或另存一套新的",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                devices.forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { persist(d.name) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(d.name, modifier = Modifier.weight(1f))
                        Text(
                            "覆盖",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (devices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新预设名字") },
                    placeholder = { Text("AirPods / 有线耳机") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { persist(newName.trim()) },
                enabled = newName.isNotBlank()
            ) { Text("另存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 简易滑块：0~100，右侧显示百分比 */
@Composable
internal fun MiniSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "$value%",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 设备摘要：给首页和房间显示一行当前状态 */
fun deviceSummary(
    store: PresetStore,
    audioOffset: Int,
    hapticEnabled: Boolean
): String {
    val name = store.selectedDevice()
    val base = if (name.isBlank()) "未选择预设" else name
    val haptic = if (hapticEnabled) "震动开" else "震动关"
    return "$base · 延迟 ${audioOffset}ms · $haptic"
}
