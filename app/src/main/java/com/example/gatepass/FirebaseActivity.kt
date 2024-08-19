package com.example.gatepass

import android.app.Application
import com.google.firebase.FirebaseApp

class FirebaseActivity : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}