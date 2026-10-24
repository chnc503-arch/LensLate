package com.lenslate.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LensLateApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
