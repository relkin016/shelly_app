package com.relkin.shelly.notify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relkin.shelly.app
import com.relkin.shelly.ui.AppTheme

/** Полноэкранный «будильник» для событий в режиме будильника. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val title = intent.getStringExtra(Notifier.EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(Notifier.EXTRA_TEXT).orEmpty()
        val notif = intent.getIntExtra(Notifier.EXTRA_NOTIF, 0)
        setContent {
            AppTheme(app.prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(24.dp))
                        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(12.dp))
                        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { Notifier.dismiss(this@AlarmActivity, notif); finish() },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) { Text("Выключить", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
    }
}
