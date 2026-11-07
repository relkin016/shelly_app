package com.relkin.shelly.net

import org.json.JSONObject
import java.net.URLEncoder

/** Что розетка рассказывает о себе (GET /shelly, без пароля). */
data class DeviceInfo(
    val id: String,
    val mac: String,
    val model: String,
    val gen: Int,
    val fw: String,
    val authRequired: Boolean,
    val name: String?,
) {
    val modelName: String get() = Models.name(model)
}

/** Мгновенные показания. null — устройство такого не измеряет. */
data class PlugStatus(
    val on: Boolean,
    val powerW: Double?,
    val voltageV: Double?,
    val currentA: Double?,
    val freqHz: Double?,
    val pf: Double?,
    /** Счётчик энергии устройства, Вт·ч (сбрасывается при перезагрузке у Gen1 / по команде). */
    val energyWh: Double?,
    val tempC: Double?,
    val rssi: Int?,
    val ssid: String?,
    val uptimeS: Long?,
    /** Сколько осталось до автопереключения, с; null — таймера нет. */
    val timerLeftS: Long?,
    val errors: List<String>,
    val updateAvailable: String?,
)

enum class PowerOnState(val label: String) { OFF("Выключена"), ON("Включена"), RESTORE("Как до отключения") }

data class PlugConfig(
    val name: String?,
    val powerOn: PowerOnState?,
    /** Лимит мощности, Вт (защита). null — не поддерживается. */
    val powerLimitW: Int?,
    /** Авто-выключение через N секунд после включения; 0 — выкл. */
    val autoOffS: Int?,
)

interface ShellyClient {
    val info: DeviceInfo
    fun status(): PlugStatus
    fun setOn(on: Boolean, offAfterS: Int? = null)
    fun cancelTimer(on: Boolean)
    fun config(): PlugConfig
    fun setPowerOn(state: PowerOnState)
    fun setPowerLimit(watts: Int)
    fun setAutoOff(seconds: Int)
    fun setName(name: String)
    fun resetEnergy()
    fun reboot()
}

object Shelly {
    /** Первый шаг входа: узнать поколение и нужен ли пароль. Пароль тут не нужен. */
    fun probe(host: String): DeviceInfo = parseInfo(JSONObject(Http(host, "", null, false).get("/shelly")))

    fun parseInfo(j: JSONObject): DeviceInfo {
        val gen = j.optInt("gen", 1)
        return if (gen >= 2) DeviceInfo(
            id = j.optString("id"),
            mac = j.optString("mac"),
            model = j.optString("model", j.optString("app")),
            gen = gen,
            fw = j.optString("ver", j.optString("fw_id")),
            authRequired = j.optBoolean("auth_en"),
            name = j.optStringOrNull("name"),
        ) else DeviceInfo(
            id = j.optString("mac"),
            mac = j.optString("mac"),
            model = j.optString("type"),
            gen = 1,
            fw = j.optString("fw").substringAfter('/').substringBefore('@'),
            authRequired = j.optBoolean("auth"),
            name = null,
        )
    }

