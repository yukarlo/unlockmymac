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
        // Lives on the Application rather than a screen: the watch has to stop broadcasting the
        // moment it comes off, whether or not anyone has the app open.
        WearBodyMonitor(this).start()
    }
}
