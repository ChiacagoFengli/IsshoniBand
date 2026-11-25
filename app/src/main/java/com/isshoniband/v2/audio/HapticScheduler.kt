package com.isshoniband.v2.audio

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.isshoniband.v2.sync.Clock
import java.util.concurrent.locks.LockSupport

/**
 * 节拍震动调度。
 *
 * 旧版的问题：震动在节拍器写音频的线程里、按「播放头跨过拍点」触发。播放头是成块前进的
 * （一块 10~40ms），所以震动时有时无地提前 / 拖后，听起来"不均匀"；而且震动是跨进程调用，
 * 卡一下还会拖累音频。
 *
 * 新做法：每一拍「耳朵应该听到」的时刻可以直接从房间计划算出来（播放器保证声音就在那一刻到耳朵），
 * 震动线程独立运行：睡到目标前约 1ms，再忙等到点，然后触发。
 * 抖动只剩线程调度（亚毫秒）+ 马达本身的启动延迟（固定值，用「震动补偿」抵掉）。
 *
 * 强弱拍：手机支持触觉原语（线性马达）或振幅控制时，重音更强；都不支持就统一一种，保证均匀。
 */
class HapticScheduler(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasAmplitude: Boolean = vibrator?.hasAmplitudeControl() == true

    private val hasClickPrimitive: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    /** 能否区分强弱拍（给界面提示用） */
    val supportsAccent: Boolean get() = hasClickPrimitive || hasAmplitude

    @Volatile var timeBase: RehearsalPlayer.TimeBase? = null
    @Volatile var accentEnabled = true
    @Volatile var enabled: Boolean = false

    /** 0~1 */
    @Volatile var strength: Float = 1f

    /** 震动补偿（毫秒）：正 = 延后，负 = 提前（抵消马达启动慢） */
    @Volatile var offsetMs: Int = 0

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "band-haptic").apply { start() }
    }

    fun release() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {
        }
        var lastEpoch = Long.MIN_VALUE
        var lastK = Long.MIN_VALUE
        while (running) {
            val tb = timeBase
            val plan = tb?.plan()
            if (tb == null || plan == null || !enabled || !tb.ready() || !plan.isPlaying || !plan.grid.active ||
                vibrator == null
            ) {
                lastEpoch = Long.MIN_VALUE
                if (!sleepMs(20)) return
                continue
            }
            val g = plan.grid
            val offsetUs = tb.offsetUs()
            val hapticUs = offsetMs * 1000.0
            val nowUs = Clock.nowUs().toDouble()
            // 此刻对应的「震动时间轴」位置：目标时刻 = 拍点听到的时刻 + 补偿
            val tNow = plan.timelineAtHostUs(nowUs - offsetUs - hapticUs)
            if (plan.epoch != lastEpoch) {
                lastEpoch = plan.epoch
                lastK = g.beatIndexAtOrAfter(tNow - 3_000.0) - 1
            }
            var k = maxOf(lastK + 1, g.beatIndexAtOrAfter(tNow - 3_000.0))
            if (g.beatTimeUs(k) < plan.clicksFromUs) k = g.beatIndexAtOrAfter(plan.clicksFromUs.toDouble())
            val targetUs = plan.hostUsAtTimeline(g.beatTimeUs(k)) + offsetUs + hapticUs
            val waitUs = targetUs - Clock.nowUs()
            if (waitUs > 25_000) {
                // 离得还远：小睡后重新算（计划可能变了）
                if (!sleepMs(((waitUs - 3_000) / 1000).toLong().coerceIn(1, 20))) return
                continue
            }
            if (waitUs > 1_500) LockSupport.parkNanos(((waitUs - 1_000) * 1000).toLong())
            while (Clock.nowUs() < targetUs && running) {
                // 最后 1ms 忙等，换取亚毫秒精度
            }
            if (!running) return
            // 等待期间计划被换掉了（暂停 / 改首拍）：这一拍作废，重新来
            if (timeBase?.plan()?.epoch != plan.epoch) continue
            fire(accentEnabled && g.posInBar(k) == 0)
            lastK = k
        }
    }

    private fun sleepMs(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun fire(accent: Boolean) {
        val v = vibrator ?: return
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f) return
        try {
            when {
                hasClickPrimitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val scale = if (accent) s else s * 0.55f
                    v.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                            .compose()
                    )
                }

                hasAmplitude -> {
                    val amp = ((if (accent) 255f else 150f) * s).toInt().coerceIn(1, 255)
                    v.vibrate(VibrationEffect.createOneShot(if (accent) 35L else 25L, amp))
                }

                else -> {
                    // 转子马达不能控制强度：所有拍一样，保证均匀
                    v.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "震动失败：${e.message}")
        }
    }

    private companion object {
        const val TAG = "IsshoniHaptic"
    }
}
