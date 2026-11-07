package com.relkin.shelly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.relkin.shelly.app
import com.relkin.shelly.data.Energy
import com.relkin.shelly.data.Fmt
import com.relkin.shelly.data.Session
import com.relkin.shelly.net.Models
import com.relkin.shelly.net.PlugStatus
import com.relkin.shelly.net.PowerOnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(onSettings: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val prefs = ctx.app.prefs
    val session = repo.session.collectAsState().value ?: return
    val status by repo.status.collectAsState()
    val error by repo.error.collectAsState()
    val lastOk by repo.lastOk.collectAsState()
    val history by repo.history.collectAsState()
    val energy by repo.energy.collectAsState()
    val busy by repo.busy.collectAsState()
    val price by prefs.price.state.collectAsState()
    val currency by prefs.currency.state.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }

    // Тикер для «обновлено N с назад», обратного отсчёта таймера и сдвига графика.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session.name, maxLines = 1)
                        Text(
                            "${session.info.modelName} · ${session.host}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Tune, "Настройки розетки") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки приложения") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Другая розетка") },
                                leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                                onClick = { menu = false; repo.logout(); onLogout() },
                            )
                            DropdownMenuItem(
                                text = { Text("Выйти и забыть пароль") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                                onClick = { menu = false; repo.logout(forgetPassword = true); onLogout() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; repo.poll(); repo.refreshConfig(); refreshing = false } },
            modifier = Modifier.padding(pad),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AnimatedVisibility(error != null) {
                        ConnectionBanner(error.orEmpty(), lastOk, now)
                    }
                }
                status?.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                    item { FaultCard(errs) }
                }
                item { PowerHero(status, busy, now, onToggle = { repo.toggle() }, onCancelTimer = { repo.cancelTimer() }) }
                item { Metrics(status) }
                item { ChartCard(history, now) }
                item { EnergyCard(energy, price, currency) }
                item { TimerCard(status, busy, onTimer = { on, s -> repo.timer(on, s) }) }
                item { InfoCard(session, status) }
            }
        }
    }

    if (sheet) DeviceSettingsSheet(onDismiss = { sheet = false })
}

