package com.yukarlo.unlockmymac.wear

import android.app.Application
import com.yukarlo.unlockmymac.AppContainer
import com.yukarlo.unlockmymac.ContainerHolder
import com.yukarlo.unlockmymac.data.AdvertiseMode

class WearUnlockApp :
    Application(),
    ContainerHolder {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The watch advertises faster than the phone by default. On LOW_POWER's ~1s interval the
        // Mac's connect watchdog expires before the link is up, so it cancels a connect that was
        // landing and gives up after two tries — measured twice in one session, with the watch
        // advertising healthily throughout.
        container = AppContainer(this, WearNotifier, AdvertiseMode.BALANCED)
        WearNotifier.createChannels(this)
        // Lives on the Application rather than a screen: the watch has to stop broadcasting the
        // moment it comes off, whether or not anyone has the app open.
        WearBodyMonitor(this).start()
    }
}
