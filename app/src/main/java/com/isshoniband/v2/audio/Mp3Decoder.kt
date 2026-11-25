package com.isshoniband.v2.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileDescriptor

/**
 * MP3 → 16bit PCM。同步模式，按块吐出，调用方负责写进环形缓冲。
 *
 * 采样率 / 声道数取文件自身的值，不做重采样：输出轨道直接用文件的采样率开，
 * 节拍器 click 也按这个采样率合成。同步协议里的位置用微秒表达，采样率不同不影响同步。
 *
 * seekTo 是逐样本精确的（见 decodeChunk 里的裁剪）。
 */
class Mp3Decoder {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var skipUntilUs = -1L

    var sampleRate = 0
        private set
    var channels = 0
        private set
    var durationUs = 0L
        private set

    fun open(fd: FileDescriptor) {
        extractor.setDataSource(fd)

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }
        require(trackIndex >= 0) { "这个文件里没有音频轨" }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, null, null, 0)
        c.start()
        codec = c
    }

    /** 跳到指定位置：seek 到前一个同步帧，丢弃解码预热出来的帧 */
    fun seekTo(us: Long) {
        val c = codec ?: return
        try {
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.flush()
            inputDone = false
            outputDone = false
            skipUntilUs = us
        } catch (_: Exception) {
        }
    }

    /**
     * 解码下一块 PCM（交织 short）。
     * @return null 表示已经到文件末尾
     */
    fun decodeChunk(): ShortArray? {
        val c = codec ?: return null

        while (true) {
            if (!inputDone) {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(
                            inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = c.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx >= 0 -> {
                    val buf = c.getOutputBuffer(outIdx)
                    val bytes = bufferInfo.size
                    if (buf != null && bytes > 0) {
                        // seek 只能落到压缩帧边界（MP3 一帧约 26ms），这里把目标之前的部分逐样本裁掉，
                        // 否则各台设备的起点会有最多一帧的随机误差
                        var trimFrames = 0
                        if (skipUntilUs >= 0) {
                            val ch = channels.coerceAtLeast(1)
                            val framesInBuf = bytes / 2 / ch
                            val pts = bufferInfo.presentationTimeUs
                            val endUs = pts + framesInBuf * 1_000_000L / sampleRate.coerceAtLeast(1)
                            if (endUs <= skipUntilUs) {
                                c.releaseOutputBuffer(outIdx, false)
                                continue
                            }
                            if (pts < skipUntilUs) {
                                trimFrames = ((skipUntilUs - pts) * sampleRate / 1_000_000L)
                                    .toInt().coerceIn(0, framesInBuf)
                            }
                            skipUntilUs = -1L
                        }
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bytes)
                        val all = ShortArray(bytes / 2)
                        buf.asShortBuffer().get(all)
                        c.releaseOutputBuffer(outIdx, false)
                        val skip = trimFrames * channels.coerceAtLeast(1)
                        return if (skip == 0) all else all.copyOfRange(skip, all.size)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        return null
                    }
                }

                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }

                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (outputDone) return null
                }
            }
        }
    }

    fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            extractor.release()
        } catch (_: Exception) {
        }
    }
}