@Composable
private fun ConnectionBanner(error: String, lastOk: Long, now: Long) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Последние данные: ${Fmt.ago(lastOk, now)}",
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FaultCard(errs: List<String>) {
    Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onError)
            Spacer(Modifier.width(12.dp))
            Text(errs.joinToString("\n") { Models.error(it) }, color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun PowerHero(st: PlugStatus?, busy: Boolean, now: Long, onToggle: () -> Unit, onCancelTimer: () -> Unit) {
    val on = st?.on == true
    val haptic = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(350), label = "bg",
    )
    val btn by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, tween(350), label = "btn",
    )
    val btnContent by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(350), label = "btnc",
    )
    val onText = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val scale by animateFloatAsState(if (busy) 0.94f else 1f, label = "scale")

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
                enabled = st != null && !busy,
                modifier = Modifier.size(132.dp).scale(scale),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = btn, contentColor = btnContent,
                    disabledContainerColor = btn.copy(alpha = 0.7f), disabledContentColor = btnContent.copy(alpha = 0.7f),
                ),
            ) {
                if (st == null) CircularProgressIndicator(Modifier.size(40.dp), color = btnContent)
                else Icon(Icons.Rounded.PowerSettingsNew, if (on) "Выключить" else "Включить", Modifier.size(64.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when { st == null -> "Подключение…"; on -> "Включена"; else -> "Выключена" },
                style = MaterialTheme.typography.titleLarge,
                color = onText,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Fmt.watts(st?.powerW),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = onText,
            )
            Text("текущая мощность", style = MaterialTheme.typography.labelLarge, color = onText.copy(alpha = 0.7f))
            st?.timerLeftS?.let { left ->
                Spacer(Modifier.height(12.dp))
                // Отсчёт идёт локально между опросами.
                val base = remember(left) { now }
                val remaining = (left - (now - base) / 1000).coerceAtLeast(0)
                InputChip(
                    selected = true,
                    onClick = onCancelTimer,
                    label = { Text("${if (on) "Выключится" else "Включится"} через ${Fmt.countdown(remaining)}") },
                    leadingIcon = { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Отменить таймер", Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun Metrics(st: PlugStatus?) {
    val tiles = buildList {
        add(Triple(Icons.Rounded.ElectricBolt, "Напряжение", Fmt.num(st?.voltageV, 1, "В")) to (st?.voltageV != null))
        add(Triple(Icons.Rounded.Speed, "Ток", Fmt.num(st?.currentA, 2, "А")) to (st?.currentA != null))
        add(Triple(Icons.Rounded.GraphicEq, "Частота", Fmt.num(st?.freqHz, 1, "Гц")) to (st?.freqHz != null))
        add(Triple(Icons.Rounded.Functions, "cos φ", Fmt.num(st?.pf, 2, "")) to (st?.pf != null))
        add(Triple(Icons.Rounded.Thermostat, "Температура", Fmt.num(st?.tempC, 1, "°C")) to (st?.tempC != null))
        add(Triple(Icons.Rounded.Wifi, "Wi‑Fi", st?.rssi?.let { "$it дБм" } ?: "—") to (st?.rssi != null))
    }.filter { it.second || st == null }.map { it.first }
    if (tiles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, label, value) -> MetricTile(icon, label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private val ranges = listOf(5 to "5 мин", 15 to "15 мин", 60 to "1 час")

@Composable
private fun ChartCard(history: List<com.relkin.shelly.data.Sample>, now: Long) {
    var range by rememberSaveable { mutableIntStateOf(5) }
    val rangeMs = range * 60_000L
    val pts = history.filter { it.t >= now - rangeMs }
    SectionCard("Мощность") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ranges.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = range == m,
                    onClick = { range = m },
                    shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (pts.size < 2) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("Собираю данные…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            PowerChart(history, rangeMs, now, Modifier.fillMaxWidth().height(180.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("мин", Fmt.watts(pts.minOf { it.w }))
                Stat("средн.", Fmt.watts(pts.map { it.w }.average()))
                Stat("макс", Fmt.watts(pts.maxOf { it.w }))
            }
        }
        Text(
            "История копится, пока приложение открыто или включён фоновый мониторинг.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EnergyCard(e: Energy, price: Float, currency: String) {
    SectionCard("Потребление") {
        if (e.totalWh == null) {
            Text("Розетка не передаёт счётчик энергии", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val sinceMidnight = e.todaySince?.let {
            val mid = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            it - mid < 15 * 60_000
        } ?: true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnergyTile(
                if (sinceMidnight) "Сегодня" else "Сегодня с ${Fmt.clock(e.todaySince!!)}",
                e.todayWh, price, currency, Modifier.weight(1f), primary = true,
            )
            EnergyTile("За сеанс", e.sessionWh, price, currency, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        EnergyTile("Всего по счётчику розетки", e.totalWh, price, currency, Modifier.fillMaxWidth())
        if (price <= 0f) {
            Text(
                "Укажите тариф в настройках, чтобы видеть стоимость.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun EnergyTile(label: String, wh: Double?, price: Float, currency: String, modifier: Modifier, primary: Boolean = false) {
    Surface(
        modifier,
        color = if (primary) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (primary) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Fmt.wh(wh), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                color = if (primary) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface)
            Fmt.money(wh, price, currency)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (primary) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val presets = listOf(15 to "15 мин", 30 to "30 мин", 60 to "1 ч", 120 to "2 ч", 240 to "4 ч", 480 to "8 ч")

@Composable
private fun TimerCard(st: PlugStatus?, busy: Boolean, onTimer: (Boolean, Int) -> Unit) {
    // true — «включить сейчас и выключить через», false — «выключить сейчас и включить через».
    var offAfter by rememberSaveable { mutableStateOf(true) }
    var custom by remember { mutableStateOf(false) }
    SectionCard("Таймер") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(offAfter, { offAfter = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Выключить через") }
            SegmentedButton(!offAfter, { offAfter = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Включить через") }
        }
        Text(
            if (offAfter) "Розетка включится (если выключена) и выключится через выбранное время."
            else "Розетка выключится (если включена) и включится через выбранное время.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (m, label) ->
                AssistChip(onClick = { onTimer(offAfter, m * 60) }, label = { Text(label) }, enabled = st != null && !busy)
            }
            AssistChip(
                onClick = { custom = true },
                label = { Text("Другое…") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) },
                enabled = st != null && !busy,
            )
        }
    }
    if (custom) {
        var text by remember { mutableStateOf("") }
        val minutes = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { custom = false },
            icon = { Icon(Icons.Rounded.Timer, null) },
            title = { Text(if (offAfter) "Выключить через" else "Включить через") },
            text = {
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Минуты") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = { onTimer(offAfter, minutes!! * 60); custom = false }, enabled = minutes != null && minutes > 0) { Text("Запустить") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun InfoCard(s: Session, st: PlugStatus?) {
    SectionCard("Устройство") {
        InfoRow("Модель", "${s.info.modelName} (Gen${s.info.gen})")
        InfoRow("Прошивка", s.info.fw.ifEmpty { "—" })
        st?.updateAvailable?.let { InfoRow("Доступно обновление", it) }
        InfoRow("MAC", s.info.mac.ifEmpty { "—" })
        InfoRow("IP-адрес", s.host)
        InfoRow("Wi‑Fi", listOfNotNull(st?.ssid, st?.rssi?.let { Fmt.rssi(it) }).joinToString(" · ").ifEmpty { "—" })
        InfoRow("Время работы", Fmt.uptime(st?.uptimeS))
    }
}

// ---------- настройки самой розетки ----------

@Composable
private fun DeviceSettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val session = repo.session.collectAsState().value ?: return
    val cfg by repo.config.collectAsState()
    val busy by repo.busy.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.refreshConfig() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Настройки розетки", style = MaterialTheme.typography.titleLarge)
            if (cfg == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            val c = cfg!!

            var name by remember(c.name) { mutableStateOf(c.name ?: session.name) }
            OutlinedTextField(
                name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (name.isNotBlank() && name != (c.name ?: session.name)) {
                        IconButton(onClick = { repo.rename(name.trim()) }, enabled = !busy) { Icon(Icons.Rounded.Check, "Сохранить") }
                    }
                },
            )

            c.powerOn?.let { current ->
                Column {
                    Text("После подачи питания", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PowerOnState.entries.forEachIndexed { i, v ->
                            SegmentedButton(
                                current == v, { repo.setPowerOn(v) }, SegmentedButtonDefaults.itemShape(i, PowerOnState.entries.size), enabled = !busy,
                            ) { Text(when (v) { PowerOnState.OFF -> "Выкл"; PowerOnState.ON -> "Вкл"; PowerOnState.RESTORE -> "Как было" }) }
                        }
                    }
                }
            }

            c.powerLimitW?.let { limit ->
                var text by remember(limit) { mutableStateOf(limit.toString()) }
                val v = text.toIntOrNull()
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Защита: лимит мощности, Вт") },
                    supportingText = { Text("Розетка отключит нагрузку при превышении") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (v != null && v > 0 && v != limit) {
                            IconButton(onClick = { repo.setPowerLimit(v) }, enabled = !busy) { Icon(Icons.Rounded.Check, "Сохранить") }
                        }
                    },
                )
            }

            c.autoOffS?.let { auto ->
                Column {
                    Text("Автовыключение после включения", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Нет", 900 to "15 мин", 1800 to "30 мин", 3600 to "1 ч", 7200 to "2 ч", 14400 to "4 ч").forEach { (s, label) ->
                            FilterChip(selected = auto == s, onClick = { repo.setAutoOff(s) }, label = { Text(label) }, enabled = !busy)
                        }
                        if (auto != 0 && auto !in listOf(900, 1800, 3600, 7200, 14400)) {
                            FilterChip(selected = true, onClick = {}, label = { Text(Fmt.duration(auto / 60)) })
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.info.gen >= 2) {
                    OutlinedButton(onClick = { confirmReset = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сбросить счётчик")
                    }
                }
                OutlinedButton(onClick = { confirmReboot = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Перезагрузить")
                }
            }
        }
    }

    if (confirmReset) ConfirmDialog("Сбросить счётчик энергии?", "Накопленные киловатт-часы на розетке обнулятся.", "Сбросить",
        onConfirm = { repo.resetEnergy(); confirmReset = false }, onDismiss = { confirmReset = false })
    if (confirmReboot) ConfirmDialog("Перезагрузить розетку?", "Связь пропадёт на несколько секунд. Нагрузка включится согласно настройке «После подачи питания».", "Перезагрузить",
        onConfirm = { repo.reboot(); confirmReboot = false }, onDismiss = { confirmReboot = false })
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
