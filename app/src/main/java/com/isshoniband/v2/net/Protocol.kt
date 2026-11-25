package com.isshoniband.v2.net

import com.isshoniband.v2.sync.RoomPlan
import kotlinx.serialization.Serializable

/**
 * 协议版本。改了消息含义就 +1：版本不一致的设备会被主机拒绝，避免半新半旧地错拍。
 * 3 = 2.2.0：单调时钟微秒 + 整份 RoomPlan 广播。
 */
const val PROTOCOL_VERSION = 3

@Serializable
data class MemberInfo(
    val id: String,
    val name: String,
    val trackName: String = "",
    /** 已对上时钟，可以按计划出声 */
    val ready: Boolean = false,
    /** 这名成员的分轨在房间时间轴上的结束位置（微秒），0 = 没选分轨 */
    val endUs: Long = 0L
)

@Serializable
data class DiscoveredRoom(
    val name: String,
    val host: String,
    val port: Int
)

/**
 * 一条 JSON Lines 消息。字段用默认值兜底，未知字段忽略。
 * 所有时刻字段都是「发送方的单调时钟微秒」。
 */
@Serializable
data class Msg(
    val type: String,
    val from: String = "",
    val name: String = "",
    val trackName: String = "",
    val ver: Int = 0,
    val t1: Long = 0L,
    val t2: Long = 0L,
    val t3: Long = 0L,
    val members: List<MemberInfo> = emptyList(),
    val reason: String = "",
    val plan: RoomPlan? = null,
    val ready: Boolean = false,
    val endUs: Long = 0L
)

object MsgType {
    /** C→H 第一条：name, ver */
    const val JOIN = "join"
    /** H→C：name = 房间名，reason = 分配给你的 id */
    const val ACCEPTED = "accepted"
    /** H→C：reason */
    const val REJECTED = "rejected"
    /** H→C：members */
    const val MEMBERS = "members"
    /** C→H：t1 */
    const val CLOCK_PING = "clockPing"
    /** H→C：t1, t2, t3 */
    const val CLOCK_PONG = "clockPong"
    /** H→C：plan（房间全部播放状态，任何变化都整份重发） */
    const val PLAN = "plan"
    /** C→H：trackName, endUs, ready */
    const val TRACK = "track"
}
