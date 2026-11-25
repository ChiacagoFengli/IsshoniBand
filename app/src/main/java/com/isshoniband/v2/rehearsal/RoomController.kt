package com.isshoniband.v2.rehearsal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.isshoniband.v2.audio.Gain
import com.isshoniband.v2.audio.HapticScheduler
import com.isshoniband.v2.audio.RehearsalPlayer
import com.isshoniband.v2.prefs.LocalPrefs
import com.isshoniband.v2.sync.Clock
import com.isshoniband.v2.sync.RoomPlan
import com.isshoniband.v2.sync.Session
import java.io.File

/**
 * 排练房里「本机」这一侧的全部状态与逻辑（界面只负责显示和转发点击）。
 *
 * - 持有本机唯一的 [RehearsalPlayer]（音乐 + 节拍器）和 [HapticScheduler]（震动）；
 * - 把 Session 的房间计划和时钟偏移交给它们（实现 [RehearsalPlayer.TimeBase]）；
 * - 管理本机参数：音量 / 增益 / 静音、延迟补偿、音轨对齐、震动，改了立即生效并持久化；
 * - 负责把本机分轨信息（名字、在时间轴上的结束位置）上报给房间。
 *
 * 生命周期：进房 [start]，离房 [release]。
 */
class RoomController(context: Context) : RehearsalPlayer.TimeBase {

    private val prefs = LocalPrefs(context)
    val player = RehearsalPlayer()
    val haptics = HapticScheduler(context)

    // ---------- 分轨 ----------
    var fileName by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var trackDurationUs by mutableLongStateOf(0L)
        private set
    var errorText by mutableStateOf("")

    // ---------- 本机参数 ----------
    var audioOffsetMs by mutableIntStateOf(prefs.audioOffsetMs)
        private set
    /** 音轨对齐属于曲目：每次进房归零 */
    var trackOffsetMs by mutableIntStateOf(0)
        private set
    var musicVolume by mutableIntStateOf(prefs.audioVolume)
        private set
    var musicGainDb by mutableIntStateOf(prefs.audioGainDb)
        private set
    var musicMuted by mutableStateOf(prefs.audioMuted)
        private set
    var metroVolume by mutableIntStateOf(prefs.metronomeVolume)
        private set
    var metroGainDb by mutableIntStateOf(prefs.metronomeGainDb)
        private set
    var accentEnabled by mutableStateOf(prefs.accentEnabled)
        private set

    fun toggleAccent() {
        accentEnabled = !accentEnabled
        prefs.accentEnabled = accentEnabled
        applyAll()
    }

    var metroMuted by mutableStateOf(prefs.metronomeMuted)
        private set
    var hapticEnabled by mutableStateOf(prefs.hapticEnabled)
        private set
    var hapticStrength by mutableIntStateOf(prefs.hapticStrength)
        private set
    var hapticOffsetMs by mutableIntStateOf(prefs.hapticOffsetMs)
        private set

    // ---------- TimeBase ----------
    override fun plan(): RoomPlan = Session.plan.value
    override fun offsetUs(): Double = Session.offsetUs()
    override fun ready(): Boolean = Session.clockReady()

    /** 此刻耳朵（应当）听到的房间时间轴位置，界面进度 / 拍点显示用 */
    fun timelineNowUs(): Double = plan().timelineAtHostUs(Clock.nowUs() - offsetUs())

    fun start() {
        player.timeBase = this
        haptics.timeBase = this
        applyAll()
        player.start()
        haptics.start()
        reportTrack()
    }

    /** 释放要等线程退出（最多约 1 秒），放后台做，不卡界面 */
    fun release() {
        player.timeBase = null
        haptics.timeBase = null
        Thread({
            haptics.release()
            player.release()
        }, "band-teardown").start()
    }

    private fun applyAll() {
        player.audioOffsetMs = audioOffsetMs
        player.trackOffsetMs = trackOffsetMs
        player.musicGain = Gain.linear(musicVolume, musicGainDb, musicMuted)
        // 节拍器静音只压音量，震动照样按拍走
        player.accentEnabled = accentEnabled
        haptics.accentEnabled = accentEnabled
        player.clickGain = Gain.linear(metroVolume, metroGainDb, metroMuted)
        haptics.enabled = hapticEnabled
        haptics.strength = hapticStrength / 100f
        haptics.offsetMs = hapticOffsetMs
    }

    // ---------- 分轨 ----------

    fun loadTrack(file: File): Boolean = try {
        player.setSource(file)
        fileName = file.name
        loaded = true
        trackDurationUs = player.sourceDurationUs
        errorText = ""
        reportTrack()
        true
    } catch (e: Exception) {
        Log.w("IsshoniRoom", "加载失败", e)
        errorText = "无法打开这个文件：${e.message ?: e.javaClass.simpleName}"
        false
    }

    fun clearTrack() {
        try {
            player.setSource(null)
        } catch (_: Exception) {
        }
        fileName = ""
        loaded = false
        trackDurationUs = 0L
        reportTrack()
    }

    /** 本机分轨在房间时间轴上的结束位置 = 文件时长 − 对齐偏移 */
    private fun reportTrack() {
        val endUs = if (loaded) (trackDurationUs - trackOffsetMs * 1000L).coerceAtLeast(0L) else 0L
        Session.reportTrack(fileName, endUs)
    }

    // ---------- 本机参数 ----------

    fun updateAudioOffset(v: Int) {
        audioOffsetMs = v.coerceIn(0, 2500)
        prefs.audioOffsetMs = audioOffsetMs
        applyAll()
    }

    fun updateTrackOffset(v: Int) {
        trackOffsetMs = v.coerceIn(-60_000, 60_000)
        prefs.trackStartOffsetMs = trackOffsetMs.coerceIn(-15_000, 15_000)
        applyAll()
        reportTrack()
    }

    fun updateMusicVolume(v: Int) {
        musicVolume = v.coerceIn(0, 100); prefs.audioVolume = musicVolume; applyAll()
    }

    fun updateMusicGain(db: Int) {
        musicGainDb = db.coerceIn(0, 12); prefs.audioGainDb = musicGainDb; applyAll()
    }

    fun updateMusicMuted(m: Boolean) {
        musicMuted = m; prefs.audioMuted = m; applyAll()
    }

    fun updateMetroVolume(v: Int) {
        metroVolume = v.coerceIn(0, 100); prefs.metronomeVolume = metroVolume; applyAll()
    }

    fun updateMetroGain(db: Int) {
        metroGainDb = db.coerceIn(0, 12); prefs.metronomeGainDb = metroGainDb; applyAll()
    }

    fun updateMetroMuted(m: Boolean) {
        metroMuted = m; prefs.metronomeMuted = m; applyAll()
    }

    fun updateHapticEnabled(on: Boolean) {
        hapticEnabled = on; prefs.hapticEnabled = on; applyAll()
    }

    fun updateHapticStrength(v: Int) {
        hapticStrength = v.coerceIn(0, 100); prefs.hapticStrength = hapticStrength; applyAll()
    }

    fun updateHapticOffset(v: Int) {
        hapticOffsetMs = v.coerceIn(-300, 300); prefs.hapticOffsetMs = hapticOffsetMs; applyAll()
    }
}
