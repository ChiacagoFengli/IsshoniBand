package com.isshoniband.v2.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.isshoniband.v2.sync.RoomPlan
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 排练播放器：本机唯一的音频输出。分轨音乐 + 节拍器 click 混在同一条 AudioTrack 里。
 *
 * ## 为什么混在一条轨道里
 * 两条 AudioTrack 各有各的缓冲和启动延迟，节拍器和音乐在同一台手机上就可能错开。
 * 混在一起后，同一台设备上二者在样本级别对齐，不可能互相漂。
 *
 * ## 怎么跟房间对齐（动态校准）
 * 轨道从进房间起一直在跑（没东西放就写静音）。渲染线程每 5ms 写一块，写之前先算：
 * 1. 这一块第一帧「真正出声」的时刻 —— 用 AudioTrack.getTimestamp() 换算，已经包含了系统报告的输出延迟；
 * 2. 加上本机的延迟补偿（蓝牙等系统没报准的额外延迟）= 耳朵听到这一帧的时刻；
 * 3. 用房间计划 [RoomPlan] 算出这一刻耳朵应该听到的时间轴位置 T；
 * 4. 节拍器：把落在这一块里的拍点直接合成进去；
 * 5. 音乐：文件里应该在放的帧 D = T + 音轨对齐偏移。和实际读到的位置比：
 *    - 差 1ms 以内不管；
 *    - 差 30ms 以内用 ±0.4% 以内的微调速度慢慢追（听不出来）；
 *    - 更大（起播、改偏移、断续）就直接补静音或跳帧，起播时提前定位、精确卡在应进的那一帧。
 *
 * 所以：任意时刻按播放、中途进房、改对齐偏移，都不需要特殊处理，每一块都会自己算对。
 */
class RehearsalPlayer {

    /** 播放器从哪里拿房间计划和时钟偏移 */
    interface TimeBase {
        fun plan(): RoomPlan
        /** 本机单调时钟 − 主机单调时钟（微秒） */
        fun offsetUs(): Double
        /** 时钟是否可信；不可信时只出静音 */
        fun ready(): Boolean
    }

    @Volatile var timeBase: TimeBase? = null

    /** 本机延迟补偿（毫秒，≥0）：输出链路比系统报告的还慢多少 */
    @Volatile var audioOffsetMs: Int = 0

    /** 音轨对齐（毫秒）：正 = 本机文件开头比歌曲起点多出这么长（文件里要往后找） */
    @Volatile var trackOffsetMs: Int = 0

    @Volatile var musicGain: Float = 1f
    @Volatile var accentEnabled = true
    @Volatile var clickGain: Float = 1f

    /** 调试信息：最近一次音乐位置误差（毫秒，正 = 文件读得比应有的靠前） */
    @Volatile var lastMusicErrorMs: Double = 0.0
        private set

    /** 调试信息：getTimestamp 是否可用 */
    @Volatile var timestampOk: Boolean = false
        private set

    private val srcLock = Any()
    private var source: TrackSource? = null

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var rate = DEFAULT_RATE

    val isStarted: Boolean get() = running

    /** 当前分轨时长（微秒），没有分轨为 0 */
    val sourceDurationUs: Long get() = source?.durationUs ?: 0L

    fun start() {
        if (running) return
        startOutput(rate)
    }

    /**
     * 换分轨（null = 取消选择）。采样率不同时会重建输出轨道（约 0.1 秒静音）。
     * 打开失败会抛异常，调用方负责提示。
     */
    fun setSource(file: File?) {
        val newSrc = file?.let { TrackSource.open(it) }
        val old = synchronized(srcLock) {
            val o = source
            source = newSrc
            parkedFrame = Long.MIN_VALUE
            o
        }
        old?.release()
        val wantRate = newSrc?.sampleRate ?: rate
        if (running && wantRate != rate) {
            stopOutput()
            startOutput(wantRate)
        } else if (!running) {
            rate = wantRate
        }
    }

