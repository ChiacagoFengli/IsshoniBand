package com.isshoniband.v2.ui

import android.content.res.Configuration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 只让页面首行的标题或按钮避开悬浮触点，滚动区仍按整宽测量。 */
@Composable
internal fun Modifier.besideNavigationCapsule(): Modifier {
    if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE) return this
    val start = with(LocalDensity.current) { 36.dp + 28.sp.toDp() }
    return padding(start = start).heightIn(min = 48.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationScaffold(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    backLabel: String = "返回",
    backDescription: String = backLabel,
    action: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var visible by remember(landscape) { mutableStateOf(true) }
    var touching by remember(landscape) { mutableStateOf(false) }
    var releases by remember(landscape) { mutableIntStateOf(0) }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 4000L, containsIcons = true, containsText = true, containsControls = true
    ) ?: 4000L
    LaunchedEffect(landscape, touching, releases, timeout) {
        if (landscape && !touching) {
            delay(timeout)
            visible = false
        }
    }

    val observeTouch = if (landscape) Modifier.pointerInput(Unit) {
        // 在子控件之前观察触摸。第一次点击仍交给原按钮或滑杆处理。
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            visible = true
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            touching = false
            releases++
        }
    } else Modifier

    Scaffold(
        modifier = Modifier.fillMaxSize().then(observeTouch),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!landscape) {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = backDescription)
                        }
                    },
                    actions = { action() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        bottomBar = bottomBar
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            content()
            if (landscape && visible) {
                FilledTonalButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(backLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
