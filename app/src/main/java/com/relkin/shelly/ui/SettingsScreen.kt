package com.relkin.shelly.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.relkin.shelly.BuildConfig
import com.relkin.shelly.app
import com.relkin.shelly.data.Ev
import com.relkin.shelly.data.IntPref
import com.relkin.shelly.notify.Mode
import com.relkin.shelly.notify.MonitorService
import com.relkin.shelly.notify.Notifier

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val repo = ctx.app.repo
    val theme by prefs.theme.state.collectAsState()
    val dynamic by prefs.dynamicColor.state.collectAsState()
    val poll by prefs.pollSec.state.collectAsState()
    val bgPoll by prefs.bgPollSec.state.collectAsState()
    val background by prefs.background.state.collectAsState()
    val session by repo.session.collectAsState()
    var canNotify by remember { mutableStateOf(Notifier.canNotify(ctx)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = Notifier.canNotify(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Оформление") {
                val labels = listOf("Система", "Светлая", "Тёмная", "AMOLED")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, l ->
                        SegmentedButton(theme == i, { prefs.theme.value = i }, SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(Icons.Rounded.Palette, "Цвета из обоев", "Material You: палитра подстраивается под обои", dynamic == 1) {
                    prefs.dynamicColor.value = if (it) 1 else 0
                }
            }

            SectionCard("Опрос розетки") {
                ChipChoice("На экране", listOf(1 to "1 с", 2 to "2 с", 5 to "5 с", 10 to "10 с"), poll) { prefs.pollSec.value = it; repo.foreground = true }
            }

            SectionCard("Тариф") {
                val price by prefs.price.state.collectAsState()
                val currency by prefs.currency.state.collectAsState()
                var text by remember { mutableStateOf(if (price > 0f) price.toString() else "") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text,
                        {
                            text = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }.take(8)
                            prefs.price.value = text.toFloatOrNull() ?: 0f
                        },
                        label = { Text("Цена за кВт·ч") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        currency, { prefs.currency.value = it.take(4) },
                        label = { Text("Валюта") }, singleLine = true, modifier = Modifier.width(96.dp),
                    )
                }
            }

            SectionCard("Фоновый мониторинг") {
                val noSavedPw = session?.let { s -> s.info.authRequired && prefs.device(s.host)?.hasPassword != true } == true
                SwitchRow(
                    Icons.Rounded.MonitorHeart, "Следить в фоне",
                    "Постоянное уведомление; события приходят при закрытом приложении", background == 1,
                ) {
                    prefs.background.value = if (it) 1 else 0
                    if (it && !canNotify) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    MonitorService.sync(ctx)
                }
                if (background == 1) {
                    Spacer(Modifier.height(8.dp))
                    ChipChoice("Период опроса в фоне", listOf(10 to "10 с", 15 to "15 с", 30 to "30 с", 60 to "1 мин"), bgPoll) { prefs.bgPollSec.value = it }
                }
                if (noSavedPw) Hint("Пароль не сохранён — после перезапуска телефона мониторинг не сможет войти в розетку.")
            }

            SectionCard("Уведомления") {
                if (!canNotify) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Уведомления запрещены", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Разрешить") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                EventRow(prefs.modes.getValue(Ev.OVERLOAD), "Высокая мощность", "Мощность выше порога") {
                    NumberField("Порог, Вт", prefs.overloadW)
                }
                EventRow(prefs.modes.getValue(Ev.DONE), "Нагрузка завершила работу", "Стирка, зарядка, бойлер: мощность упала после работы ≥ 1 мин") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Работа от, Вт", prefs.doneMinW, Modifier.weight(1f))
                        NumberField("Окончание ниже, Вт", prefs.doneW, Modifier.weight(1f))
                    }
                }
                EventRow(prefs.modes.getValue(Ev.FAULT), "Авария розетки", "Перегрузка, перегрев, напряжение — розетка отключила нагрузку")
                EventRow(prefs.modes.getValue(Ev.SWITCHED), "Переключение не из приложения", "Кнопка на корпусе, таймер, расписание, другое приложение")
                EventRow(prefs.modes.getValue(Ev.OFFLINE), "Потеря связи", "Три неудачных опроса подряд")

                val anyAlarm = prefs.modes.values.any { it.value == Mode.ALARM }
                if (anyAlarm && !Notifier.canFullScreen(ctx)) {
                    Hint("Для «будильника» на весь экран разрешите полноэкранные уведомления.")
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                        }
                    }) { Text("Открыть настройки") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { Notifier.post(ctx, Mode.NOTIFY, Notifier.N_TEST, "Проверка", "Так выглядит обычное уведомление") }, modifier = Modifier.weight(1f)) {
                        Text("Тест: уведомление")
                    }
                    OutlinedButton(onClick = { Notifier.post(ctx, Mode.ALARM, Notifier.N_TEST, "Проверка", "Так выглядит будильник") }, modifier = Modifier.weight(1f)) {
                        Text("Тест: будильник")
                    }
                }
            }

            SectionCard("О приложении") {
                InfoRow("Версия", BuildConfig.VERSION_NAME)
                InfoRow("Связь", "Напрямую по локальной сети (HTTP)")
                InfoRow("Пароль", "AES-GCM, ключ в Android Keystore")
            }
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun ChipChoice(title: String, options: List<Pair<Int, String>>, value: Int, onSelect: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = value == v, onClick = { onSelect(v) }, label = { Text(l) }) }
    }
}

@Composable
private fun EventRow(pref: IntPref, title: String, subtitle: String, extra: (@Composable () -> Unit)? = null) {
    val mode by pref.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.labels.forEachIndexed { i, l ->
                SegmentedButton(mode == i, { pref.value = i }, SegmentedButtonDefaults.itemShape(i, Mode.labels.size)) {
                    Text(l, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (extra != null && mode != Mode.OFF) {
            Spacer(Modifier.height(8.dp))
            extra()
        }
    }
}

@Composable
private fun NumberField(label: String, pref: IntPref, modifier: Modifier = Modifier.fillMaxWidth()) {
    var text by remember { mutableStateOf(pref.value.toString()) }
    OutlinedTextField(
        text,
        {
            text = it.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { v -> v > 0 }?.let { v -> pref.value = v }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
