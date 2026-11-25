package com.isshoniband.v2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isshoniband.v2.BuildConfig
import com.isshoniband.v2.prefs.LocalPrefs
import com.isshoniband.v2.prefs.PresetStore
import com.isshoniband.v2.sync.Session
import com.isshoniband.v2.sync.SessionState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: SessionState) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val titleSpacing = if (landscape) 16.dp else 56.dp
    val themeMode by Appearance.mode.collectAsState()
    val paletteId by Appearance.palette.collectAsState()
    val darkNow = when (themeMode) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        else -> isSystemInDarkTheme()
    }
    var showCreate by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(state.selfName) }

    // 设备属性：首页是主要设置入口，改了立即持久化
    val appCtx = LocalContext.current.applicationContext
    val prefs = remember { LocalPrefs(appCtx) }
    val presetStore = remember { PresetStore(appCtx) }
    var showDeviceSettings by remember { mutableStateOf(false) }
    var showCalibrate by remember { mutableStateOf(false) }
    var audioOffset by remember { mutableIntStateOf(prefs.audioOffsetMs) }
    var hapticOffset by remember { mutableIntStateOf(prefs.hapticOffsetMs) }
    var hapticEnabled by remember { mutableStateOf(prefs.hapticEnabled) }
    var hapticStrength by remember { mutableIntStateOf(prefs.hapticStrength) }
    var metroVolume by remember { mutableIntStateOf(prefs.metronomeVolume) }

    LaunchedEffect(audioOffset) { prefs.audioOffsetMs = audioOffset }
    LaunchedEffect(hapticOffset) { prefs.hapticOffsetMs = hapticOffset }
    LaunchedEffect(hapticEnabled) { prefs.hapticEnabled = hapticEnabled }
    LaunchedEffect(hapticStrength) { prefs.hapticStrength = hapticStrength }
    LaunchedEffect(metroVolume) { prefs.metronomeVolume = metroVolume }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
    ) {
        Spacer(Modifier.height(titleSpacing))

        Text(
            "一休尼帮多",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "I S S H O N I   B A N D",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(titleSpacing))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (landscape && configuration.screenWidthDp >= 600) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BigAction(
                title = "创建排练房",
                subtitle = "主持排练",
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1f),
                onClick = { showCreate = true }
            )
            BigAction(
                title = "加入排练房",
                subtitle = "查找局域网房间",
                icon = Icons.Filled.Search,
                modifier = Modifier.weight(1f),
                onClick = { Session.goJoin() }
            )
            BigAction(
                title = "本地曲库",
                subtitle = "导入与管理分轨",
                icon = Icons.Filled.List,
                modifier = Modifier.weight(1f),
                onClick = { Session.goLibrary() }
            )
            BigAction(
                title = "预设管理",
                subtitle = "曲目、音轨对齐和设备",
                icon = Icons.Filled.Settings,
                modifier = Modifier.weight(1f),
                onClick = { Session.goPresets() }
            )
        }

        Spacer(Modifier.height(20.dp))

        Surface(
            onClick = {
                nameInput = state.selfName
                showNameDialog = true
            },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "我的名字",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        state.selfName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "修改",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            onClick = { showDeviceSettings = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "设备设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        deviceSummary(presetStore, audioOffset, hapticEnabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "修改",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (showDeviceSettings) {
            DeviceSettingsDialog(
                store = presetStore,
                audioOffset = audioOffset,
                hapticOffset = hapticOffset,
                hapticEnabled = hapticEnabled,
                hapticStrength = hapticStrength,
                metroVolume = metroVolume,
                onAudioOffsetChange = { audioOffset = it },
                onHapticOffsetChange = { hapticOffset = it },
                onHapticEnabledChange = { hapticEnabled = it },
                onHapticStrengthChange = { hapticStrength = it },
                onMetroVolumeChange = { metroVolume = it },
                onStartLatencyTest = {
                    showDeviceSettings = false
                    showCalibrate = true
                },
                onDismiss = { showDeviceSettings = false }
            )
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            onClick = { showCalibrate = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "延迟测试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "测一下这台设备大概延迟多少",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "修改",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (showCalibrate) {
            LatencyTestDialog(onDismiss = { showCalibrate = false })
        }

        if (state.error.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    "外观",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Appearance.FOLLOW_SYSTEM to "跟随系统",
                        Appearance.LIGHT to "浅色",
                        Appearance.DARK to "深色"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = themeMode == value,
                            onClick = { Appearance.set(context, value) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 384.dp) 8 else 4
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Palettes.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                row.forEach { palette ->
                                    val primary = if (darkNow) palette.primaryDark else palette.primaryLight
                                    val secondary = if (darkNow) palette.accentDark else palette.accentLight
                                    val selected = palette.id == paletteId
                                    Box(
                                        modifier = Modifier.weight(1f).height(48.dp)
                                            .selectable(selected, role = Role.RadioButton) {
                                                Appearance.setPalette(context, palette.id)
                                            }.semantics { contentDescription = palette.label },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val ring = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        Canvas(Modifier.size(38.dp).then(ring).padding(4.dp).clip(CircleShape)) {
                                            drawRect(secondary)
                                            drawPath(Path().apply {
                                                moveTo(0f, 0f)
                                                lineTo(size.width, 0f)
                                                lineTo(0f, size.height)
                                                close()
                                            }, primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("给房间起个名字") },
            text = {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    singleLine = true,
                    placeholder = { Text("例如：周五排练") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    Session.createRoom(roomName.ifBlank { "排练房" })
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("你的名字") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    placeholder = { Text("起个响亮的名字") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Session.setName(nameInput)
                    showNameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
