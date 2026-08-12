package com.yukarlo.unlockmymac.wear

import android.app.Application
import com.yukarlo.unlockmymac.AppContainer
import com.yukarlo.unlockmymac.ContainerHolder

class WearUnlockApp :
    Application(),
    ContainerHolder {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, WearNotifier)
        WearNotifier.createChannels(this)
    }
}
