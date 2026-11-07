package com.relkin.shelly.data

import android.content.Context
import com.relkin.shelly.net.AuthException
import com.relkin.shelly.net.DemoClient
import com.relkin.shelly.net.Models
import com.relkin.shelly.net.PlugConfig
import com.relkin.shelly.net.PlugStatus
import com.relkin.shelly.net.Shelly
import com.relkin.shelly.net.ShellyClient
import com.relkin.shelly.net.humanMessage
import com.relkin.shelly.notify.Mode
import com.relkin.shelly.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class Sample(val t: Long, val w: Double)

data class Session(val host: String, val client: ShellyClient, val name: String) {
    val info get() = client.info
}

data class Energy(val totalWh: Double?, val todayWh: Double?, val todaySince: Long?, val sessionWh: Double?)

/** Единая точка работы с розеткой: вход, опрос, история мощности, команды, события для уведомлений. */
class PlugRepository(private val ctx: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Mutex()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    private val _status = MutableStateFlow<PlugStatus?>(null)
    val status: StateFlow<PlugStatus?> = _status
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _lastOk = MutableStateFlow(0L)
    val lastOk: StateFlow<Long> = _lastOk
    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    val history: StateFlow<List<Sample>> = _history
    private val _energy = MutableStateFlow(Energy(null, null, null, null))
    val energy: StateFlow<Energy> = _energy
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _config = MutableStateFlow<PlugConfig?>(null)
    val config: StateFlow<PlugConfig?> = _config

    /** Экран приложения на виду — опрашиваем часто. */
    @Volatile var foreground = false
        set(v) { field = v; if (v) wake() }
    /** Работает служба фонового мониторинга. */
    @Volatile var backgroundActive = false
        set(v) { field = v; wake() }

    private var loop: Job? = null
    private var sessionStartWh: Double? = null
    private var failures = 0
    private var lastCommandAt = 0L

    // Состояние детектора событий.
    private var overloadFired = false
    private var loadSince = 0L
    private var loadStartWh: Double? = null
    private var offlineFired = false
    private var lastFaults = emptySet<String>()

    // ---------- вход ----------

    /** Подключение по IP + паролю. remember — сохранить пароль (зашифрованным). */
    suspend fun login(host: String, password: String, remember: Boolean, user: String = "admin"): Result<Session> = withContext(Dispatchers.IO) {
        val h = normalizeHost(host)
        runCatching {
            val client = Shelly.connect(h, password.ifEmpty { null }, user)
            val name = runCatching { client.config().name }.getOrNull() ?: client.info.name ?: client.info.modelName
            val old = prefs.device(h)
            prefs.upsert(
                SavedDevice(
                    host = h, name = name, model = client.info.model, gen = client.info.gen, user = user,
                    encPassword = if (remember && password.isNotEmpty()) SecretBox.encrypt(password) else null,
                    lastUsed = System.currentTimeMillis(),
                ).let { if (old != null && remember && password.isEmpty()) it.copy(encPassword = old.encPassword) else it }
            )
            prefs.currentHost.value = h
            start(Session(h, client, name))
        }
    }

    /** Вход в сохранённую розетку без формы: если пароль запомнен или не нужен. */
    suspend fun autoLogin(host: String = prefs.currentHost.value): Result<Session> {
        val d = prefs.device(host) ?: return Result.failure(IllegalStateException("Розетка не сохранена"))
        val pw = d.encPassword?.let { SecretBox.decrypt(it) } ?: ""
        return login(d.host, pw, remember = d.encPassword != null, user = d.user)
    }

    fun logout(forgetPassword: Boolean = false) {
        val s = _session.value
        if (s != null && forgetPassword) prefs.device(s.host)?.let { prefs.upsert(it.copy(encPassword = null)) }
        prefs.currentHost.value = ""
        loop?.cancel()
        _session.value = null
        _status.value = null
        _config.value = null
        _history.value = emptyList()
        _error.value = null
    }

    private fun start(s: Session): Session {
        loop?.cancel()
        _session.value = s
        _status.value = null
        _config.value = null
        _history.value = emptyList()
        _error.value = null
        sessionStartWh = null
        failures = 0
        overloadFired = false; loadSince = 0; loadStartWh = null; offlineFired = false; lastFaults = emptySet()
        wake()
        scope.launch { refreshConfig() }
        return s
    }

    private fun wake() {
        if (_session.value == null) return
        if (loop?.isActive == true) {
            loop?.cancel()
        }
        loop = scope.launch {
            while (isActive) {
                if (!foreground && !backgroundActive) break
                poll()
                delay(1000L * (if (foreground) prefs.pollSec.value else prefs.bgPollSec.value).coerceAtLeast(1))
            }
        }
    }

    // ---------- опрос ----------

    suspend fun poll() {
        val s = _session.value ?: return
        val result = io.withLock { runCatching { s.client.status() } }
        if (_session.value !== s) return
        result.onSuccess { st ->
            val prev = _status.value
            _status.value = st
            _error.value = null
            _lastOk.value = System.currentTimeMillis()
            if (failures >= 3 && offlineFired) {
                offlineFired = false
                Notifier.dismiss(ctx, Notifier.N_OFFLINE)
                if (mode(Ev.OFFLINE) != Mode.OFF) Notifier.post(ctx, Mode.NOTIFY, Notifier.N_ONLINE, s.name, "Связь с розеткой восстановлена")
            }
            failures = 0
            addSample(st)
            updateEnergy(s.host, st)
            detect(s, prev, st)
        }.onFailure { e ->
            failures++
            _error.value = e.humanMessage()
            if (e is AuthException) return
            if (failures == 3 && !offlineFired) {
                offlineFired = true
                Notifier.post(ctx, mode(Ev.OFFLINE), Notifier.N_OFFLINE, s.name, "Розетка не отвечает: ${e.humanMessage()}")
            }
        }
    }

    private fun addSample(st: PlugStatus) {
        val w = st.powerW ?: return
        val now = System.currentTimeMillis()
        val cutoff = now - HISTORY_MS
        _history.value = (_history.value.dropWhile { it.t < cutoff } + Sample(now, w)).takeLast(MAX_SAMPLES)
    }

    private fun updateEnergy(host: String, st: PlugStatus) {
        val wh = st.energyWh
        if (wh == null) { _energy.value = Energy(null, null, null, null); return }
        if (sessionStartWh == null || wh < sessionStartWh!!) sessionStartWh = wh
        val today = LocalDate.now().toString()
        var base = prefs.dayBaseline(host)
        if (base == null || base.first != today || wh < base.second) {
            base = Triple(today, wh, System.currentTimeMillis())
            prefs.setDayBaseline(host, today, wh, base.third)
        }
        _energy.value = Energy(wh, wh - base.second, base.third, wh - sessionStartWh!!)
    }

    private fun mode(ev: String) = prefs.modes[ev]?.value ?: Mode.OFF

    private fun detect(s: Session, prev: PlugStatus?, st: PlugStatus) {
        val now = System.currentTimeMillis()
        val w = st.powerW ?: 0.0

        // Перегрузка: срабатывает один раз, пока мощность не опустится ниже 90 % порога.
        val limit = prefs.overloadW.value
        if (w > limit && !overloadFired) {
            overloadFired = true
            Notifier.post(ctx, mode(Ev.OVERLOAD), Notifier.N_OVERLOAD, s.name, "Мощность %.0f Вт — выше порога %d Вт".format(w, limit))
        } else if (w < limit * 0.9) overloadFired = false

        // Нагрузка закончила работу: была ≥ doneMinW минимум минуту, упала ниже doneW.
        if (w >= prefs.doneMinW.value) {
            if (loadSince == 0L) { loadSince = now; loadStartWh = st.energyWh }
        } else if (loadSince != 0L && w < prefs.doneW.value) {
            val ranMs = now - loadSince
            if (ranMs >= 60_000) {
                val used = if (st.energyWh != null && loadStartWh != null) " · израсходовано ${Fmt.wh(st.energyWh - loadStartWh!!)}" else ""
                Notifier.post(ctx, mode(Ev.DONE), Notifier.N_DONE, s.name, "Нагрузка завершила работу (${Fmt.duration((ranMs / 60_000).toInt())})$used")
            }
            loadSince = 0; loadStartWh = null
        }

        // Переключение не из приложения (кнопка на корпусе, таймер, защита, другое приложение).
        if (prev != null && prev.on != st.on && now - lastCommandAt > 8_000) {
            Notifier.post(ctx, mode(Ev.SWITCHED), Notifier.N_SWITCHED, s.name, if (st.on) "Розетка включилась" else "Розетка выключилась")
        }

        // Аварии устройства (перегрузка/перегрев) — по появлению нового кода.
        val faults = st.errors.toSet()
        val fresh = faults - lastFaults
        if (fresh.isNotEmpty()) {
            Notifier.post(ctx, mode(Ev.FAULT), Notifier.N_FAULT, s.name, fresh.joinToString("\n") { Models.error(it) })
        }
        lastFaults = faults
    }

    // ---------- команды ----------

    private fun command(block: (ShellyClient) -> Unit, after: suspend () -> Unit = { poll() }) {
        val s = _session.value ?: return
        scope.launch {
            _busy.value = true
            lastCommandAt = System.currentTimeMillis()
            val r = io.withLock { runCatching { block(s.client) } }
            r.onFailure { _error.value = it.humanMessage() }
            _busy.value = false
            after()
        }
    }

    fun setOn(on: Boolean) = command({ it.setOn(on) })

    fun toggle() = _status.value?.let { setOn(!it.on) }

    /** Включить/оставить как есть и переключить через seconds (Gen1 timer / Gen2 toggle_after). */
    fun timer(on: Boolean, seconds: Int) = command({ it.setOn(on, seconds) })

    fun cancelTimer() = _status.value?.let { st -> command({ it.cancelTimer(st.on) }) }

    fun setPowerOn(v: com.relkin.shelly.net.PowerOnState) = command({ it.setPowerOn(v) }) { refreshConfig() }
    fun setPowerLimit(w: Int) = command({ it.setPowerLimit(w) }) { refreshConfig() }
    fun setAutoOff(s: Int) = command({ it.setAutoOff(s) }) { refreshConfig() }

    fun rename(name: String) = command({ it.setName(name) }) {
        val s = _session.value ?: return@command
        _session.value = s.copy(name = name)
        prefs.device(s.host)?.let { prefs.upsert(it.copy(name = name)) }
        refreshConfig()
    }

    fun resetEnergy() = command({ it.resetEnergy() }) {
        _session.value?.let { prefs.setDayBaseline(it.host, "", 0.0, 0) }
        sessionStartWh = null
        poll()
    }

    fun reboot() = command({ it.reboot() }) { delay(5000); poll() }

    suspend fun refreshConfig() {
        val s = _session.value ?: return
        val c = io.withLock { runCatching { s.client.config() }.getOrNull() } ?: return
        if (_session.value === s) _config.value = c
    }

    fun clearHistory() { _history.value = emptyList() }

    companion object {
        const val HISTORY_MS = 60 * 60_000L
        const val MAX_SAMPLES = 4000

        fun normalizeHost(raw: String): String {
            val h = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            return if (h.equals(DemoClient.HOST, ignoreCase = true)) DemoClient.HOST else h
        }
    }
}
