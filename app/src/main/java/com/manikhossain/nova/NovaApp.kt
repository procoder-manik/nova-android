package com.manikhossain.nova

import android.app.Application

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Nova.init(this)
    }
}
