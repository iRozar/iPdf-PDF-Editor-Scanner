package com.irozar.ipdfmaster.pdfeditorspike

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required once before any PdfBox-Android use.
        PDFBoxResourceLoader.init(applicationContext)
        // Initialize the Google Mobile Ads (AdMob) SDK once at startup.
        MobileAds.initialize(this) {}
    }
}
