package com.rokidmirror.sender.app

import android.app.Application

class MirrorApplication : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun from(app: Application): AppContainer = (app as MirrorApplication).container
    }
}
