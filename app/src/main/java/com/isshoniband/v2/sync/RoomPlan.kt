package com.isshoniband.v2.sync

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 节拍网格（房间共用）：BPM、拍号、首拍位置、节拍器开关、预备拍小节数。
 *
 * anchorUs：第一小节第一拍在「歌曲时间轴」上的位置，可以为负（在文件开头之前）。
 */
@Serializable
data class Grid(
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val anchorUs: Long = 0L,
    val active: Boolean = true,
    val countInBars: Int = 0
) {
    val beatUs: Double get() = 60_000_000.0 / bpm.coerceAtLeast(1)
    val barUs: Double get() = beatUs * beatsPerBar.coerceAtLeast(1)
    val countInUs: Long
        get() = if (active && countInBars > 0) (barUs * countInBars).toLong() else 0L

    /** 第 k 拍（以首拍为第 0 拍，可为负）在时间轴上的位置 */
    fun beatTimeUs(k: Long): Double = anchorUs + k * beatUs

    /** 时间轴位置 t 处或之后的第一拍序号 */
    fun beatIndexAtOrAfter(tUs: Double): Long = ceil((tUs - anchorUs) / beatUs - 1e-9).toLong()

    /** 时间轴位置 t 处或之前的最后一拍序号 */
    fun beatIndexAtOrBefore(tUs: Double): Long = floor((tUs - anchorUs) / beatUs + 1e-9).toLong()

    /** 第 k 拍在小节里的位置（0 = 小节第一拍，重音） */
    fun posInBar(k: Long): Int = Math.floorMod(k, beatsPerBar.coerceAtLeast(1).toLong()).toInt()
}

@Serializable
enum class PlanState { STOPPED, PAUSED, PLAYING }

/**
 * 房间的全部播放状态，由主机生成、原样广播，各端只照着算，不各自推测。
 *
 * 时间轴（timeline）= 歌曲时间，单位微秒。0 = 主机定义的歌曲起点。
 *
 * PLAYING 时：主机单调时钟到达 [startAtHostUs] 那一刻，所有人「耳朵听到」的时间轴位置 = [positionUs]，
 * 之后按真实时间往前走。PAUSED / STOPPED 时时间轴停在 [positionUs]。
 *
 * - [musicFromUs]：时间轴到这里之前，音乐静音（预备拍阶段）。
 * - [clicksFromUs]：节拍器只在这个位置及之后的拍点上响。
 *
 * 中途进房 / 消息迟到的设备拿到的是同一份计划，用自己的当前时刻一算就知道该在哪，
 * 不需要任何特殊分支。
 */
@Serializable
data class RoomPlan(
    val epoch: Long = 0L,
    val state: PlanState = PlanState.STOPPED,
    val positionUs: Long = 0L,
    val startAtHostUs: Long = 0L,
    val musicFromUs: Long = 0L,
    val clicksFromUs: Long = 0L,
    val grid: Grid = Grid()
) {
    val isPlaying: Boolean get() = state == PlanState.PLAYING

    /** 主机时刻 hostUs 时，耳朵应听到的时间轴位置（微秒，双精度） */
    fun timelineAtHostUs(hostUs: Double): Double =
        if (state == PlanState.PLAYING) positionUs + (hostUs - startAtHostUs) else positionUs.toDouble()

    /** 时间轴位置 t 对应的主机时刻（只在 PLAYING 有意义） */
    fun hostUsAtTimeline(tUs: Double): Double = startAtHostUs + (tUs - positionUs)

    /** 这个位置的节拍器是否应该响 */
    fun clickAllowed(tUs: Double): Boolean = grid.active && tUs >= clicksFromUs
}

/**
 * 纯计算：怎么从「按下播放」得到一份计划。没有 Android 依赖，可以直接跑 JVM 单元测试。
 */
object PlanMath {

    /**
     * 从时间轴位置 [resumeUs] 开始播放。
     *
     * 规则（用户设想：按下播放后，所有设备自己算好什么时候出声，从任意位置都自然开始）：
     * - 节拍器关：时间轴从 resumeUs 直接开始，没有任何等待。
     * - 节拍器开、还没到首拍（resumeUs ≤ 首拍）：节拍器从「首拍 − 预备拍」开始响；
     *   如果这个点早于 resumeUs（负首拍或预备拍比前奏长），时间轴从这个点开始，音乐等到 resumeUs 才进。
     * - 节拍器开、已经过了首拍：预备拍从 resumeUs 往前倒推 N 小节开始，
     *   拍子仍落在歌曲本来的网格上，重音按真实小节位置；没有预备拍就直接从 resumeUs 接着响。
     */
    fun planPlay(
        resumeUs: Long,
        grid: Grid,
        startAtHostUs: Long,
        epoch: Long
    ): RoomPlan {
        val countIn = grid.countInUs
        val clicksFrom = if (resumeUs <= grid.anchorUs) grid.anchorUs - countIn else resumeUs - countIn
        val start = if (grid.active) minOf(resumeUs, clicksFrom) else resumeUs
        return RoomPlan(
            epoch = epoch,
            state = PlanState.PLAYING,
            positionUs = start,
            startAtHostUs = startAtHostUs,
            musicFromUs = resumeUs,
            clicksFromUs = clicksFrom,
            grid = grid
        )
    }

    /** 暂停：停在当前位置；预备拍里暂停就停在音乐入口，下次再按播放会重新打预备拍 */
    fun planPause(plan: RoomPlan, nowHostUs: Long, epoch: Long): RoomPlan {
        val t = plan.timelineAtHostUs(nowHostUs.toDouble()).toLong()
        val pos = if (plan.isPlaying) maxOf(t, plan.musicFromUs) else plan.positionUs
        return plan.copy(
            epoch = epoch,
            state = PlanState.PAUSED,
            positionUs = pos,
            musicFromUs = pos,
            clicksFromUs = pos
        )
    }

    fun planStop(plan: RoomPlan, epoch: Long): RoomPlan =
        plan.copy(
            epoch = epoch,
            state = PlanState.STOPPED,
            positionUs = 0L,
            musicFromUs = 0L,
            clicksFromUs = 0L
        )

    /** 停着时拖进度 */
    fun planSeek(plan: RoomPlan, positionUs: Long, epoch: Long): RoomPlan =
        plan.copy(
            epoch = epoch,
            state = if (plan.state == PlanState.STOPPED && positionUs == 0L) PlanState.STOPPED else PlanState.PAUSED,
            positionUs = positionUs,
            musicFromUs = positionUs,
            clicksFromUs = positionUs
        )
}
