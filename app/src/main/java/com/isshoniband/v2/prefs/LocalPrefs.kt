package com.isshoniband.v2.prefs

import android.content.Context

/** 本机参数：偏移、音量。只影响本机出声，不进协议。 */
class LocalPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("isshoni_local", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * 2.2.0 起音量拆成「音量 0~100%」+「增益 0~+12dB」。
     * 旧版 MP3 音量 0~200%：超过 100% 的部分换算成最接近的增益档。
     */
    private fun migrate() {
        if (sp.getInt(KEY_SCHEMA, 0) >= 2) return
        val old = sp.getInt(KEY_AUDIO_VOL, 100)
        val e = sp.edit()
        if (old > 100) {
            val db = 20.0 * kotlin.math.log10(old / 100.0)
            val step = listOf(0, 3, 6, 9, 12).minByOrNull { kotlin.math.abs(it - db) } ?: 0
            e.putInt(KEY_AUDIO_VOL, 100).putInt(KEY_AUDIO_GAIN, step)
        }
        e.putInt(KEY_SCHEMA, 2).apply()
    }

    /**
     * 延迟补偿（毫秒）：本机输出链路比系统报告的还慢多少（主要是蓝牙耳机）。
     * 2.2.0 起播放器已自动扣除系统报告的输出延迟，这里只填剩下的部分，旧值需要重新测。
     */
    var audioOffsetMs: Int
        get() = sp.getInt(KEY_AUDIO_OFFSET, 0)
        set(v) = sp.edit().putInt(KEY_AUDIO_OFFSET, v.coerceIn(0, 2500)).apply()

    var audioVolume: Int
        get() = sp.getInt(KEY_AUDIO_VOL, 100)
        set(v) = sp.edit().putInt(KEY_AUDIO_VOL, v.coerceIn(0, 100)).apply()

    /** MP3 增益（dB，0/3/6/9/12）：0 = 原声音量，往上是数字放大 */
    var audioGainDb: Int
        get() = sp.getInt(KEY_AUDIO_GAIN, 0)
        set(v) = sp.edit().putInt(KEY_AUDIO_GAIN, v.coerceIn(0, 12)).apply()

    /** 节拍器增益（dB），鼓手听不清时往上加 */
    var metronomeGainDb: Int
        get() = sp.getInt(KEY_METRO_GAIN, 0)
        set(v) = sp.edit().putInt(KEY_METRO_GAIN, v.coerceIn(0, 12)).apply()

    var metronomeVolume: Int
        get() = sp.getInt(KEY_METRO_VOL, 100)
        set(v) = sp.edit().putInt(KEY_METRO_VOL, v.coerceIn(0, 100)).apply()

    /** 本机显示名，加入房间时给其他人看 */
    var deviceName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /** 本机静音开关：关掉某一路声音，不影响音量记录 */
    var audioMuted: Boolean
        get() = sp.getBoolean(KEY_AUDIO_MUTED, false)
        set(v) = sp.edit().putBoolean(KEY_AUDIO_MUTED, v).apply()

    var accentEnabled: Boolean
        get() = sp.getBoolean("accent_enabled", true)
        set(v) = sp.edit().putBoolean("accent_enabled", v).apply()

    var metronomeMuted: Boolean
        get() = sp.getBoolean(KEY_METRO_MUTED, false)
        set(v) = sp.edit().putBoolean(KEY_METRO_MUTED, v).apply()

    /** 外观：0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, 0)
        set(v) = sp.edit().putInt(KEY_THEME_MODE, v.coerceIn(0, 2)).apply()

    /** 调色盘 id */
    var paletteId: String
        get() = sp.getString(KEY_PALETTE, "mint") ?: "mint"
        set(v) = sp.edit().putString(KEY_PALETTE, v).apply()

    /** 分轨起点偏移（毫秒），正 = 文件开头比歌曲起点晚（前面有空白） */
    var trackStartOffsetMs: Int
        get() = sp.getInt(KEY_TRACK_OFFSET, 0)
        set(v) = sp.edit().putInt(KEY_TRACK_OFFSET, v.coerceIn(-15000, 15000)).apply()

    /** 节拍震动开关 */
    var hapticEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTIC, false)
        set(v) = sp.edit().putBoolean(KEY_HAPTIC, v).apply()

    /** 震动强度 0~100 */
    var hapticStrength: Int
        get() = sp.getInt(KEY_HAPTIC_STRENGTH, 100)
        set(v) = sp.edit().putInt(KEY_HAPTIC_STRENGTH, v.coerceIn(0, 100)).apply()

    /** 震动输出偏移（毫秒），正=延后，负=提前 */
    var hapticOffsetMs: Int
        get() = sp.getInt(KEY_HAPTIC_OFFSET, 0)
        set(v) = sp.edit().putInt(KEY_HAPTIC_OFFSET, v.coerceIn(-300, 300)).apply()

    private companion object {
        const val KEY_AUDIO_OFFSET = "audioOffsetMs"
        const val KEY_AUDIO_VOL = "audioVolume"
        const val KEY_METRO_VOL = "metronomeVolume"
        const val KEY_NAME = "deviceName"
        const val KEY_AUDIO_MUTED = "audioMuted"
        const val KEY_METRO_MUTED = "metronomeMuted"
        const val KEY_HAPTIC = "hapticEnabled"
        const val KEY_HAPTIC_STRENGTH = "hapticStrength"
        const val KEY_HAPTIC_OFFSET = "hapticOffsetMs"
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_PALETTE = "paletteId"
        const val KEY_TRACK_OFFSET = "trackStartOffsetMs"
        const val KEY_AUDIO_GAIN = "audioGainDb"
        const val KEY_METRO_GAIN = "metronomeGainDb"
        const val KEY_SCHEMA = "schemaVersion"
    }
}
