package com.yukarlo.unlockmymac

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yukarlo.unlockmymac.service.UnlockNotifications

class UnlockMyMacApp :
    Application(),
    ContainerHolder {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, UnlockNotifications)
        UnlockNotifications.createChannels(this)
        observeForeground()
    }

    /**
     * Keeps [AppVisibility] in step with whether any of this app's UI is on screen.
     *
     * `onStart`/`onStop` rather than resume/pause: started is the state in which the in-app approval
     * card is actually visible, and pausing happens for things that leave it on screen — a dialog from
     * another app, the notification shade being pulled down. Suppressing the notification for those and
     * then having the user swipe the shade away would leave nothing to answer.
     *
     * Reported from here rather than from `core` on purpose; see [AppVisibility].
     */
    private fun observeForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = AppVisibility.report(true)

                override fun onStop(owner: LifecycleOwner) = AppVisibility.report(false)
            },
        )
    }
}
