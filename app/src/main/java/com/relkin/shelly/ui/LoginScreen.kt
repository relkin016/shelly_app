package com.relkin.shelly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.relkin.shelly.app
import com.relkin.shelly.data.SavedDevice
import com.relkin.shelly.net.AuthException
import com.relkin.shelly.net.DemoClient
import com.relkin.shelly.net.Models
import com.relkin.shelly.net.humanMessage
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(initialError: String?, onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val prefs = ctx.app.prefs
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val devices by prefs.devices.collectAsState()

    var host by rememberSaveable { mutableStateOf(prefs.currentHost.value.ifEmpty { devices.firstOrNull()?.host.orEmpty() }) }
    var password by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("admin") }
    var rememberPw by rememberSaveable { mutableStateOf(true) }
    var showPw by remember { mutableStateOf(false) }
    var showUser by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    var forget by remember { mutableStateOf<SavedDevice?>(null) }
    val pwFocus = remember { FocusRequester() }

    fun connect(h: String = host, pw: String = password, rem: Boolean = rememberPw, u: String = user) {
        if (h.isBlank()) { error = "Введите IP-адрес розетки"; return }
        focus.clearFocus()
        loading = true
        error = null
        scope.launch {
            repo.login(h, pw, rem, u.ifBlank { "admin" })
                .onSuccess { password = ""; onLoggedIn() }
                .onFailure { error = it.humanMessage() }
            loading = false
        }
    }

    fun quick(d: SavedDevice) {
        host = d.host
        user = d.user
        if (!d.hasPassword) {
            // Пароль не сохранён — пробуем без него (розетка может быть без защиты), иначе просим ввести.
            loading = true; error = null
            scope.launch {
                repo.login(d.host, "", false, d.user)
                    .onSuccess { onLoggedIn() }
                    .onFailure {
                        error = if (it is AuthException) "Введите пароль для «${d.name}»" else it.humanMessage()
                        if (it is AuthException) runCatching { pwFocus.requestFocus() }
                    }
                loading = false
            }
            return
        }
        loading = true; error = null
        scope.launch {
            repo.autoLogin(d.host).onSuccess { onLoggedIn() }.onFailure { error = it.humanMessage() }
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Power, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("Shelly", style = MaterialTheme.typography.displaySmall)
        Text(
            "Умная розетка в вашей сети — без облака",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        if (devices.isNotEmpty()) {
            SectionLabel("Сохранённые розетки")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { d ->
                    SavedDeviceRow(d, enabled = !loading, onClick = { quick(d) }, onForget = { forget = d })
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionLabel("Новое подключение")
        }

        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); error = null },
                    label = { Text("IP-адрес") },
                    placeholder = { Text("192.168.1.50") },
                    leadingIcon = { Icon(Icons.Rounded.Router, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { pwFocus.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    supportingText = { Text("Оставьте пустым, если защита не включена") },
                    leadingIcon = { Icon(Icons.Rounded.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPw) "Скрыть" else "Показать")
                        }
                    },
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(pwFocus),
                    enabled = !loading,
                )
                AnimatedVisibility(showUser) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it.trim() },
                        label = { Text("Пользователь (только Gen1)") },
                        leadingIcon = { Icon(Icons.Rounded.Person, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { rememberPw = !rememberPw }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Запомнить пароль", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Хранится зашифрованным ключом Android Keystore",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rememberPw, onCheckedChange = { rememberPw = it })
                }

                AnimatedVisibility(error != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Button(onClick = { connect() }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Login, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Подключиться")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showUser = !showUser }, enabled = !loading) { Text(if (showUser) "Скрыть логин" else "Другой логин") }
                    TextButton(onClick = { connect(DemoClient.HOST, "", false, "admin") }, enabled = !loading) {
                        Icon(Icons.Rounded.PlayCircleOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Демо")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Поддерживаются Shelly Plug / Plug S (Gen1), Plus Plug S, Plug S Gen3 и новее. Телефон и розетка должны быть в одной сети.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    forget?.let { d ->
        AlertDialog(
            onDismissRequest = { forget = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Забыть «${d.name}»?") },
            text = { Text("Розетка и сохранённый пароль будут удалены из приложения. На самой розетке ничего не изменится.") },
            confirmButton = { TextButton(onClick = { prefs.remove(d.host); forget = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SavedDeviceRow(d: SavedDevice, enabled: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Outlet, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "${d.host} · ${Models.name(d.model)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
            if (d.hasPassword) Icon(Icons.Rounded.Key, "Пароль сохранён", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            IconButton(onClick = onForget, enabled = enabled) {
                Icon(Icons.Rounded.Close, "Забыть", tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
