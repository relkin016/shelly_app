package com.relkin.shelly.net

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Демо-розетка: тот же интерфейс, правдоподобные показания (чайник/стиралка) без устройства. */
class DemoClient : ShellyClient {
    companion object {
        const val HOST = "demo"
    }

    override val info = DeviceInfo("demo-0001", "DE:MO:00:00:00:01", "DEMO", 3, "1.4.4", false, "Демо")

    private val start = System.currentTimeMillis()
    private var on = true
    private var energyWh = 12_345.6
    private var lastTick = System.currentTimeMillis()
    private var timerEnd: Long? = null
    private var timerTarget = false
    private var cfg = PlugConfig("Демо", PowerOnState.RESTORE, 2500, 0)

    @Synchronized
    override fun status(): PlugStatus {
        val now = System.currentTimeMillis()
        timerEnd?.let { if (now >= it) { on = timerTarget; timerEnd = null } }
        val t = (now - start) / 1000.0
        val power = if (!on) 0.0 else max(0.0, 420 + 380 * sin(2 * PI * t / 90) + Random.nextDouble(-25.0, 25.0) +
            if ((t / 45).toInt() % 3 == 0) 1400.0 else 0.0)
        energyWh += power * (now - lastTick) / 3_600_000.0
        lastTick = now
        val v = 228 + Random.nextDouble(-3.0, 3.0)
        return PlugStatus(
            on = on, powerW = power, voltageV = v, currentA = power / v, freqHz = 50.0 + Random.nextDouble(-0.05, 0.05), pf = null,
            energyWh = energyWh, tempC = 38 + power / 200, rssi = -58 + Random.nextInt(-3, 3), ssid = "Home",
            uptimeS = 86_400 * 3 + t.toLong(), timerLeftS = timerEnd?.let { (it - now) / 1000 }, errors = emptyList(), updateAvailable = null,
        )
    }

    @Synchronized
    override fun setOn(on: Boolean, offAfterS: Int?) {
        this.on = on
        timerEnd = offAfterS?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000L }
        timerTarget = !on
    }

    override fun cancelTimer(on: Boolean) = setOn(on)
    override fun config() = cfg
    override fun setPowerOn(state: PowerOnState) { cfg = cfg.copy(powerOn = state) }
    override fun setPowerLimit(watts: Int) { cfg = cfg.copy(powerLimitW = watts) }
    override fun setAutoOff(seconds: Int) { cfg = cfg.copy(autoOffS = seconds) }
    override fun setName(name: String) { cfg = cfg.copy(name = name) }
    override fun resetEnergy() { energyWh = 0.0 }
    override fun reboot() {}
}
