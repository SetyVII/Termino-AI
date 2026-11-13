package com.termino

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TerminoApp : Application() {
    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        serviceLocator = ServiceLocator(this)
    }
}
