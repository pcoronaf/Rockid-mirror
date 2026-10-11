package com.rokidmirror.sender.app

import android.app.Application
import com.rokidmirror.sender.telemetry.MirrorLog
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MirrorApplication : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        // Computer mode reflects on hidden framework classes (IDisplayManager, VirtualDisplayConfig)
        // in THIS process. Without the exemption, Android's hidden-API filter hides those members, so
        // the lookup saw no createVirtualDisplay at all. Exempting the android/ namespace restores
        // them. Harmless for the rest of the app, which does not touch hidden APIs.
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/", "Lcom/android/") }
            .onFailure { MirrorLog.w("App", "hidden_api_exemption_failed", "error" to it.message) }
        container = AppContainer(this)
    }

    companion object {
        fun from(app: Application): AppContainer = (app as MirrorApplication).container
    }
}
