package com.isshoniband.v2.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.audio.RehearsalPlayer
import com.isshoniband.v2.sync.Clock
import com.isshoniband.v2.sync.Grid
import com.isshoniband.v2.sync.PlanState
import com.isshoniband.v2.sync.RoomPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 粗调结束后的兜底窗口（毫秒）。
 * 延迟上限 2500ms 再加 500ms 敲击余量，保证最后一下不会被截掉。
 */
private const val CALIBRATION_TAIL_MS = 3000L

/**
 * 校准用的私有时间基准：本机自己当主机（偏移 0），不进任何房间。
 */
private class LocalTimeBase : RehearsalPlayer.TimeBase {
    @Volatile var p: RoomPlan = RoomPlan()
    override fun plan(): RoomPlan = p
    override fun offsetUs(): Double = 0.0
    override fun ready(): Boolean = true
}

/**
 * 延迟测试。
 *
 * 第一轮（粗调）：打 8 拍，第 5 拍点第一下、第 8 拍点最后一下，共四下；取中位数。
 * 第二轮（精调）：一直打拍，屏幕按当前偏移延后闪光，调到「看到和听到同时」为止。
 *
 * 2.2.0 起播放器已经自动扣除系统报告的输出延迟，这里测出来的是「系统没算进去的那部分」
 * （主要是蓝牙耳机），所以有线耳机 / 外放通常接近 0，旧版测的数值需要重测。
 *
 * 全程**不修改任何设置**，只给出参考数值。
 */
