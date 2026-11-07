package com.relkin.shelly.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.relkin.shelly.R
import com.relkin.shelly.ui.MainActivity

object Mode {
    const val OFF = 0
    const val NOTIFY = 1
    const val ALARM = 2
    val labels = listOf("Выкл", "Уведомление", "Будильник")
}

object Notifier {
    const val CH_EVENTS = "events"
    const val CH_ALARM = "alarms"
    const val CH_SERVICE = "service"

    const val N_SERVICE = 1
    const val N_OVERLOAD = 2
    const val N_DONE = 3
    const val N_OFFLINE = 4
    const val N_ONLINE = 5
    const val N_SWITCHED = 6
    const val N_FAULT = 7
    const val N_TEST = 8

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_NOTIF = "notif"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "События розетки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Перегрузка, окончание работы нагрузки, потеря связи, переключение"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "События в режиме будильника: звук повторяется, пока не выключите"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Фоновый мониторинг", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока приложение следит за розеткой в фоне"
                setShowBadge(false)
            }
        )
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun openApp(ctx: Context, code: Int = 0): PendingIntent = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Показывает событие в выбранном режиме: обычное уведомление или будильник на весь экран. */
    fun post(ctx: Context, mode: Int, id: Int, title: String, text: String) {
        if (mode == Mode.OFF || !canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, if (mode == Mode.ALARM) CH_ALARM else CH_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (mode == Mode.ALARM) {
            val full = PendingIntent.getActivity(
                ctx, 100 + id,
                Intent(ctx, AlarmActivity::class.java)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_NOTIF, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .addAction(0, "Выключить", PendingIntent.getBroadcast(
                    ctx, 200 + id,
                    Intent(ctx, DismissReceiver::class.java).putExtra(EXTRA_NOTIF, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
        }
        val n = b.build()
        if (mode == Mode.ALARM) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun dismiss(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)
}

class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.dismiss(context, intent.getIntExtra(Notifier.EXTRA_NOTIF, 0))
    }
}
