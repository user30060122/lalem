package com.example.lalem

import android.app.Application
import cn.bmob.v3.Bmob
import kotlinx.coroutines.sync.Mutex

class LalemApplication : Application() {
    internal val recordStore by lazy { RecordStore(this) }
    internal val sessionLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BMOB_APPLICATION_ID.isNotBlank()) {
            Bmob.initialize(this, BuildConfig.BMOB_APPLICATION_ID)
        }
    }
}