    /** Подключается и проверяет пароль реальным запросом статуса. */
    fun connect(host: String, password: String?, user: String = "admin"): ShellyClient {
        if (host.equals(DemoClient.HOST, ignoreCase = true)) return DemoClient()
        val info = probe(host)
        if (info.authRequired && password.isNullOrEmpty()) throw AuthException("Розетка защищена паролем")
        val client = if (info.gen >= 2) Gen2Client(host, password, info) else Gen1Client(host, user, password, info)
        client.status()
        return client
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

/** Gen2 / Gen3 / Gen4 (Plus Plug S, Plug S Gen3, Outdoor Plug…): JSON-RPC поверх GET /rpc/Method?params. */
class Gen2Client(host: String, password: String?, override val info: DeviceInfo) : ShellyClient {
    private val http = Http(host, "admin", password, digest = true)

    private fun rpc(method: String, vararg params: Pair<String, Any>): JSONObject {
        val q = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8").replace("+", "%20") }
        val body = http.get("/rpc/$method" + if (q.isNotEmpty()) "?$q" else "")
        return if (body.isBlank() || body == "null") JSONObject() else JSONObject(body)
    }

    override fun status(): PlugStatus = parseStatus(rpc("Shelly.GetStatus"))

    override fun setOn(on: Boolean, offAfterS: Int?) {
        if (offAfterS != null && offAfterS > 0) rpc("Switch.Set", "id" to 0, "on" to on, "toggle_after" to offAfterS)
        else rpc("Switch.Set", "id" to 0, "on" to on)
    }

    // Новый Switch.Set без toggle_after сбрасывает таймер.
    override fun cancelTimer(on: Boolean) = setOn(on)

    override fun config(): PlugConfig {
        val c = rpc("Switch.GetConfig", "id" to 0)
        val sys = runCatching { rpc("Sys.GetConfig").optJSONObject("device") }.getOrNull()
        return PlugConfig(
            name = sys?.optStringOrNull("name") ?: c.optStringOrNull("name"),
            powerOn = when (c.optString("initial_state")) {
                "on" -> PowerOnState.ON
                "off" -> PowerOnState.OFF
                "restore_last" -> PowerOnState.RESTORE
                else -> null
            },
            powerLimitW = c.optDoubleOrNull("power_limit")?.toInt(),
            autoOffS = if (c.optBoolean("auto_off")) c.optDouble("auto_off_delay", 0.0).toInt() else 0,
        )
    }

    private fun setConfig(cfg: JSONObject) = rpc("Switch.SetConfig", "id" to 0, "config" to cfg.toString())

    override fun setPowerOn(state: PowerOnState) {
        setConfig(JSONObject().put("initial_state", when (state) {
            PowerOnState.ON -> "on"; PowerOnState.OFF -> "off"; PowerOnState.RESTORE -> "restore_last"
        }))
    }

    override fun setPowerLimit(watts: Int) { setConfig(JSONObject().put("power_limit", watts)) }

    override fun setAutoOff(seconds: Int) {
        setConfig(JSONObject().put("auto_off", seconds > 0).apply { if (seconds > 0) put("auto_off_delay", seconds) })
    }

    override fun setName(name: String) {
        rpc("Sys.SetConfig", "config" to JSONObject().put("device", JSONObject().put("name", name)).toString())
    }

    override fun resetEnergy() { rpc("Switch.ResetCounters", "id" to 0, "type" to "[\"aenergy\"]") }

    override fun reboot() { rpc("Shelly.Reboot") }

    companion object {
        fun parseStatus(j: JSONObject): PlugStatus {
            val sw = j.optJSONObject("switch:0") ?: JSONObject()
            val sys = j.optJSONObject("sys") ?: JSONObject()
            val wifi = j.optJSONObject("wifi") ?: JSONObject()
            val now = sys.optDoubleOrNull("unixtime")
            val started = sw.optDoubleOrNull("timer_started_at")
            val dur = sw.optDoubleOrNull("timer_duration")
            val errs = sw.optJSONArray("errors")
            return PlugStatus(
                on = sw.optBoolean("output"),
                powerW = sw.optDoubleOrNull("apower"),
                voltageV = sw.optDoubleOrNull("voltage"),
                currentA = sw.optDoubleOrNull("current"),
                freqHz = sw.optDoubleOrNull("freq"),
                pf = sw.optDoubleOrNull("pf"),
                energyWh = sw.optJSONObject("aenergy")?.optDoubleOrNull("total"),
                tempC = sw.optJSONObject("temperature")?.optDoubleOrNull("tC"),
                rssi = wifi.optDoubleOrNull("rssi")?.toInt(),
                ssid = wifi.optStringOrNull("ssid"),
                uptimeS = sys.optDoubleOrNull("uptime")?.toLong(),
                timerLeftS = if (started != null && dur != null && now != null) (started + dur - now).toLong().coerceAtLeast(0) else null,
                errors = (0 until (errs?.length() ?: 0)).map { errs!!.getString(it) },
                updateAvailable = sys.optJSONObject("available_updates")?.optJSONObject("stable")?.optStringOrNull("version"),
            )
        }
    }
}

/** Gen1 (Shelly Plug, Plug S): REST /status, /relay/0, /settings. */
class Gen1Client(host: String, user: String, password: String?, override val info: DeviceInfo) : ShellyClient {
    private val http = Http(host, user, password, digest = false)

    private fun get(path: String) = JSONObject(http.get(path))

    override fun status(): PlugStatus = parseStatus(get("/status"))

    override fun setOn(on: Boolean, offAfterS: Int?) {
        val t = if (offAfterS != null && offAfterS > 0) "&timer=$offAfterS" else ""
        get("/relay/0?turn=${if (on) "on" else "off"}$t")
    }

    override fun cancelTimer(on: Boolean) = setOn(on)

    override fun config(): PlugConfig {
        val s = get("/settings")
        val r = s.optJSONArray("relays")?.optJSONObject(0) ?: JSONObject()
        return PlugConfig(
            name = s.optStringOrNull("name"),
            powerOn = when (r.optString("default_state")) {
                "on" -> PowerOnState.ON
                "off" -> PowerOnState.OFF
                "last" -> PowerOnState.RESTORE
                else -> null
            },
            powerLimitW = s.optDoubleOrNull("max_power")?.toInt(),
            autoOffS = r.optDoubleOrNull("auto_off")?.toInt(),
        )
    }

    override fun setPowerOn(state: PowerOnState) {
        get("/settings/relay/0?default_state=" + when (state) {
            PowerOnState.ON -> "on"; PowerOnState.OFF -> "off"; PowerOnState.RESTORE -> "last"
        })
    }

    override fun setPowerLimit(watts: Int) { get("/settings?max_power=$watts") }

    override fun setAutoOff(seconds: Int) { get("/settings/relay/0?auto_off=$seconds") }

    override fun setName(name: String) { get("/settings?name=" + URLEncoder.encode(name, "UTF-8")) }

    // У Gen1 счётчик сбрасывается только перезагрузкой; отдельной команды нет.
    override fun resetEnergy() {
        throw UnsupportedOperationException("Gen1 сбрасывает счётчик только при перезагрузке")
    }

    override fun reboot() { get("/reboot") }

    companion object {
        fun parseStatus(j: JSONObject): PlugStatus {
            val relay = j.optJSONArray("relays")?.optJSONObject(0) ?: JSONObject()
            val meter = j.optJSONArray("meters")?.optJSONObject(0) ?: JSONObject()
            val wifi = j.optJSONObject("wifi_sta") ?: JSONObject()
            val errors = buildList {
                if (relay.optBoolean("overpower")) add("overpower")
                if (j.optBoolean("overtemperature")) add("overtemp")
            }
            return PlugStatus(
                on = relay.optBoolean("ison"),
                powerW = meter.optDoubleOrNull("power"),
                voltageV = null,
                currentA = null,
                freqHz = null,
                pf = null,
                // Gen1 считает в Вт·мин.
                energyWh = meter.optDoubleOrNull("total")?.div(60.0),
                tempC = j.optDoubleOrNull("temperature") ?: j.optJSONObject("tmp")?.optDoubleOrNull("tC"),
                rssi = wifi.optDoubleOrNull("rssi")?.toInt(),
                ssid = wifi.optStringOrNull("ssid"),
                uptimeS = j.optDoubleOrNull("uptime")?.toLong(),
                timerLeftS = if (relay.optBoolean("has_timer")) relay.optDouble("timer_remaining", 0.0).toLong() else null,
                errors = errors,
                updateAvailable = j.optJSONObject("update")?.takeIf { it.optBoolean("has_update") }?.optStringOrNull("new_version"),
            )
        }
    }
}

object Models {
    private val names = mapOf(
        "SHPLG-S" to "Shelly Plug S",
        "SHPLG-1" to "Shelly Plug",
        "SHPLG2-1" to "Shelly Plug",
        "SHPLG-U1" to "Shelly Plug US",
        "SNPL-00112EU" to "Shelly Plus Plug S",
        "SNPL-10112EU" to "Shelly Plus Plug S",
        "SNPL-00112UK" to "Shelly Plus Plug UK",
        "SNPL-00110IT" to "Shelly Plus Plug IT",
        "SNPL-00116US" to "Shelly Plus Plug US",
        "S3PL-00112EU" to "Shelly Plug S Gen3",
        "S3PL-10112EU" to "Shelly Plug S Gen3",
        "S4PL-00416EU" to "Shelly Outdoor Plug S Gen3",
        "PlusPlugS" to "Shelly Plus Plug S",
        "DEMO" to "Демо-розетка",
    )

    fun name(model: String): String = names[model] ?: model.ifEmpty { "Shelly" }

    fun error(code: String): String = when (code) {
        "overpower" -> "Перегрузка по мощности — розетка отключила нагрузку"
        "overtemp" -> "Перегрев"
        "overvoltage" -> "Повышенное напряжение"
        "undervoltage" -> "Пониженное напряжение"
        "overcurrent" -> "Превышен ток"
        else -> code
    }
}
