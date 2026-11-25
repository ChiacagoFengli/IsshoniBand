package com.isshoniband.v2.sync

/**
 * 客户端侧时钟偏移测量（NTP 式四时间戳），全部用单调时钟微秒。
 *
 * offset = 客户端时钟 − 主机时钟（微秒）。本机时刻 = 主机时刻 + offset。
 *
 * - 只信 RTT 最小的样本：排队最少那次的 offset 最干净。
 * - 窗口只保留最近 [WINDOW] 个样本（500ms 一次 ≈ 8 秒），
 *   两台手机晶振有 ppm 级差异，offset 会慢慢变，窗口太长会跟不上。
 * - 样本数不足 [READY_SAMPLES] 时视为「对时中」，此时不能按计划出声。
 */
class ClockSync {

    private data class Sample(val rttUs: Long, val offsetUs: Double)

    private val samples = ArrayDeque<Sample>()

    /**
     * @param t1 客户端发出
     * @param t2 主机收到
     * @param t3 主机发出
     * @param t4 客户端收到
     */
    @Synchronized
    fun onPong(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0 || rtt > 3_000_000) return
        val offset = ((t1 - t2) + (t4 - t3)) / 2.0
        samples.addLast(Sample(rtt, offset))
        while (samples.size > WINDOW) samples.removeFirst()
    }

    @get:Synchronized
    val ready: Boolean get() = samples.size >= READY_SAMPLES

    /** @return (offsetUs, rttUs)，没有样本时 null */
    @Synchronized
    fun best(): Pair<Double, Long>? {
        val s = samples.minByOrNull { it.rttUs } ?: return null
        return s.offsetUs to s.rttUs
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    companion object {
        const val WINDOW = 16
        const val READY_SAMPLES = 5
    }
}
