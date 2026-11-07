package com.relkin.shelly.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.relkin.shelly.app
import com.relkin.shelly.notify.MonitorService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme(app.prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

private enum class Screen { LOGIN, DEVICE, SETTINGS }

@Composable
private fun Root() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val repo = ctx.app.repo
    val prefs = ctx.app.prefs
    val session by repo.session.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.LOGIN) }
    // Автовход при запуске: последняя розетка с сохранённым паролем (или без пароля).
    var booting by remember { mutableStateOf(session == null && prefs.currentHost.value.isNotEmpty()) }
    var bootError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (booting) {
            repo.autoLogin().onFailure { bootError = it.message }
            booting = false
        }
    }

    // Фоновая служба следует за входом: запускается при входе (если включена), останавливается при выходе.
    LaunchedEffect(session?.host) { if (!booting) runCatching { MonitorService.sync(ctx) } }

    val target = when {
        booting -> null
        session == null -> Screen.LOGIN
        screen == Screen.SETTINGS -> Screen.SETTINGS
        else -> Screen.DEVICE
    }

    BackHandler(enabled = target == Screen.SETTINGS) { screen = Screen.DEVICE }

    AnimatedContent(target, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
        when (s) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Screen.LOGIN -> LoginScreen(initialError = bootError, onLoggedIn = { bootError = null; screen = Screen.DEVICE })
            Screen.DEVICE -> DeviceScreen(onSettings = { screen = Screen.SETTINGS }, onLogout = { screen = Screen.LOGIN })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.DEVICE })
        }
    }
}
