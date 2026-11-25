package com.isshoniband.v2.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.isshoniband.v2.sync.Session
import com.isshoniband.v2.sync.SessionState

@Composable
fun AppRoot(context: Context) {
    val state by Session.state.collectAsState()

    LaunchedEffect(Unit) { Session.init(context) }

    when (state.screen) {
        SessionState.SCREEN_JOIN -> JoinScreen(state)
        SessionState.SCREEN_ROOM -> RoomScreen(state, context)
        SessionState.SCREEN_LIBRARY -> LibraryScreen()
        SessionState.SCREEN_PRESETS -> PresetScreen(context)
        else -> HomeScreen(state)
    }
}
