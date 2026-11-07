package com.relkin.shelly.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val ru = Locale.forLanguageTag("ru")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(ms: Long): String = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** 1234.5 → «1 235 Вт», 12.34 → «12,3 Вт», 1.2 → «1,20 Вт». */
    fun watts(w: Double?): String = when {
        w == null -> "—"
        w >= 1000 -> String.format(ru, "%,.0f Вт", w)
        w >= 10 -> String.format(ru, "%.1f Вт", w)
        else -> String.format(ru, "%.2f Вт", w)
    }

    fun num(v: Double?, digits: Int, unit: String): String =
        if (v == null) "—" else String.format(ru, "%.${digits}f $unit", v)

    /** Вт·ч: «4,2 Вт·ч», «312 Вт·ч», «1,25 кВт·ч», «1 234 кВт·ч». */
    fun wh(v: Double?): String = when {
        v == null -> "—"
        v < 10 -> String.format(ru, "%.1f Вт·ч", v)
        v < 1000 -> String.format(ru, "%.0f Вт·ч", v)
        v < 100_000 -> String.format(ru, "%.2f кВт·ч", v / 1000)
        else -> String.format(ru, "%,.0f кВт·ч", v / 1000)
    }

    fun money(wh: Double?, price: Float, currency: String): String? =
        if (wh == null || price <= 0f) null else String.format(ru, "%.2f %s", wh / 1000 * price, currency)

    /** 200 → «3 ч 20 мин». */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "меньше минуты"
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0 -> "${minutes / 60} ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }

    fun uptime(s: Long?): String {
        if (s == null) return "—"
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "$d д $h ч"
            h > 0 -> "$h ч $m мин"
            else -> "$m мин"
        }
    }

    /** Обратный отсчёт: «1:05:09» или «05:09». */
    fun countdown(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms == 0L) return "нет данных"
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "только что"
            s < 60 -> "$s с назад"
            s < 3600 -> "${s / 60} мин назад"
            else -> "в ${clock(ms)}"
        }
    }

    fun rssi(r: Int?): String = when {
        r == null -> "—"
        r >= -55 -> "$r дБм · отлично"
        r >= -67 -> "$r дБм · хорошо"
        r >= -75 -> "$r дБм · средне"
        else -> "$r дБм · слабо"
    }
}
