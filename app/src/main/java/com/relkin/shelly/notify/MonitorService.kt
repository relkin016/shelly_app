package com.relkin.shelly.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.relkin.shelly.R
import com.relkin.shelly.app
import com.relkin.shelly.data.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Фоновый мониторинг: держит процесс живым и опрашивает розетку раз в N секунд,
 * чтобы уведомления (перегрузка, окончание работы, потеря связи) приходили при закрытом приложении.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || app.prefs.background.value == 0) {
            stop(); return START_NOT_STICKY
        }
        startForeground(Notifier.N_SERVICE, build("Подключение…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val repo = app.repo
        repo.backgroundActive = true
        scope.launch {
            // После перезагрузки телефона / убийства процесса — вход по сохранённому паролю.
            if (repo.session.value == null) {
                val r = repo.autoLogin()
                if (r.isFailure) {
                    Notifier.post(this@MonitorService, Mode.NOTIFY, Notifier.N_OFFLINE, "Фоновый мониторинг остановлен",
                        "Не удалось войти в розетку: сохраните пароль или откройте приложение")
                    stop(); return@launch
                }
            }
            combine(repo.session, repo.status, repo.error) { s, st, err ->
                val name = s?.name ?: "Shelly"
                when {
                    err != null -> "$name · $err"
                    st == null -> "$name · подключение…"
                    else -> "$name · ${if (st.on) "вкл" else "выкл"} · ${Fmt.watts(st.powerW)}"
                }
            }.collect { text ->
                getSystemService(android.app.NotificationManager::class.java).notify(Notifier.N_SERVICE, build(text))
            }
        }
        return START_STICKY
    }

    private fun build(text: String) = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Мониторинг розетки")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(Notifier.openApp(this))
        .build()

    private fun stop() {
        app.repo.backgroundActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        app.repo.backgroundActive = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"

        fun sync(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            if (ctx.app.prefs.background.value == 1 && ctx.app.prefs.currentHost.value.isNotEmpty()) ctx.startForegroundService(i)
            else ctx.startService(i.setAction(ACTION_STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.app.prefs.background.value == 1 && context.app.prefs.currentHost.value.isNotEmpty()) {
            runCatching { MonitorService.sync(context) }
        }
    }
}
