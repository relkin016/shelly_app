package com.relkin.shelly.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IntPref(private val sp: SharedPreferences, private val key: String, default: Int) {
    private val flow = MutableStateFlow(sp.getInt(key, default))
    val state: StateFlow<Int> = flow
    var value: Int
        get() = flow.value
        set(v) { flow.value = v; sp.edit().putInt(key, v).apply() }
}

class FloatPref(private val sp: SharedPreferences, private val key: String, default: Float) {
    private val flow = MutableStateFlow(sp.getFloat(key, default))
    val state: StateFlow<Float> = flow
    var value: Float
        get() = flow.value
        set(v) { flow.value = v; sp.edit().putFloat(key, v).apply() }
}

class StringPref(private val sp: SharedPreferences, private val key: String, default: String) {
    private val flow = MutableStateFlow(sp.getString(key, default) ?: default)
    val state: StateFlow<String> = flow
    var value: String
        get() = flow.value
        set(v) { flow.value = v; sp.edit().putString(key, v).apply() }
}

/** Сохранённая розетка. Пароль — только в зашифрованном виде и только если пользователь попросил запомнить. */
data class SavedDevice(
    val host: String,
    val name: String,
    val model: String,
    val gen: Int,
    val user: String = "admin",
    val encPassword: String? = null,
    val lastUsed: Long = 0,
) {
    val hasPassword get() = encPassword != null

    fun toJson(): JSONObject = JSONObject().put("host", host).put("name", name).put("model", model).put("gen", gen)
        .put("user", user).put("pw", encPassword ?: JSONObject.NULL).put("last", lastUsed)

    companion object {
        fun fromJson(j: JSONObject) = SavedDevice(
            host = j.getString("host"), name = j.optString("name"), model = j.optString("model"), gen = j.optInt("gen", 1),
            user = j.optString("user", "admin"), encPassword = if (j.isNull("pw")) null else j.optString("pw"), lastUsed = j.optLong("last"),
        )
    }
}

object Ev {
    const val OVERLOAD = "overload"
    const val DONE = "done"
    const val OFFLINE = "offline"
    const val SWITCHED = "switched"
    const val FAULT = "fault"
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** 0 системная, 1 светлая, 2 тёмная, 3 AMOLED. */
    val theme = IntPref(sp, "theme", 0)
    val dynamicColor = IntPref(sp, "dynamic", 1)
    /** Период опроса на экране, с. */
    val pollSec = IntPref(sp, "poll", 2)
    /** Период опроса в фоне, с. */
    val bgPollSec = IntPref(sp, "bg_poll", 15)
    val price = FloatPref(sp, "price", 0f)
    val currency = StringPref(sp, "currency", "₽")
    val background = IntPref(sp, "background", 0)

    // Уведомления: режим (Mode.OFF/NOTIFY/ALARM) для каждого события + пороги.
    val modes = mapOf(
        Ev.OVERLOAD to IntPref(sp, "m_overload", 1),
        Ev.DONE to IntPref(sp, "m_done", 0),
        Ev.OFFLINE to IntPref(sp, "m_offline", 1),
        Ev.SWITCHED to IntPref(sp, "m_switched", 0),
        Ev.FAULT to IntPref(sp, "m_fault", 2),
    )
    val overloadW = IntPref(sp, "overload_w", 2000)
    val doneW = IntPref(sp, "done_w", 5)
    val doneMinW = IntPref(sp, "done_min_w", 30)

    private val devicesFlow = MutableStateFlow(loadDevices())
    val devices: StateFlow<List<SavedDevice>> = devicesFlow
    val currentHost = StringPref(sp, "current", "")

    private fun loadDevices(): List<SavedDevice> = runCatching {
        val arr = JSONArray(sp.getString("devices", "[]"))
        (0 until arr.length()).map { SavedDevice.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveDevices(list: List<SavedDevice>) {
        val sorted = list.sortedByDescending { it.lastUsed }
        devicesFlow.value = sorted
        sp.edit().putString("devices", JSONArray().apply { sorted.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun upsert(d: SavedDevice) = saveDevices(devicesFlow.value.filterNot { it.host == d.host } + d)

    fun remove(host: String) {
        saveDevices(devicesFlow.value.filterNot { it.host == host })
        sp.edit().remove("day_$host").apply()
        if (currentHost.value == host) currentHost.value = ""
    }

    fun device(host: String) = devicesFlow.value.firstOrNull { it.host == host }

    /** Базовое значение счётчика на начало суток: "yyyy-MM-dd|Wh|ms". */
    fun dayBaseline(host: String): Triple<String, Double, Long>? = sp.getString("day_$host", null)?.split('|')
        ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1].toDoubleOrNull() ?: return null, it[2].toLongOrNull() ?: 0) }

    fun setDayBaseline(host: String, day: String, wh: Double, since: Long) =
        sp.edit().putString("day_$host", "$day|$wh|$since").apply()
}

/** Шифрование пароля ключом AES-GCM из Android Keystore (ключ не покидает устройство). */
object SecretBox {
    private const val ALIAS = "shelly_password_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = runCatching {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        }
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}
