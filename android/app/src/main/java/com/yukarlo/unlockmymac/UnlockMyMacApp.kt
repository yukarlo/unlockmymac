package com.yukarlo.unlockmymac

import android.app.Application
import com.yukarlo.unlockmymac.service.UnlockNotifications

class UnlockMyMacApp :
    Application(),
    ContainerHolder {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        UnlockNotifications.createChannels(this)
    }
}
