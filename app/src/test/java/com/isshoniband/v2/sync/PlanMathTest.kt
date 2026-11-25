package com.isshoniband.v2.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间计划的纯计算测试：不需要手机，`gradlew :app:testDebugUnitTest` 即可。
 * 改 PlanMath / Grid 之前先跑一遍，改完再跑一遍。
 */
class PlanMathTest {

    private val g120 = Grid(bpm = 120, beatsPerBar = 4, anchorUs = 0L, active = true, countInBars = 0)

    @Test
    fun 从头播放_无预备拍_立即开始() {
        val p = PlanMath.planPlay(0L, g120, startAtHostUs = 1_000_000L, epoch = 1)
        assertEquals(0L, p.positionUs)
        assertEquals(0L, p.musicFromUs)
        assertEquals(0L, p.clicksFromUs)
        assertEquals(0.0, p.timelineAtHostUs(1_000_000.0), 0.001)
    }

    @Test
    fun 从头播放_一小节预备拍_音乐让位一小节() {
        val g = g120.copy(countInBars = 1)          // 一小节 = 2 秒
        val p = PlanMath.planPlay(0L, g, 0L, 1)
        assertEquals(-2_000_000L, p.positionUs)     // 时间轴从 −2s 开始
        assertEquals(0L, p.musicFromUs)
        assertEquals(-2_000_000L, p.clicksFromUs)
    }

    @Test
    fun 负首拍_无预备拍_音乐让位到首拍() {
        val g = g120.copy(anchorUs = -510_000L)
        val p = PlanMath.planPlay(0L, g, 0L, 1)
        assertEquals(-510_000L, p.positionUs)
        assertEquals(-510_000L, p.clicksFromUs)
        assertEquals(0L, p.musicFromUs)
    }

    @Test
    fun 首拍在前奏之后_预备拍不长于前奏_音乐不等待() {
        val g = g120.copy(anchorUs = 5_000_000L, countInBars = 1)
        val p = PlanMath.planPlay(0L, g, 0L, 1)
        assertEquals(0L, p.positionUs)              // 音乐直接开始
        assertEquals(3_000_000L, p.clicksFromUs)    // 节拍器从 3s（首拍前一小节）开始响
    }

    @Test
    fun 从歌曲中间继续_不会等很久() {
        // 旧版 bug：暂停在 60 秒处再继续，要静音约 60 秒
        val g = g120.copy(countInBars = 1)
        val p = PlanMath.planPlay(60_000_000L, g, 0L, 1)
        assertEquals(58_000_000L, p.positionUs)     // 只提前一小节（预备拍）
        assertEquals(60_000_000L, p.musicFromUs)
        val noCountIn = PlanMath.planPlay(60_000_000L, g120, 0L, 2)
        assertEquals(60_000_000L, noCountIn.positionUs)   // 没预备拍就直接接着放
    }

    @Test
    fun 从小节中间继续_预备拍仍落在原网格上() {
        val g = g120.copy(countInBars = 1)
        val resume = 61_250_000L                    // 不在拍点上
        val p = PlanMath.planPlay(resume, g, 0L, 1)
        val firstClick = g.beatIndexAtOrAfter(p.clicksFromUs.toDouble())
        val t = g.beatTimeUs(firstClick)
        assertEquals(0.0, (t - g.anchorUs) % g.beatUs, 0.001)  // 拍点在网格上
        assertTrue(t >= p.clicksFromUs)
    }

    @Test
    fun 重音按真实小节位置() {
        val g = g120.copy(anchorUs = 100_000L)
        assertEquals(0, g.posInBar(0))
        assertEquals(1, g.posInBar(1))
        assertEquals(3, g.posInBar(-1))             // 首拍之前那一拍是上一小节第 4 拍
        assertEquals(0, g.posInBar(8))
    }

    @Test
    fun 节拍器关_不让位() {
        val g = g120.copy(active = false, countInBars = 2, anchorUs = -510_000L)
        val p = PlanMath.planPlay(30_000_000L, g, 0L, 1)
        assertEquals(30_000_000L, p.positionUs)
        assertFalse(p.clickAllowed(31_000_000.0))
    }

    @Test
    fun 暂停_预备拍中暂停停在音乐入口() {
        val g = g120.copy(countInBars = 1)
        val p = PlanMath.planPlay(10_000_000L, g, startAtHostUs = 0L, epoch = 1)
        // 起播后 0.5 秒（还在预备拍里）暂停
        val paused = PlanMath.planPause(p, nowHostUs = 500_000L, epoch = 2)
        assertEquals(PlanState.PAUSED, paused.state)
        assertEquals(10_000_000L, paused.positionUs)
    }

    @Test
    fun 暂停_音乐中暂停停在当前位置() {
        val p = PlanMath.planPlay(10_000_000L, g120, startAtHostUs = 0L, epoch = 1)
        val paused = PlanMath.planPause(p, nowHostUs = 3_000_000L, epoch = 2)
        assertEquals(13_000_000L, paused.positionUs)
        // 再播放就从这里接着
        val again = PlanMath.planPlay(paused.positionUs, g120, 0L, 3)
        assertEquals(13_000_000L, again.musicFromUs)
    }

    @Test
    fun 中途进房_同一份计划算出同一位置() {
        val p = PlanMath.planPlay(0L, g120, startAtHostUs = 5_000_000L, epoch = 1)
        // 主机和客户端时钟差 123.456 秒：客户端用自己的时刻减偏移，结果一致
        val offset = 123_456_000.0
        val hostNow = 9_000_000.0
        val clientNow = hostNow + offset
        assertEquals(p.timelineAtHostUs(hostNow), p.timelineAtHostUs(clientNow - offset), 0.001)
        assertEquals(4_000_000.0, p.timelineAtHostUs(hostNow), 0.001)
    }

    @Test
    fun 拍点序号换算() {
        val g = g120.copy(anchorUs = -510_000L)
        assertEquals(0L, g.beatIndexAtOrAfter(-510_000.0))
        assertEquals(1L, g.beatIndexAtOrAfter(-509_999.0))
        assertEquals(0L, g.beatIndexAtOrBefore(-20_000.0))
        assertEquals(1L, g.beatIndexAtOrBefore(-10_000.0))   // −10ms 正好是第 1 拍
        assertEquals(-1L, g.beatIndexAtOrBefore(-510_001.0))
    }
}
