package com.isshoniband.v2.audio

import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 一个分轨文件的解码源：解码线程 → 环形缓冲 → 由播放器的渲染线程读取。
 *
 * 定位协议（渲染线程和解码线程之间，不加锁）：
 * 1. 渲染线程调用 [requestSeek]（帧号），seek 代次 +1；
 * 2. 解码线程在下一轮循环看到代次变化 → 解码器精确定位、清空缓冲、记下这一代已就绪；
 * 3. 渲染线程看到 [isReady] 后才开始读，第一帧就是请求的那一帧。
 *
 * 只有解码线程写缓冲、只有渲染线程读缓冲，清空由写方做，避免旧数据混进来。
 */
class TrackSource private constructor(
    private val stream: FileInputStream,
    private val decoder: Mp3Decoder
) {
    val sampleRate: Int = decoder.sampleRate
    val channels: Int = decoder.channels
    val durationFrames: Long = decoder.durationUs * decoder.sampleRate / 1_000_000L
    val durationUs: Long = decoder.durationUs

    private val ring = PcmRingBuffer(sampleRate * 2, channels)

    @Volatile private var running = true
    @Volatile private var requestedGen = 0
    @Volatile private var requestedFrame = 0L
    @Volatile private var handledGen = -1
    @Volatile private var eof = false

    /** 渲染线程专用：下一次 read 读到的文件帧号 */
    var nextFrame = 0L
        private set

    private val thread = Thread({ decodeLoop() }, "band-decode").apply {
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /** 当前缓冲里的数据是否属于最近一次定位请求 */
    val isReady: Boolean get() = handledGen == requestedGen

    /** 最近一次定位请求的目标帧（未就绪时也有效） */
    val pendingFrame: Long get() = requestedFrame

    /** 解码已到文件尾且缓冲读空 */
    val isDrained: Boolean get() = isReady && eof && ring.available() == 0

    fun available(): Int = if (isReady) ring.available() else 0

    /** 渲染线程调用：请求定位到某一帧 */
    fun requestSeek(frame: Long) {
        // 时长未知（元数据缺失）时不设上限
        val f = if (durationFrames > 0) frame.coerceIn(0L, durationFrames) else frame.coerceAtLeast(0L)
        requestedFrame = f
        nextFrame = f
        requestedGen += 1
    }

    /** 渲染线程调用：读交织 PCM，返回实际帧数 */
    fun read(dst: ShortArray, frames: Int): Int {
        if (!isReady) return 0
        val n = ring.read(dst, frames)
        nextFrame += n
        return n
    }

    /** 渲染线程调用：丢掉若干帧（追赶用），返回实际丢掉的帧数 */
    fun skip(frames: Int, scratch: ShortArray): Int {
        var left = frames
        var done = 0
        val step = scratch.size / channels
        while (left > 0) {
            val n = read(scratch, minOf(left, step))
            if (n <= 0) break
            left -= n
            done += n
        }
        return done
    }

    fun release() {
        running = false
        try {
            thread.join(500)
        } catch (_: InterruptedException) {
        }
        decoder.close()
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    private fun decodeLoop() {
        while (running) {
            val gen = requestedGen
            if (gen != handledGen) {
                decoder.seekTo(requestedFrame * 1_000_000L / sampleRate)
                ring.clear()
                eof = false
                handledGen = gen
                continue
            }
            if (eof || ring.freeSpace() < 4096) {
                sleepQuietly(3)
                continue
            }
            val chunk = try {
                decoder.decodeChunk()
            } catch (e: Exception) {
                Log.w(TAG, "解码异常：${e.message}")
                null
            }
            if (requestedGen != gen) continue  // 解码期间又来了新的定位请求，这块作废
            if (chunk == null) {
                eof = true
                continue
            }
            val frames = chunk.size / channels
            var off = 0
            while (off < frames && running && requestedGen == gen) {
                val n = ring.write(chunk, off, frames - off)
                if (n <= 0) sleepQuietly(2) else off += n
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "IsshoniSource"

        /** 打开文件；失败抛异常，由调用方提示 */
        fun open(file: File): TrackSource {
            val fis = FileInputStream(file)
            val d = Mp3Decoder()
            try {
                d.open(fis.fd)
            } catch (e: Exception) {
                d.close()
                fis.close()
                throw e
            }
            return TrackSource(fis, d)
        }
    }
}
