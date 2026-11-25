package com.isshoniband.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 偏移 / 音量的步进控件（不用滑块）。
 *
 * [−50] [−10] [ ± ] [ 123 ] ms [+10] [+50]
 *
 * - 小号 ±smallStep，大号 ±bigStep
 * - 符号键单击切换正负；输入框只填绝对值
 */
@Composable
fun OffsetStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    hint: String = "",
    onInfo: (() -> Unit)? = null,
    signed: Boolean = true,
    min: Int = if (signed) -300 else 0,
    max: Int = if (signed) 300 else 100,
    smallStep: Int = 10,
    bigStep: Int = 50,
    unit: String = "ms",
    fineStep: Int? = null,
    enabled: Boolean = true
) {
    val magnitude = abs(value).toString()
    var text by remember { mutableStateOf(magnitude) }
    var negative by remember { mutableStateOf(value < 0) }
    val maxDigits = max.toString().length.coerceAtLeast(1)

    LaunchedEffect(value) {
        if (text.toIntOrNull() != abs(value)) text = abs(value).toString()
        if (value != 0) negative = value < 0
    }

    fun step(delta: Int) {
        onValueChange((value + delta).coerceIn(min, max))
    }

    fun commit(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(maxDigits)
        text = digits
        val n = digits.toIntOrNull() ?: 0
        val signedValue = if (signed && negative) -n else n
        onValueChange(signedValue.coerceIn(min, max))
    }

    fun toggleSign() {
        negative = !negative
        val n = abs(value)
        onValueChange(if (negative) -n else n)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (onInfo != null) {
                IconButton(onClick = onInfo, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "说明",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        if (hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            if (signed) {
                TextButton(onClick = { toggleSign() }, enabled = enabled) {
                    Text(
                        if (negative) "−" else "+",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (!enabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else if (negative) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { commit(it) },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(unit, style = MaterialTheme.typography.bodySmall)
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val steps = listOfNotNull(bigStep, smallStep, fineStep)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val buttonWidth = maxOf(48.dp, with(density) {
                (12 * (bigStep.toString().length + 1)).sp.toDp() + 12.dp
            })
            val wide = maxWidth >= buttonWidth * (steps.size * 2) + 4.dp * (steps.size * 2 - 1)
            val minus = steps.map { -it }
            val rows = if (wide) listOf(minus + steps.reversed()) else listOf(minus, steps)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                rows.forEach { row ->
                    CenteredControls {
                        row.forEach { delta ->
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { step(delta) }, enabled = enabled,
                                modifier = Modifier.width(buttonWidth),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                Text(if (delta < 0) "−${abs(delta)}" else "+$delta", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 每个按钮按完整标签测量，空间不足时整颗换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CenteredControls(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) { content() }
}
