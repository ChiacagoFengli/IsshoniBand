package com.isshoniband.v2.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {

    @Test
    fun 取往返最短的样本() {
        val c = ClockSync()
        // 客户端比主机快 1000µs；第二个样本排队更少（RTT 小），应以它为准
        c.onPong(t1 = 0, t2 = -1000 + 4000, t3 = -1000 + 4000, t4 = 10_000)   // rtt 10ms，不对称
        c.onPong(t1 = 20_000, t2 = 19_000 + 1000, t3 = 19_000 + 1000, t4 = 22_000) // rtt 2ms，对称
        val (offset, rtt) = c.best()!!
        assertEquals(2_000L, rtt)
        assertEquals(1_000.0, offset, 0.001)
    }

    @Test
    fun 样本不够不算就绪() {
        val c = ClockSync()
        repeat(ClockSync.READY_SAMPLES - 1) { c.onPong(0, 0, 0, 1000) }
        assertFalse(c.ready)
        c.onPong(0, 0, 0, 1000)
        assertTrue(c.ready)
    }
}
