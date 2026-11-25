package com.isshoniband.v2.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 节拍器 click 音色，按输出采样率预先合成好（浮点，−1..1）。
 *
 * 旧版是 900/1200Hz 纯正弦、峰值只有满幅的 37%，在鼓声里很容易被盖住（鼓手反馈"太小"）。
 * 新音色：
 * - 主频 + 一个非整数倍泛音，听感接近木鱼 / 牛铃，穿透力强；
 * - 1ms 起音 + 指数衰减，开头最响，干脆不拖泥带水；
 * - 峰值约 0.9 满幅（−1 dBFS）。再要更响走「增益」，由混音限幅兜底。
 * 重音（小节第一拍）音高更高、稍长。
 */
class ClickSynth(sampleRate: Int) {

    val accent: FloatArray = render(sampleRate, freq = 2_000.0, lengthMs = 70, decayMs = 16.0)
    val normal: FloatArray = render(sampleRate, freq = 1_400.0, lengthMs = 55, decayMs = 12.0)

    private fun render(rate: Int, freq: Double, lengthMs: Int, decayMs: Double): FloatArray {
        val n = rate * lengthMs / 1000
        val attack = (rate / 1000).coerceAtLeast(1)
        val out = FloatArray(n)
        var peak = 0.0
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-t * 1000.0 / decayMs)
            val s = sin(2 * PI * freq * t) + 0.45 * sin(2 * PI * freq * 2.76 * t) + 0.2 * sin(2 * PI * freq * 5.4 * t)
            raw[i] = s * env
            if (kotlin.math.abs(raw[i]) > peak) peak = kotlin.math.abs(raw[i])
        }
        // 末尾 3ms 线性收尾，防止截断爆音
        val tail = (rate * 3 / 1000).coerceAtMost(n)
        val norm = if (peak > 0) PEAK / peak else 0.0
        for (i in 0 until n) {
            var v = raw[i] * norm
            val fromEnd = n - i
            if (fromEnd < tail) v *= fromEnd.toDouble() / tail
            out[i] = v.toFloat()
        }
        return out
    }

    private companion object {
        const val PEAK = 0.9
    }
}

/** 音量与增益换算 */
object Gain {
    /** 增益档位（dB）：0 = 原声，往上是数字放大，靠软限幅防破音 */
    val STEPS_DB = listOf(0, 3, 6, 9, 12)

    fun dbToLinear(db: Int): Float = Math.pow(10.0, db / 20.0).toFloat()

    /** 音量百分比（0~100）× 增益 */
    fun linear(volumePercent: Int, gainDb: Int, muted: Boolean): Float =
        if (muted) 0f else volumePercent.coerceIn(0, 100) / 100f * dbToLinear(gainDb)

    /**
     * 软限幅：|x| < 0.8 原样通过；以上平滑压到不超过 1。
     * 比硬削波（直接截断）听感好得多，增益拉高也不会刺耳。
     */
    fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val y = KNEE + (1f - KNEE) * (over / (1f + over))
        return if (x < 0) -y else y
    }

    private const val KNEE = 0.8f
}
