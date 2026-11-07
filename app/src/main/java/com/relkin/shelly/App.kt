package com.relkin.shelly

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.relkin.shelly.data.PlugRepository
import com.relkin.shelly.data.Prefs
import com.relkin.shelly.notify.Notifier

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: PlugRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifier.createChannels(this)
        repo = PlugRepository(this, prefs)
        // Частый опрос — только пока приложение на экране.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { repo.foreground = true }
            override fun onStop(owner: LifecycleOwner) { repo.foreground = false }
        })
    }
}

val android.content.Context.app: App get() = applicationContext as App