@Composable
fun LatencyTestDialog(onDismiss: () -> Unit) {
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480
    val bpm = 120
    val totalBeats = 8
    val tapFromBeat = 5

    // 0 = 说明（未启动），1 = 进行中，2 = 结果，3 = 精调
    var phase by remember { mutableIntStateOf(0) }
    var beatCount by remember { mutableIntStateOf(0) }
    var taps by remember { mutableStateOf(listOf<Int>()) }
    // 一共点了几下（含过早的那几下）
    var tapCount by remember { mutableIntStateOf(0) }
    var earlyCount by remember { mutableIntStateOf(0) }
    var coarse by remember { mutableIntStateOf(0) }
    var fineOffset by remember { mutableIntStateOf(0) }
    // 第 5 拍之前就按了，坐实抢跑
    var jumpStart by remember { mutableStateOf(false) }
    val needTaps = totalBeats - tapFromBeat + 1

    // 第 5~8 拍各自「应当听到」的时刻（毫秒，单调时钟），按序号和第几次点击一一对应
    val beatTimes = remember { mutableListOf<Long>() }

    // 和排练房用的是同一个播放器：测出来的就是排练时真实要补的那部分
    val timeBase = remember { LocalTimeBase() }
    val player = remember { RehearsalPlayer() }
    var clickStartUs by remember { mutableLongStateOf(0L) }
    var clickBeatUs by remember { mutableLongStateOf(500_000L) }
    var runId by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        player.timeBase = timeBase
        player.audioOffsetMs = 0
        player.clickGain = 1f
        player.start()
        onDispose {
            timeBase.p = RoomPlan()
            Thread({ player.release() }, "band-cal-teardown").start()
        }
    }

    fun startClicks(tempo: Int) {
        val start = Clock.nowUs() + 600_000L
        clickStartUs = start
        clickBeatUs = 60_000_000L / tempo
        runId += 1
        timeBase.p = RoomPlan(
            epoch = runId.toLong(),
            state = PlanState.PLAYING,
            positionUs = 0L,
            startAtHostUs = start,
            musicFromUs = Long.MAX_VALUE / 4,
            clicksFromUs = 0L,
            grid = Grid(bpm = tempo, beatsPerBar = 4, anchorUs = 0L, active = true)
        )
    }

    fun stopClicks() {
        timeBase.p = RoomPlan()
    }

    fun resetTest() {
        taps = emptyList()
        tapCount = 0
        earlyCount = 0
        jumpStart = false
        beatTimes.clear()
        beatCount = 0
    }

    // 结算：把有效那几下取平均，没有有效值就是 0
    fun finishCoarse() {
        stopClicks()
        coarse = if (taps.isEmpty()) 0 else taps.average().toInt()
        phase = 2
    }

    // 点满四下立即结算；没点满就等第 8 拍后兜底
    LaunchedEffect(tapCount, beatCount, phase) {
        if (phase != 1) return@LaunchedEffect
        if (tapCount >= needTaps) {
            finishCoarse()
        }
    }

    LaunchedEffect(beatCount, phase) {
        if (phase != 1 || beatCount < totalBeats) return@LaunchedEffect
        delay(CALIBRATION_TAIL_MS)
        if (phase == 1) finishCoarse()
    }

    // 进入不同阶段时控制节拍器
    LaunchedEffect(phase) {
        when (phase) {
            1 -> {
                startClicks(bpm)
                // 拍点时刻直接由计划算出（就是声音到耳朵的时刻），不依赖回调
                for (k in 0 until totalBeats) {
                    val t = clickStartUs + k * clickBeatUs
                    val wait = (t - Clock.nowUs()) / 1000
                    if (wait > 0) delay(wait)
                    if (phase != 1) break
                    beatCount = k + 1
                    if (beatCount >= tapFromBeat) beatTimes.add(t / 1000)
                }
                // 第 8 拍响完立刻收声，别让后面的拍子干扰判断
                delay(clickBeatUs / 1000 - 50)
                stopClicks()
            }
            3 -> startClicks(60)  // 精调用慢速（1 秒一拍），偏移值不会跨到下一拍
            else -> stopClicks()
        }
    }

    fun tap() {
        if (phase != 1) return
        if (tapCount >= needTaps) return
        // 第 5 拍还没响就按，直接判抢跑
        if (beatCount < tapFromBeat) {
            jumpStart = true
            stopClicks()
            return
        }
        val idx = tapCount
        if (idx >= beatTimes.size) {
            // 对应的那一拍还没响，说明抢在拍前了：照样计数，别把点击吞掉
            earlyCount += 1
            tapCount += 1
            return
        }
        val diff = (Clock.nowUs() / 1000 - beatTimes[idx]).toInt()
        tapCount += 1
        // 提前按下的一律不要，延迟补偿也不接受负值
        if (diff < 0) earlyCount += 1 else taps = taps + diff
    }

    if (jumpStart) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("还没到第 5 拍") },
            text = {
                Text(
                    "等第 5 拍响起来再点。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    jumpStart = false
                    resetTest()
                    phase = 0
                }) { Text("重新开始") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("退出") }
            }
        )
        return
    }

    when (phase) {
        0 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("延迟测试") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "听前四拍，从第 5 拍起跟着点四下。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "记下测试结果，" +
                            "再填入设备的延迟补偿。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    resetTest()
                    phase = 1
                }) { Text("开始测试") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )

        1 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("延迟测试") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            when {
                                beatCount == 0 -> "准备…"
                                beatCount < tapFromBeat -> "第 $beatCount / $totalBeats 拍"
                                tapCount < needTaps -> "${tapCount + 1} / $needTaps  下"
                                else -> "点完了"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { tap() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (compactHeight) 64.dp else 100.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = tapCount < needTaps
                    ) {
                        Text(
                            when {
                                beatCount < tapFromBeat -> "准备"
                                tapCount >= needTaps -> "点完了"
                                else -> "跟着点"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )

        2 -> {
            val noData = taps.isEmpty()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (noData) "没有测到有效数值" else "粗略估计") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!noData) {
                            Text(
                                "$coarse ms",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (earlyCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (noData) {
                                    "$earlyCount 下都比拍点早，这次没法算。"
                                } else {
                                    "有 $earlyCount 下按早了，已忽略"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    CenteredControls {
                        TextButton(onClick = {
                            resetTest()
                            phase = 0
                        }) { Text("重新测量") }
                        TextButton(onClick = onDismiss) { Text("退出") }
                        TextButton(onClick = {
                            fineOffset = coarse.coerceIn(0, 2500)
                            phase = 3
                        }) { Text("试听并精调") }
                    }
                }
            )
        }

        else -> FineTuneDialog(
            compactHeight = compactHeight,
            offset = fineOffset,
            onOffsetChange = { fineOffset = it.coerceIn(0, 2500) },
            startUs = clickStartUs,
            beatUs = clickBeatUs,
            onDone = {
                stopClicks()
                onDismiss()
            },
            onCancel = {
                stopClicks()
                onDismiss()
            }
        )
    }
}

/** 精调：一直打拍，屏幕按当前偏移延后闪光，调到看到和听到同时为止 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FineTuneDialog(
    compactHeight: Boolean,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
    startUs: Long,
    beatUs: Long,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val offsetState = rememberUpdatedState(offset)

    // 第 k 拍的闪光时刻 = 拍点（声音应到耳朵的时刻）+ 当前偏移
    LaunchedEffect(startUs, beatUs) {
        if (startUs == 0L) return@LaunchedEffect
        var k = 0L
        while (true) {
            val target = startUs + k * beatUs + offsetState.value * 1000L
            val wait = (target - Clock.nowUs()) / 1000
            if (wait < -50) {
                k++
                continue
            }
            if (wait > 0) delay(wait)
            scope.launch {
                pulse.snapTo(1f)
                pulse.animateTo(0f, tween(durationMillis = 320))
            }
            k++
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("调到同步为止") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "对照闪光和拍声。" +
                        "拍声偏晚就按 ＋。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(if (compactHeight) 8.dp else 16.dp))

                val accent = MaterialTheme.colorScheme.primary
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (compactHeight) 2 else 1,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(
                        Modifier
                            .align(Alignment.CenterVertically)
                            .size(if (compactHeight) 88.dp else 150.dp)
                    ) {
                        val r = size.minDimension / 2f * (0.55f + 0.45f * pulse.value)
                        drawCircle(
                            color = accent.copy(alpha = 0.75f * pulse.value),
                            radius = r
                        )
                    }
                    Box(Modifier.widthIn(max = 248.dp).align(Alignment.CenterVertically)) {
                        OffsetStepper(
                            label = "延迟补偿",
                            value = offset,
                            onValueChange = onOffsetChange,
                            signed = false,
                            max = 2500,
                            smallStep = 10,
                            bigStep = 100
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "调好后把这个数值填进延迟补偿，或存成设备预设。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onDone) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
