package com.isshoniband.v2.ui

import android.content.Context
import com.isshoniband.v2.prefs.LocalPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外观模式：0=跟随系统 1=浅色 2=深色 */
object Appearance {

    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private val _mode = MutableStateFlow(FOLLOW_SYSTEM)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _palette = MutableStateFlow(Palettes.first().id)
    val palette: StateFlow<String> = _palette.asStateFlow()

    fun init(context: Context) {
        val prefs = LocalPrefs(context.applicationContext)
        _mode.value = prefs.themeMode
        _palette.value = paletteById(prefs.paletteId).id
    }

    fun set(context: Context, value: Int) {
        val v = value.coerceIn(FOLLOW_SYSTEM, DARK)
        LocalPrefs(context.applicationContext).themeMode = v
        _mode.value = v
    }

    fun setPalette(context: Context, id: String) {
        val resolved = paletteById(id).id
        LocalPrefs(context.applicationContext).paletteId = resolved
        _palette.value = resolved
    }
}
