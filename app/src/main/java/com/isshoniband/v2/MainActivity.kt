package com.isshoniband.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.isshoniband.v2.ui.AppRoot
import com.isshoniband.v2.ui.Appearance
import com.isshoniband.v2.ui.IsshoniTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.init(applicationContext)
        setContent {
            val mode by Appearance.mode.collectAsState()
            val palette by Appearance.palette.collectAsState()
            val dark = when (mode) {
                Appearance.LIGHT -> false
                Appearance.DARK -> true
                else -> isSystemInDarkTheme()
            }
            IsshoniTheme(paletteId = palette, darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(LocalContext.current)
                }
            }
        }
    }
}
