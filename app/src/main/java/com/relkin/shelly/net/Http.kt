package com.relkin.shelly.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AuthException(message: String) : IOException(message)
class HttpException(val code: Int, message: String) : IOException(message)

/** Человекочитаемая причина сетевой ошибки. */
fun Throwable.humanMessage(): String = when (this) {
    is AuthException -> message ?: "Неверный пароль"
    is HttpException -> message ?: "HTTP $code"
    is SocketTimeoutException -> "Розетка не отвечает (таймаут)"
    is UnknownHostException -> "Адрес не найден"
    is java.net.ConnectException -> "Нет соединения с розеткой"
    is java.net.NoRouteToHostException -> "Нет маршрута до розетки — проверьте Wi‑Fi"
    else -> message ?: javaClass.simpleName
}

/**
 * Простой HTTP-клиент для Shelly в локальной сети.
 * Gen1 — Basic-авторизация; Gen2+ — Digest (SHA-256, пользователь всегда «admin»).
 */
class Http(
    private val host: String,
    private val user: String,
    private val password: String?,
    private val digest: Boolean,
) {
    private val base = "http://" + host.trim().removePrefix("http://").removeSuffix("/")
    private var challenge: Digest.Challenge? = null
    private var nc = 0

    fun get(path: String, timeoutMs: Int = 4000): String {
        var attempt = 0
        while (true) {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                authHeader(path)?.let { setRequestProperty("Authorization", it) }
            }
            try {
                val code = conn.responseCode
                if (code == 401) {
                    if (password.isNullOrEmpty()) throw AuthException("Нужен пароль")
                    val www = conn.getHeaderField("WWW-Authenticate")
                    val fresh = if (digest && www != null) Digest.parse(www) else null
                    // Повторяем только если пришёл новый вызов (истёк nonce) — иначе пароль неверный.
                    if (attempt++ < 2 && fresh != null && (challenge == null || fresh.nonce != challenge!!.nonce || fresh.stale)) {
                        challenge = fresh
                        nc = 0
                        continue
                    }
                    throw AuthException("Неверный пароль")
                }
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw HttpException(code, "HTTP $code" + if (err.isNotBlank()) ": ${err.take(200)}" else "")
                }
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun authHeader(path: String): String? {
        if (password.isNullOrEmpty()) return null
        if (!digest) {
            return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        }
        val ch = challenge ?: return null
        nc++
        return Digest.header(ch, "admin", password, "GET", path, nc, Digest.cnonce())
    }
}

/** RFC 7616 Digest, как его ждёт Shelly Gen2+ (algorithm=SHA-256, qop=auth). */
object Digest {
    data class Challenge(val realm: String, val nonce: String, val qop: String?, val algorithm: String, val opaque: String?, val stale: Boolean)

    fun parse(header: String): Challenge? {
        if (!header.trimStart().startsWith("Digest", ignoreCase = true)) return null
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(header.substringAfter(' '))
            .associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        return Challenge(
            realm = params["realm"] ?: return null,
            nonce = params["nonce"] ?: return null,
            qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" } ?: params["qop"],
            algorithm = params["algorithm"] ?: "MD5",
            opaque = params["opaque"],
            stale = params["stale"].equals("true", ignoreCase = true),
        )
    }

    fun cnonce(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(algorithm: String, s: String): String {
        val alg = if (algorithm.uppercase().startsWith("SHA-256")) "SHA-256" else "MD5"
        return MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun header(ch: Challenge, user: String, password: String, method: String, uri: String, nc: Int, cnonce: String): String {
        val ha1 = hash(ch.algorithm, "$user:${ch.realm}:$password")
        val ha2 = hash(ch.algorithm, "$method:$uri")
        val ncs = "%08x".format(nc)
        val response = if (ch.qop != null) hash(ch.algorithm, "$ha1:${ch.nonce}:$ncs:$cnonce:${ch.qop}:$ha2")
        else hash(ch.algorithm, "$ha1:${ch.nonce}:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"${ch.realm}\", nonce=\"${ch.nonce}\", uri=\"$uri\", ")
            append("algorithm=${ch.algorithm}, response=\"$response\"")
            if (ch.qop != null) append(", qop=${ch.qop}, nc=$ncs, cnonce=\"$cnonce\"")
            if (ch.opaque != null) append(", opaque=\"${ch.opaque}\"")
        }
    }
}
