package com.isshoniband.v2.sync

/**
 * 本机唯一的时间基准：单调时钟（System.nanoTime，即 CLOCK_MONOTONIC）。
 *
 * 为什么不用 System.currentTimeMillis()：系统校时会让它跳变，同步会瞬间错乱。
 * 为什么不用 elapsedRealtime：AudioTrack.getTimestamp() 给的 nanoTime 也是 CLOCK_MONOTONIC，
 * 两者用同一把尺子，音频出声时刻才能直接换算。
 *
 * 各台手机的单调时钟起点不同，没关系：ClockSync 会测出两台之间的差值（offset）。
 */
object Clock {
    fun nowNs(): Long = System.nanoTime()
    fun nowUs(): Long = System.nanoTime() / 1_000L
    fun nowMs(): Double = System.nanoTime() / 1_000_000.0
}
