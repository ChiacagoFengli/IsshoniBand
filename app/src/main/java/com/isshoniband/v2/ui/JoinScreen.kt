package com.isshoniband.v2.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.isshoniband.v2.sync.Session
import com.isshoniband.v2.sync.SessionState

@Composable
fun JoinScreen(state: SessionState) {
    var manualIp by remember { mutableStateOf("") }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val refresh: @Composable () -> Unit = {
        IconButton(onClick = { Session.refreshRooms() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "重新搜索")
        }
    }

    NavigationScaffold(
        title = { Text("加入排练房") },
        onBack = Session::goHome,
        action = refresh
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().besideNavigationCapsule(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("同一 WiFi 下的房间", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (landscape) refresh()
            }

            if (state.discovered.isEmpty()) {
                Text(
                    "正在搜索…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.discovered.forEach { room ->
                    Card(
                        onClick = { Session.joinRoom(room.name, room.host, room.port) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(room.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${room.host}:${room.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text("手动连接", style = MaterialTheme.typography.titleSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("192.168.1.10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Button(
                    onClick = {
                        Session.joinRoom("手动连接", manualIp.trim(), 9090)
                    },
                    enabled = manualIp.isNotBlank()
                ) {
                    Text("连接")
                }
            }

            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