    fun release() {
        stopOutput()
        synchronized(srcLock) {
            source?.release()
            source = null
        }
    }

    // ------------------------------------------------------------------

    private fun startOutput(sampleRate: Int) {
        rate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 约 80ms 缓冲：够抗调度抖动，改参数后也能较快生效
        val bufferBytes = maxOf(if (minBuf > 0) minBuf * 2 else 0, sampleRate * 4 * 80 / 1000)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 创建失败", e)
            return
        }
        // 系统音量键控制整体音量；软件里的音量 / 增益在混音时做
        t.setVolume(1f)
        t.play()
        track = t
        running = true
        thread = Thread({ renderLoop(t, sampleRate) }, "band-render").apply { start() }
        Log.i(TAG, "输出启动 ${sampleRate}Hz buffer=${bufferBytes}B")
    }

    private fun stopOutput() {
        running = false
        thread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
        track?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

    // ---------------- 渲染线程 ----------------

    /** 分轨已请求定位到的帧（停着时预先定位，按播放后可以立刻精确出声） */
    private var parkedFrame = Long.MIN_VALUE

    private class Voice(var buf: FloatArray, var pos: Int, var delay: Int)

    private fun renderLoop(t: AudioTrack, sampleRate: Int) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
        }
        val n = sampleRate / 200                       // 5ms 一块
        val mix = FloatArray(n * 2)
        val out = ShortArray(n * 2)
        val srcBuf = ShortArray((n * 2 + 64) * 2)      // 最多读 n + 微调量 帧，双声道
        val scratch = ShortArray(4096)
        val synth = ClickSynth(sampleRate)
        val voices = ArrayList<Voice>(4)
        val ts = AudioTimestamp()
        val dtUs = 1_000_000.0 / sampleRate

        var written = 0L
        var tsFrame = -1L
        var tsNs = 0L
        var lastTsQuery = 0L
        var lastEpoch = Long.MIN_VALUE
        var lastClickK = Long.MIN_VALUE
        var musicWasSilent = true

        while (running) {
            java.util.Arrays.fill(mix, 0f)
            val now = System.nanoTime()

            // 1) 这块第一帧真正出声的时刻
            if (now - lastTsQuery > 100_000_000L) {
                lastTsQuery = now
                val ok = try {
                    t.getTimestamp(ts)
                } catch (_: Exception) {
                    false
                }
                if (ok && ts.framePosition > 0) {
                    tsFrame = ts.framePosition
                    tsNs = ts.nanoTime
                    timestampOk = true
                }
            }
            val presentNs: Double = if (tsFrame >= 0) {
                tsNs + (written - tsFrame) * 1e9 / sampleRate
            } else {
                // 还没拿到时间戳：按「缓冲里还没播的帧数」估算（不含硬件延迟，误差由延迟补偿兜）
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                now + (written - head) * 1e9 / sampleRate
            }

            val tb = timeBase
            val plan = tb?.plan()
            val ready = tb?.ready() == true

            if (tb != null && plan != null && ready && plan.isPlaying) {
                // 2) 耳朵听到这一帧的本机时刻 → 主机时刻 → 3) 时间轴位置
                val earLocalUs = presentNs / 1000.0 + audioOffsetMs * 1000.0
                val t0 = plan.timelineAtHostUs(earLocalUs - tb.offsetUs())
                val t1 = t0 + n * dtUs

                // 4) 节拍器
                val g = plan.grid
                if (plan.epoch != lastEpoch) {
                    lastEpoch = plan.epoch
                    lastClickK = g.beatIndexAtOrAfter(t0) - 1
                }
                if (g.active) {
                    var k = maxOf(g.beatIndexAtOrAfter(t0 - 20_000.0), lastClickK + 1)
                    while (true) {
                        val bt = g.beatTimeUs(k)
                        if (bt >= t1) break
                        if (bt >= plan.clicksFromUs) {
                            val off = ((bt - t0) / dtUs).roundToInt().coerceIn(0, n - 1)
                            val buf = if (accentEnabled && g.posInBar(k) == 0) synth.accent else synth.normal
                            if (voices.size >= 4) voices.removeAt(0)
                            voices.add(Voice(buf, 0, off))
                        }
                        lastClickK = k
                        k++
                    }
                }

                // 5) 音乐
                synchronized(srcLock) {
                    val src = source
                    if (src != null) {
                        val played = renderMusic(src, plan, t0, dtUs, n, mix, srcBuf, scratch, musicWasSilent)
                        musicWasSilent = !played
                    }
                }
            } else {
                lastEpoch = Long.MIN_VALUE
                musicWasSilent = true
                // 停着：把分轨预先定位到下次开始的地方
                if (plan != null) {
                    synchronized(srcLock) {
                        source?.let { park(it, fileFrameFor(plan.positionUs.toDouble(), it)) }
                    }
                }
            }

            // click 叠加
            val cg = clickGain
            if (voices.isNotEmpty()) {
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    var i = v.delay
                    v.delay = 0
                    while (i < n && v.pos < v.buf.size) {
                        val s = v.buf[v.pos++] * cg
                        mix[i * 2] += s
                        mix[i * 2 + 1] += s
                        i++
                    }
                    if (v.pos >= v.buf.size) it.remove()
                }
            }

            for (i in 0 until n * 2) {
                out[i] = (Gain.softClip(mix[i]) * 32767f).toInt().toShort()
            }
            val w = try {
                t.write(out, 0, n * 2)
            } catch (e: Exception) {
                Log.w(TAG, "write 失败：${e.message}")
                -1
            }
            if (w < 0) break
            written += w / 2
        }
    }

    private fun fileFrameFor(timelineUs: Double, src: TrackSource): Long =
        ((timelineUs + trackOffsetMs * 1000.0) * src.sampleRate / 1_000_000.0).roundToLong()
            .coerceAtLeast(0L)

    private fun park(src: TrackSource, frame: Long) {
        if (parkedFrame == frame) return
        src.requestSeek(frame)
        parkedFrame = frame
    }

    /**
     * 把音乐写进 mix。返回这一块是否真的出了音乐（用于下一块决定要不要淡入）。
     */
    private fun renderMusic(
        src: TrackSource,
        plan: RoomPlan,
        t0: Double,
        dtUs: Double,
        n: Int,
        mix: FloatArray,
        srcBuf: ShortArray,
        scratch: ShortArray,
        wasSilent: Boolean
    ): Boolean {
        val sr = src.sampleRate
        val trackOffUs = trackOffsetMs * 1000.0
        val bigTol = sr * 30 / 1000                 // 30ms
        val deadband = sr / 1000                    // 1ms
        val seekLead = sr * 250L / 1000             // 定位时提前 250ms，等时间轴追上
        val maxWait = sr * 600L / 1000

        // 音乐入口之前（预备拍）静音，提前定位到入口
        var start = ceil((plan.musicFromUs - t0) / dtUs).toInt().coerceAtLeast(0)
        if (start >= n) {
            park(src, fileFrameFor(plan.musicFromUs.toDouble(), src))
            return false
        }

        // 输出第 start 帧时，文件里应当在放的帧
        var desired = (t0 + start * dtUs + trackOffUs) * sr / 1_000_000.0
        if (desired < 0) {
            // 本机文件开头比时间轴晚（对齐偏移为负）：先静音，等到文件开头
            val lead = ceil(-desired).toInt()
            if (start + lead >= n) {
                park(src, 0L)
                return false
            }
            start += lead
            desired += lead
        }
        if (src.durationFrames > 0 && desired >= src.durationFrames) return false
        if (src.isDrained) {
            // 已经放完；如果往回调了对齐偏移，重新定位
            if (desired < src.nextFrame - bigTol) {
                src.requestSeek(desired.roundToLong() + seekLead)
            }
            return false
        }

        if (!src.isReady) {
            // 还在定位：目标太离谱就重新定位
            val p = src.pendingFrame.toDouble()
            if (p < desired - deadband || p > desired + maxWait) {
                src.requestSeek(desired.roundToLong() + seekLead)
                parkedFrame = Long.MIN_VALUE
            }
            return false
        }
        parkedFrame = Long.MIN_VALUE

        val e = src.nextFrame - desired              // >0：文件读得太靠前（要等），<0：落后（要追）
        lastMusicErrorMs = e * 1000.0 / sr
        val outN0 = n - start

        if (e > maxWait) {
            src.requestSeek(desired.roundToLong() + seekLead)
            return false
        }
        if (e >= bigTol || (e > 0 && wasSilent)) {
            // 精确卡点：静音 e 帧后从文件当前位置开始放（起播 / 刚定位 / 偏移改大）
            val wait = e.roundToInt()
            if (wait >= outN0) return false
            start += wait
            return copyMusic(src, start, n - start, n - start, mix, srcBuf, true)
        }
        if (e <= -bigTol) {
            val behind = (-e).roundToInt()
            if (behind < src.available() && behind < sr / 2) {
                src.skip(behind, scratch)
                return copyMusic(src, start, outN0, outN0, mix, srcBuf, true)
            }
            src.requestSeek(desired.roundToLong() + seekLead)
            return false
        }

        // 小误差：微调读取速度，最多 ±1 帧 / 200 帧（0.5%），听不出音高变化
        var consume = outN0
        if (abs(e) > deadband) {
            val maxAdj = (outN0 / 200).coerceAtLeast(1)
            val adj = (abs(e) / 20.0).roundToInt().coerceIn(1, maxAdj)
            consume = if (e > 0) outN0 - adj else outN0 + adj
        }
        return copyMusic(src, start, outN0, consume, mix, srcBuf, wasSilent)
    }

    /** 从分轨读 consume 帧，线性插值铺满 outN 帧，加到 mix[start..] */
    private fun copyMusic(
        src: TrackSource,
        start: Int,
        outN: Int,
        consume: Int,
        mix: FloatArray,
        srcBuf: ShortArray,
        fadeIn: Boolean
    ): Boolean {
        if (outN <= 0) return false
        val ch = src.channels.coerceAtLeast(1)
        val want = consume.coerceAtMost(srcBuf.size / ch)
        val got = src.read(srcBuf, want)
        if (got <= 0) return false
        val gain = musicGain
        val fadeLen = 96
        // 读够了就按比例拉伸；缓冲不够（解码跟不上）就 1:1 放能放的部分
        val ratio = if (got == want) got.toDouble() / outN else 1.0
        val count = if (got == want) outN else minOf(got, outN)
        for (i in 0 until count) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(got - 1)
            val i1 = (i0 + 1).coerceAtMost(got - 1)
            val frac = (pos - i0).toFloat()
            var l: Float
            var r: Float
            if (ch == 1) {
                val a = srcBuf[i0] / 32768f
                val b = srcBuf[i1] / 32768f
                l = a + (b - a) * frac
                r = l
            } else {
                val al = srcBuf[i0 * ch] / 32768f
                val bl = srcBuf[i1 * ch] / 32768f
                val ar = srcBuf[i0 * ch + 1] / 32768f
                val br = srcBuf[i1 * ch + 1] / 32768f
                l = al + (bl - al) * frac
                r = ar + (br - ar) * frac
            }
            var gg = gain
            if (fadeIn && i < fadeLen) gg *= i.toFloat() / fadeLen
            val o = (start + i) * 2
            mix[o] += l * gg
            mix[o + 1] += r * gg
        }
        return true
    }

    companion object {
        private const val TAG = "IsshoniPlayer"
        const val DEFAULT_RATE = 48_000
    }
}
