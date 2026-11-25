package com.isshoniband.v2.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 设备预设：跟耳机/输出链路绑定，换歌不影响 */
@Serializable
data class DevicePreset(
    val name: String,
    val audioOffsetMs: Int = 0,
    val hapticOffsetMs: Int = 0,
    val hapticEnabled: Boolean = true,
    val hapticStrength: Int = 60,
    val metronomeVolume: Int = 80
)

/**
 * 曲目预设：跟这首歌绑定，BPM / 拍号 / 首拍位置 / 预备拍。
 * audioVolume 是旧版遗留字段（2.2.0 起音量是个人设置，不再随预设套用），保留只为兼容旧数据。
 */
@Serializable
data class TrackPreset(
    val name: String,
    val anchorUs: Long = 0L,
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val audioVolume: Int = 100,
    val countInBars: Int = 0
)

/** 音轨对齐：记录「本机分轨文件相对歌曲起点的偏移」，只有自己用得上 */
@Serializable
data class AlignmentPreset(
    val name: String,
    val trackStartOffsetMs: Int = 0
)

/**
 * 两套预设分开存：
 * - 设备预设：只影响本机，谁都能存
 * - 曲目预设：所有人都能存一份备份，但只有主机能应用并广播
 */
class PresetStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("isshoni_presets", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val deviceSerializer = ListSerializer(DevicePreset.serializer())
    private val trackSerializer = ListSerializer(TrackPreset.serializer())
    private val alignmentSerializer = ListSerializer(AlignmentPreset.serializer())

    // ---------- 设备预设 ----------

    fun devices(): List<DevicePreset> = readDevices()

    fun saveDevice(preset: DevicePreset) {
        val list = readDevices().filterNot { it.name == preset.name } + preset
        sp.edit()
            .putString(KEY_DEVICES, json.encodeToString(deviceSerializer, list))
            .apply()
        selectDevice(preset.name)
    }

    fun deleteDevice(name: String) {
        val list = readDevices().filterNot { it.name == name }
        sp.edit()
            .putString(KEY_DEVICES, json.encodeToString(deviceSerializer, list))
            .apply()
        if (selectedDevice() == name) selectDevice("")
    }

    fun selectedDevice(): String = sp.getString(KEY_DEVICE_SELECTED, "") ?: ""

    fun selectDevice(name: String) {
        sp.edit().putString(KEY_DEVICE_SELECTED, name).apply()
    }

    // ---------- 曲目预设 ----------

    fun tracks(): List<TrackPreset> = readTracks()

    fun saveTrack(preset: TrackPreset) {
        val list = readTracks().filterNot { it.name == preset.name } + preset
        sp.edit()
            .putString(KEY_TRACKS, json.encodeToString(trackSerializer, list))
            .apply()
        selectTrack(preset.name)
    }

    fun deleteTrack(name: String) {
        val list = readTracks().filterNot { it.name == name }
        sp.edit()
            .putString(KEY_TRACKS, json.encodeToString(trackSerializer, list))
            .apply()
        if (selectedTrack() == name) selectTrack("")
    }

    fun selectedTrack(): String = sp.getString(KEY_TRACK_SELECTED, "") ?: ""

    fun selectTrack(name: String) {
        sp.edit().putString(KEY_TRACK_SELECTED, name).apply()
    }

    // ---------- 音轨对齐 ----------

    fun alignments(): List<AlignmentPreset> = readAlignments()

    fun saveAlignment(preset: AlignmentPreset) {
        val list = readAlignments().filterNot { it.name == preset.name } + preset
        sp.edit()
            .putString(KEY_ALIGNMENTS, json.encodeToString(alignmentSerializer, list))
            .apply()
        selectAlignment(preset.name)
    }

    fun deleteAlignment(name: String) {
        val list = readAlignments().filterNot { it.name == name }
        sp.edit()
            .putString(KEY_ALIGNMENTS, json.encodeToString(alignmentSerializer, list))
            .apply()
        if (selectedAlignment() == name) selectAlignment("")
    }

    fun selectedAlignment(): String = sp.getString(KEY_ALIGNMENT_SELECTED, "") ?: ""

    fun selectAlignment(name: String) {
        sp.edit().putString(KEY_ALIGNMENT_SELECTED, name).apply()
    }

    private fun readAlignments(): List<AlignmentPreset> {
        val raw = sp.getString(KEY_ALIGNMENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(alignmentSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readDevices(): List<DevicePreset> {
        val raw = sp.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            json.decodeFromString(deviceSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readTracks(): List<TrackPreset> {
        val raw = sp.getString(KEY_TRACKS, null) ?: return emptyList()
        return try {
            json.decodeFromString(trackSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_DEVICES = "devicePresets"
        const val KEY_DEVICE_SELECTED = "deviceSelected"
        const val KEY_TRACKS = "trackPresets"
        const val KEY_TRACK_SELECTED = "trackSelected"
        const val KEY_ALIGNMENTS = "timingPresets"
        const val KEY_ALIGNMENT_SELECTED = "timingSelected"
    }
}
