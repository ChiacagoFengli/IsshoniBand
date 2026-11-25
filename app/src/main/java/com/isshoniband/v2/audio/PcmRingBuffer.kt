package com.isshoniband.v2.audio

/**
 * 16bit PCM 环形缓冲，按“帧”计数（1 帧 = channels 个 short）。
 * 解码线程写，播放线程读。
 */
class PcmRingBuffer(private val capacityFrames: Int, private val channels: Int) {

    private val buffer = ShortArray(capacityFrames * channels)
    private var writeFrame = 0
    private var readFrame = 0
    private var filled = 0
    private val lock = Object()

    fun capacity(): Int = capacityFrames

    fun available(): Int = synchronized(lock) { filled }

    fun freeSpace(): Int = synchronized(lock) { capacityFrames - filled }

    /** 写入，返回实际写入的帧数（可能小于 frames） */
    fun write(src: ShortArray, offsetFrames: Int, frames: Int): Int =
        synchronized(lock) {
            val n = minOf(frames, capacityFrames - filled)
            if (n <= 0) {
                return@synchronized 0
            }
            var remaining = n
            var srcFrame = offsetFrames
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityFrames - writeFrame)
                System.arraycopy(
                    src, srcFrame * channels,
                    buffer, writeFrame * channels,
                    chunk * channels
                )
                writeFrame = (writeFrame + chunk) % capacityFrames
                srcFrame += chunk
                remaining -= chunk
            }
            filled += n
            lock.notifyAll()
            n
        }

    /** 读取，返回实际读取的帧数 */
    fun read(dst: ShortArray, maxFrames: Int): Int =
        synchronized(lock) {
            val n = minOf(maxFrames, filled)
            if (n <= 0) {
                return@synchronized 0
            }
            var remaining = n
            var dstFrame = 0
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityFrames - readFrame)
                System.arraycopy(
                    buffer, readFrame * channels,
                    dst, dstFrame * channels,
                    chunk * channels
                )
                readFrame = (readFrame + chunk) % capacityFrames
                dstFrame += chunk
                remaining -= chunk
            }
            filled -= n
            n
        }

    /** 播放线程用：等到有数据或超时（毫秒） */
    fun awaitData(timeoutMs: Long): Boolean =
        synchronized(lock) {
            if (filled > 0) {
                true
            } else {
                try {
                    lock.wait(timeoutMs)
                } catch (_: InterruptedException) {
                }
                filled > 0
            }
        }

    fun clear() {
        synchronized(lock) {
            writeFrame = 0
            readFrame = 0
            filled = 0
        }
    }
}
