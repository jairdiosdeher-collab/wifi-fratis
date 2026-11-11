package com.enterprise.wifianalyzer

import android.os.Bundle
import com.enterprise.wifianalyzer.plugins.WifiScannerPlugin
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(WifiScannerPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
