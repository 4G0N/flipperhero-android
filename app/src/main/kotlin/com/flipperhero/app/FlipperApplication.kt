package com.flipperhero.app

import android.app.Application
import com.flipperhero.app.di.AppContainer

class FlipperApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
