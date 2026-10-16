package app.rewapp

import android.app.Application

class ReWappApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
