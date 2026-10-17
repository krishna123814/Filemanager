package com.krishna.myfiles

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.load(this)
        Thumbs.init(this)
    }
}
