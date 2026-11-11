package com.enterprise.wifianalyzer.plugins

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

private const val TAG = "WifiScannerPlugin"

@CapacitorPlugin(
    name = "WifiScanner",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ],
            alias = "location"
        ),
        Permission(
            strings = [
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            ],
            alias = "wifi"
        ),
        Permission(
            strings = [
                "android.permission.NEARBY_WIFI_DEVICES"
            ],
            alias = "nearbyWifi"
        )
    ]
)
class WifiScannerPlugin : Plugin() {

    private var wifiManager: WifiManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var locationManager: LocationManager? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var pendingScanCall: PluginCall? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun load() {
        super.load()
        val ctx = context
        wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        Log.d(TAG, "[BOOT] WifiScannerPlugin inicializado en Android API ${Build.VERSION.SDK_INT}")
    }

    /**
     * Devuelve el estado de diagnóstico de hardware y permisos de Android
     */
    @PluginMethod
    fun getDiagnostics(call: PluginCall) {
        val manager = wifiManager
        val isWifiOn = manager?.isWifiEnabled ?: false
        val isLocOn = isLocationServicesEnabled()
        val fineGranted = getPermissionState("location") == PermissionState.GRANTED
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPermissionState("nearbyWifi") == PermissionState.GRANTED
        } else {
            true
        }

        val ret = JSObject()
        ret.put("isAndroid", true)
        ret.put("sdkInt", Build.VERSION.SDK_INT)
        ret.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
        ret.put("wifiEnabled", isWifiOn)
        ret.put("locationServicesEnabled", isLocOn)
        ret.put("fineLocationGranted", fineGranted)
        ret.put("nearbyWifiGranted", nearbyGranted)
        ret.put("allPermissionsGranted", fineGranted && nearbyGranted)
        ret.put("readyToScan", isWifiOn && isLocOn && fineGranted && nearbyGranted)

        val logList = JSArray()
        logList.put("[BOOT] Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android API ${Build.VERSION.SDK_INT})")
        logList.put("[PLUGIN] Plugin WifiScanner registrado y enlazado con Capacitor Bridge.")
        logList.put(if (isWifiOn) "[WIFI] WifiManager habilitado correctamente." else "[WIFI] WifiManager DESHABILITADO. Requiere activación en Ajustes.")
        logList.put(if (isLocOn) "[PERMISSIONS] Servicios de ubicación GPS encendidos en el SO." else "[PERMISSIONS] Servicios de ubicación GPS APAGADOS. Android requiere GPS activo para escanear Wi-Fi.")
        logList.put(if (fineGranted) "[PERMISSIONS] Permiso ACCESS_FINE_LOCATION CONCEDIDO." else "[PERMISSIONS] Permiso ACCESS_FINE_LOCATION DENEGADO.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            logList.put(if (nearbyGranted) "[PERMISSIONS] Permiso NEARBY_WIFI_DEVICES CONCEDIDO." else "[PERMISSIONS] Permiso NEARBY_WIFI_DEVICES DENEGADO.")
        }
        ret.put("logs", logList)

        call.resolve(ret)
    }

    /**
     * Solicita todos los permisos de Wi-Fi y Ubicación necesarios en tiempo de ejecución
     */
    @PluginMethod
    fun requestWifiPermissions(call: PluginCall) {
        if (hasAllRequiredPermissions()) {
            val ret = JSObject()
            ret.put("granted", true)
            ret.put("message", "Todos los permisos ya se encuentran concedidos.")
            call.resolve(ret)
            return
        }

        val aliases = mutableListOf("location")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            aliases.add("nearbyWifi")
        }

        requestPermissionForAliases(aliases.toTypedArray(), call, "wifiPermissionsCallback")
    }

    @PermissionCallback
    private fun wifiPermissionsCallback(call: PluginCall) {
        val granted = hasAllRequiredPermissions()
        val ret = JSObject()
        ret.put("granted", granted)
        ret.put("fineLocation", getPermissionState("location") == PermissionState.GRANTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ret.put("nearbyWifi", getPermissionState("nearbyWifi") == PermissionState.GRANTED)
        }
        ret.put("message", if (granted) "Permisos autorizados satisfactoriamente." else "Permisos de escaneo Wi-Fi no concedidos.")
        call.resolve(ret)
    }

    /**
     * Abre los Ajustes de Wi-Fi del sistema
     */
    @PluginMethod
    fun openWifiSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val ret = JSObject()
            ret.put("opened", true)
            ret.put("message", "Ajustes de Wi-Fi abiertos con éxito.")
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("No se pudo abrir Ajustes de Wi-Fi: ${e.message}", e)
        }
    }

    /**
     * Abre los Ajustes de Ubicación (GPS) del sistema
     */
    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val ret = JSObject()
            ret.put("opened", true)
            ret.put("message", "Ajustes de Ubicación abiertos con éxito.")
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("No se pudo abrir Ajustes de Ubicación: ${e.message}", e)
        }
    }

    /**
     * Verifica si el Wi-Fi está encendido
     */
    @PluginMethod
    fun isWifiEnabled(call: PluginCall) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible en este dispositivo.")
            return
        }
        val ret = JSObject()
        ret.put("enabled", manager.isWifiEnabled)
        call.resolve(ret)
    }

    /**
     * Obtiene la red actualmente conectada con detalles reales de radio
     */
    @PluginMethod
    fun getConnectedNetwork(call: PluginCall) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible.")
            return
        }

        try {
            val wifiInfo: WifiInfo? = manager.connectionInfo
            val ret = JSObject()

            if (wifiInfo != null && wifiInfo.networkId != -1) {
                var ssid = wifiInfo.ssid ?: "<Desconocido>"
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }

                ret.put("connected", true)
                ret.put("ssid", ssid)
                ret.put("bssid", wifiInfo.bssid ?: "00:00:00:00:00:00")
                ret.put("rssi", wifiInfo.rssi)
                ret.put("linkSpeedMbps", wifiInfo.linkSpeed)
                ret.put("frequency", wifiInfo.frequency)
                ret.put("ipAddress", formatIpAddress(wifiInfo.ipAddress))
                ret.put("networkId", wifiInfo.networkId)
                
                val channel = frequencyToChannel(wifiInfo.frequency)
                ret.put("channel", channel)
                ret.put("band", frequencyToBand(wifiInfo.frequency))
            } else {
                ret.put("connected", false)
            }

            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Fallo al consultar red conectada: ${e.message}", e)
            call.reject("Error al obtener red conectada: ${e.message}", e)
        }
    }

    /**
     * Ejecuta el escaneo de redes físicas Wi-Fi mediante WifiManager
     * Maneja el BroadcastReceiver, compatibilidad Android 13/14 y watchdog timeout
     */
    @PluginMethod
    fun scanNetworks(call: PluginCall) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible en este dispositivo.")
            return
        }

        // 1. Verificar Wi-Fi activo
        if (!manager.isWifiEnabled) {
            val ret = JSObject()
            ret.put("error", "WIFI_DISABLED")
            ret.put("message", "El adaptador Wi-Fi de Android está apagado. Actívalo para realizar el escaneo.")
            ret.put("networks", JSArray())
            ret.put("count", 0)
            call.resolve(ret)
            return
        }

        // 2. Verificar permisos
        if (!hasAllRequiredPermissions()) {
            val aliases = mutableListOf("location")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                aliases.add("nearbyWifi")
            }
            requestPermissionForAliases(aliases.toTypedArray(), call, "scanPermissionCallback")
            return
        }

        // 3. Ejecutar escaneo con BroadcastReceiver y Watchdog de seguridad (evita bloqueos)
        try {
            // Cancelar timeout y receiver previo si existían
            cancelScanTimeout()
            unregisterScanReceiver()

            pendingScanCall = call
            registerScanReceiver()

            // Iniciar escaneo físico por radiofrecuencia
            val started = manager.startScan()
            Log.d(TAG, "[SCAN] manager.startScan() retornó: $started")

            // Armar Watchdog de 3.5 segundos: si el SO demora el broadcast o lo estrangula (throttle),
            // se entregan los resultados disponibles en el búfer de hardware inmediatamente.
            val timeoutRunnable = Runnable {
                Log.d(TAG, "[SCAN] Watchdog timeout cumplido. Entregando resultados de escaneo en búfer...")
                pendingScanCall?.let { c ->
                    deliverScanResults(c, "búfer_hardware_watchdog")
                    pendingScanCall = null
                }
                unregisterScanReceiver()
            }
            scanTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 3500)

        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Excepción durante inicio de escaneo: ${e.message}", e)
            deliverScanResults(call, "fallback_inmediato_error")
        }
    }

    @PermissionCallback
    private fun scanPermissionCallback(call: PluginCall) {
        if (hasAllRequiredPermissions()) {
            scanNetworks(call)
        } else {
            val ret = JSObject()
            ret.put("error", "PERMISSIONS_DENIED")
            ret.put("message", "Permiso de localización denegado. Requerido por Android para escanear redes Wi-Fi.")
            ret.put("networks", JSArray())
            ret.put("count", 0)
            call.resolve(ret)
        }
    }

    private fun registerScanReceiver() {
        if (scanReceiver != null) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                Log.d(TAG, "[SCAN] Broadcast SCAN_RESULTS_AVAILABLE recibido. Actualizado: $success")
                cancelScanTimeout()
                pendingScanCall?.let { call ->
                    deliverScanResults(call, "broadcast_completado")
                    pendingScanCall = null
                }
                unregisterScanReceiver()
            }
        }

        // Android 13/14+ requiere especificar Context.RECEIVER_EXPORTED o RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver, filter)
        }
    }

    private fun unregisterScanReceiver() {
        scanReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (ignored: Exception) {}
            scanReceiver = null
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            scanTimeoutRunnable = null
        }
    }

    private fun deliverScanResults(call: PluginCall, source: String) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible.")
            return
        }

        try {
            val rawResults: List<ScanResult> = manager.scanResults ?: emptyList()
            Log.d(TAG, "[SCAN] Obtenidas ${rawResults.size} redes desde WifiManager (origen: $source)")

            val jsNetworks = JSArray()
            for (res in rawResults) {
                val net = JSObject()
                val ssid = if (res.SSID.isNullOrEmpty()) "<Red Oculta>" else res.SSID
                net.put("ssid", ssid)
                net.put("bssid", res.BSSID ?: "00:00:00:00:00:00")
                net.put("rssi", res.level)
                net.put("freq", res.frequency)
                net.put("channel", frequencyToChannel(res.frequency))
                net.put("band", frequencyToBand(res.frequency))
                net.put("width", parseChannelWidth(res))
                net.put("standard", parseWifiStandard(res))
                net.put("security", parseCapabilities(res.capabilities))
                net.put("vendor", resolveVendor(res.BSSID))
                net.put("snr", Math.max(5, res.level + 95))
                net.put("timestamp", res.timestamp)
                jsNetworks.put(net)
            }

            val ret = JSObject()
            ret.put("networks", jsNetworks)
            ret.put("count", rawResults.size)
            ret.put("source", source)
            ret.put("timestamp", System.currentTimeMillis())
            ret.put("locationServicesEnabled", isLocationServicesEnabled())
            call.resolve(ret)
        } catch (e: SecurityException) {
            Log.e(TAG, "[ERROR] SecurityException en scanResults: ${e.message}", e)
            val ret = JSObject()
            ret.put("error", "SECURITY_EXCEPTION")
            ret.put("message", "Falta permiso de localización o NEARBY_WIFI_DEVICES en el sistema: ${e.message}")
            ret.put("networks", JSArray())
            ret.put("count", 0)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Fallo al leer ScanResults: ${e.message}", e)
            call.reject("Fallo al leer ScanResults de Android: ${e.message}", e)
        }
    }

    /**
     * Conexión a red mediante mecanismos oficiales del SO
     */
    @PluginMethod
    fun connectToNetwork(call: PluginCall) {
        val ssid = call.getString("ssid")
        val password = call.getString("password") ?: ""
        val security = call.getString("security") ?: "WPA2"

        if (ssid.isNullOrEmpty()) {
            call.reject("El SSID es obligatorio para la conexión.")
            return
        }

        val cm = connectivityManager
        if (cm == null) {
            call.reject("ConnectivityManager no disponible.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifierBuilder = android.net.wifi.WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)

                if (password.isNotEmpty()) {
                    if (security.uppercase().contains("WPA3") || security.uppercase().contains("SAE")) {
                        specifierBuilder.setWpa3Passphrase(password)
                    } else {
                        specifierBuilder.setWpa2Passphrase(password)
                    }
                }

                val networkSpecifier = specifierBuilder.build()
                val request = android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(networkSpecifier)
                    .build()

                activeNetworkCallback?.let {
                    try { cm.unregisterNetworkCallback(it) } catch (ignored: Exception) {}
                }

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        val ret = JSObject()
                        ret.put("connected", true)
                        ret.put("ssid", ssid)
                        ret.put("message", "Conectado a $ssid mediante el gestor del sistema.")
                        call.resolve(ret)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        call.reject("No se pudo conectar a $ssid. Petición cancelada o credenciales inválidas.")
                    }
                }

                activeNetworkCallback = callback
                cm.requestNetwork(request, callback)
            } else {
                @Suppress("DEPRECATION")
                val manager = wifiManager
                if (manager == null) {
                    call.reject("WifiManager no disponible.")
                    return
                }

                @Suppress("DEPRECATION")
                val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                    this.SSID = "\"$ssid\""
                    if (password.isNotEmpty()) {
                        this.preSharedKey = "\"$password\""
                    } else {
                        this.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                }

                @Suppress("DEPRECATION")
                val netId = manager.addNetwork(wifiConfig)
                if (netId != -1) {
                    @Suppress("DEPRECATION")
                    manager.disconnect()
                    @Suppress("DEPRECATION")
                    manager.enableNetwork(netId, true)
                    @Suppress("DEPRECATION")
                    manager.reconnect()
                    val ret = JSObject()
                    ret.put("connected", true)
                    ret.put("ssid", ssid)
                    ret.put("message", "Petición de conexión enviada a $ssid.")
                    call.resolve(ret)
                } else {
                    call.reject("Error al añadir configuración de red.")
                }
            }
        } catch (e: Exception) {
            call.reject("Fallo en la conexión oficial de Android: ${e.message}", e)
        }
    }

    @PluginMethod
    fun disconnectNetwork(call: PluginCall) {
        val cm = connectivityManager
        activeNetworkCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
                activeNetworkCallback = null
            } catch (ignored: Exception) {}
        }
        val ret = JSObject()
        ret.put("disconnected", true)
        call.resolve(ret)
    }

    /**
     * Escanea automáticamente las redes disponibles, filtra las redes abiertas
     * (sin contraseña ni filtros de seguridad), elige la mejor por potencia (RSSI)
     * y se conecta inmediatamente a ella sin intervención del usuario.
     */
    @PluginMethod
    fun autoConnectBestOpenNetwork(call: PluginCall) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible.")
            return
        }

        if (!manager.isWifiEnabled) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("error", "WIFI_DISABLED")
            ret.put("message", "El adaptador Wi-Fi de Android está apagado.")
            call.resolve(ret)
            return
        }

        try {
            // Obtener resultados de escaneo actuales
            var rawResults: List<ScanResult> = manager.scanResults ?: emptyList()
            if (rawResults.isEmpty()) {
                manager.startScan()
                rawResults = manager.scanResults ?: emptyList()
            }

            // Filtrar redes abiertas sin contraseña
            val openNetworks = rawResults.filter { res ->
                val cap = res.capabilities?.uppercase() ?: ""
                val isOpen = !cap.contains("WPA") && !cap.contains("WEP") && !cap.contains("SAE") && !cap.contains("EAP") && !cap.contains("PSK")
                val validSsid = !res.SSID.isNullOrEmpty() && !res.SSID.contains("<Red Oculta>") && !res.SSID.contains("0x00")
                isOpen && validSsid
            }.sortedByDescending { it.level }

            if (openNetworks.isEmpty()) {
                val ret = JSObject()
                ret.put("success", false)
                ret.put("foundOpenNetwork", false)
                ret.put("totalNetworksScanned", rawResults.size)
                ret.put("message", "No se encontraron redes Wi-Fi abiertas sin contraseña en el entorno inmediato.")
                call.resolve(ret)
                return
            }

            val bestNet = openNetworks.first()
            val ssid = bestNet.SSID
            Log.d(TAG, "[AUTO-CONNECT] Mejor red abierta encontrada: $ssid con señal ${bestNet.level} dBm en canal ${frequencyToChannel(bestNet.frequency)}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .build()

                val request = android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                val cm = connectivityManager
                activeNetworkCallback?.let {
                    try { cm?.unregisterNetworkCallback(it) } catch (ignored: Exception) {}
                }

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        val ret = JSObject()
                        ret.put("success", true)
                        ret.put("connected", true)
                        ret.put("foundOpenNetwork", true)
                        ret.put("ssid", ssid)
                        ret.put("bssid", bestNet.BSSID ?: "")
                        ret.put("rssi", bestNet.level)
                        ret.put("frequency", bestNet.frequency)
                        ret.put("channel", frequencyToChannel(bestNet.frequency))
                        ret.put("band", frequencyToBand(bestNet.frequency))
                        ret.put("message", "Conectado automáticamente a la mejor red abierta: $ssid")
                        call.resolve(ret)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        val ret = JSObject()
                        ret.put("success", false)
                        ret.put("connected", false)
                        ret.put("ssid", ssid)
                        ret.put("message", "Tiempo de espera agotado al conectar a $ssid.")
                        call.resolve(ret)
                    }
                }

                activeNetworkCallback = callback
                cm?.requestNetwork(request, callback)
            } else {
                @Suppress("DEPRECATION")
                val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                    this.SSID = "\"$ssid\""
                    this.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                }

                @Suppress("DEPRECATION")
                val netId = manager.addNetwork(wifiConfig)
                if (netId != -1) {
                    @Suppress("DEPRECATION")
                    manager.disconnect()
                    @Suppress("DEPRECATION")
                    manager.enableNetwork(netId, true)
                    @Suppress("DEPRECATION")
                    manager.reconnect()

                    val ret = JSObject()
                    ret.put("success", true)
                    ret.put("connected", true)
                    ret.put("foundOpenNetwork", true)
                    ret.put("ssid", ssid)
                    ret.put("bssid", bestNet.BSSID ?: "")
                    ret.put("rssi", bestNet.level)
                    ret.put("channel", frequencyToChannel(bestNet.frequency))
                    ret.put("band", frequencyToBand(bestNet.frequency))
                    ret.put("message", "Asociación enviada a la mejor red abierta: $ssid")
                    call.resolve(ret)
                } else {
                    val ret = JSObject()
                    ret.put("success", false)
                    ret.put("message", "Fallo al asignar configuración de red abierta en Android.")
                    call.resolve(ret)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Fallo en auto-conexión abierta: ${e.message}", e)
            call.reject("Error durante la conexión automática a red abierta: ${e.message}", e)
        }
    }

    /**
     * En caso de no encontrar redes abiertas, escanea y localiza la red privada con mejor señal (RSSI),
     * verifica si existe una clave registrada en la bóveda de emergencia o parámetro suministrado,
     * y ejecuta la conexión automática a la red privada.
     */
    @PluginMethod
    fun autoConnectBestPrivateNetwork(call: PluginCall) {
        val manager = wifiManager
        if (manager == null) {
            call.reject("WifiManager no disponible.")
            return
        }

        if (!manager.isWifiEnabled) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("error", "WIFI_DISABLED")
            ret.put("message", "El adaptador Wi-Fi de Android está apagado.")
            call.resolve(ret)
            return
        }

        try {
            var rawResults: List<ScanResult> = manager.scanResults ?: emptyList()
            if (rawResults.isEmpty()) {
                manager.startScan()
                rawResults = manager.scanResults ?: emptyList()
            }

            // Filtrar redes privadas (con seguridad WPA, WPA2, WPA3, WEP)
            val privateNetworks = rawResults.filter { res ->
                val cap = res.capabilities?.uppercase() ?: ""
                val isPrivate = cap.contains("WPA") || cap.contains("WEP") || cap.contains("SAE") || cap.contains("PSK") || cap.contains("EAP")
                val validSsid = !res.SSID.isNullOrEmpty() && !res.SSID.contains("<Red Oculta>") && !res.SSID.contains("0x00")
                isPrivate && validSsid
            }.sortedByDescending { it.level }

            if (privateNetworks.isEmpty()) {
                val ret = JSObject()
                ret.put("success", false)
                ret.put("foundPrivateNetwork", false)
                ret.put("totalNetworksScanned", rawResults.size)
                ret.put("message", "No se detectaron redes privadas disponibles en el rango de radiofrecuencia.")
                call.resolve(ret)
                return
            }

            val bestNet = privateNetworks.first()
            val ssid = bestNet.SSID
            val security = parseCapabilities(bestNet.capabilities)
            val fallbackPassword = call.getString("fallbackPassword") ?: ""
            val knownPasswords = call.getArray("knownPasswords")

            var matchedPassword = ""
            if (knownPasswords != null) {
                try {
                    for (i in 0 until knownPasswords.length()) {
                        val obj = knownPasswords.getJSONObject(i)
                        val s = obj.optString("ssid", "")
                        if (s.equals(ssid, ignoreCase = true)) {
                            matchedPassword = obj.optString("password", "")
                            break
                        }
                    }
                } catch (ignored: Exception) {}
            }

            if (matchedPassword.isEmpty() && fallbackPassword.isNotEmpty()) {
                matchedPassword = fallbackPassword
            }

            Log.d(TAG, "[AUTO-PRIVATE] Mejor red privada: $ssid con señal ${bestNet.level} dBm ($security). Clave encontrada: ${matchedPassword.isNotEmpty()}")

            if (matchedPassword.isNotEmpty()) {
                // Proceder con la conexión automática usando la clave de la bóveda
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val specifierBuilder = android.net.wifi.WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)

                    if (security.uppercase().contains("WPA3") || security.uppercase().contains("SAE")) {
                        specifierBuilder.setWpa3Passphrase(matchedPassword)
                    } else {
                        specifierBuilder.setWpa2Passphrase(matchedPassword)
                    }

                    val networkSpecifier = specifierBuilder.build()
                    val request = android.net.NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(networkSpecifier)
                        .build()

                    val cm = connectivityManager
                    activeNetworkCallback?.let {
                        try { cm?.unregisterNetworkCallback(it) } catch (ignored: Exception) {}
                    }

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            val ret = JSObject()
                            ret.put("success", true)
                            ret.put("connected", true)
                            ret.put("isPrivate", true)
                            ret.put("hasPassword", true)
                            ret.put("ssid", ssid)
                            ret.put("bssid", bestNet.BSSID ?: "")
                            ret.put("rssi", bestNet.level)
                            ret.put("security", security)
                            ret.put("frequency", bestNet.frequency)
                            ret.put("channel", frequencyToChannel(bestNet.frequency))
                            ret.put("band", frequencyToBand(bestNet.frequency))
                            ret.put("message", "Conexión automática exitosa a red privada: $ssid")
                            call.resolve(ret)
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            val ret = JSObject()
                            ret.put("success", false)
                            ret.put("connected", false)
                            ret.put("ssid", ssid)
                            ret.put("message", "Tiempo de espera agotado al conectar a red privada $ssid.")
                            call.resolve(ret)
                        }
                    }

                    activeNetworkCallback = callback
                    cm?.requestNetwork(request, callback)
                } else {
                    @Suppress("DEPRECATION")
                    val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                        this.SSID = "\"$ssid\""
                        this.preSharedKey = "\"$matchedPassword\""
                    }

                    @Suppress("DEPRECATION")
                    val netId = manager.addNetwork(wifiConfig)
                    if (netId != -1) {
                        @Suppress("DEPRECATION")
                        manager.disconnect()
                        @Suppress("DEPRECATION")
                        manager.enableNetwork(netId, true)
                        @Suppress("DEPRECATION")
                        manager.reconnect()

                        val ret = JSObject()
                        ret.put("success", true)
                        ret.put("connected", true)
                        ret.put("isPrivate", true)
                        ret.put("hasPassword", true)
                        ret.put("ssid", ssid)
                        ret.put("bssid", bestNet.BSSID ?: "")
                        ret.put("rssi", bestNet.level)
                        ret.put("security", security)
                        ret.put("channel", frequencyToChannel(bestNet.frequency))
                        ret.put("band", frequencyToBand(bestNet.frequency))
                        ret.put("message", "Asociación enviada a red privada: $ssid")
                        call.resolve(ret)
                    } else {
                        val ret = JSObject()
                        ret.put("success", false)
                        ret.put("message", "No se pudo configurar la red privada en Android.")
                        call.resolve(ret)
                    }
                }
            } else {
                // Se encontró la mejor red privada pero no hay clave en la bóveda
                val ret = JSObject()
                ret.put("success", true)
                ret.put("connected", false)
                ret.put("isPrivate", true)
                ret.put("requiresPassword", true)
                ret.put("ssid", ssid)
                ret.put("bssid", bestNet.BSSID ?: "")
                ret.put("rssi", bestNet.level)
                ret.put("security", security)
                ret.put("frequency", bestNet.frequency)
                ret.put("channel", frequencyToChannel(bestNet.frequency))
                ret.put("band", frequencyToBand(bestNet.frequency))
                ret.put("message", "Mejor red privada detectada: $ssid ($security, ${bestNet.level} dBm). Requiere clave de acceso.")
                call.resolve(ret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Fallo en auto-conexión privada: ${e.message}", e)
            call.reject("Error durante la conexión automática a red privada: ${e.message}", e)
        }
    }
        val fineLocationGranted = getPermissionState("location") == PermissionState.GRANTED
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPermissionState("nearbyWifi") == PermissionState.GRANTED
        } else {
            true
        }
        return fineLocationGranted && nearbyGranted
    }

    private fun isLocationServicesEnabled(): Boolean {
        val lm = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun frequencyToChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5170..5825 -> (freq - 5000) / 5
            freq in 5925..7125 -> ((freq - 5950) / 5) + 1
            else -> 0
        }
    }

    private fun frequencyToBand(freq: Int): String {
        return when {
            freq in 2400..2500 -> "2.4GHz"
            freq in 4900..5899 -> "5GHz"
            freq >= 5900 -> "6GHz"
            else -> "2.4GHz"
        }
    }

    private fun parseChannelWidth(res: ScanResult): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return when (res.channelWidth) {
                ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
                ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
                ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
                ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
                ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
                else -> "20 MHz"
            }
        }
        return "20 MHz"
    }

    private fun parseWifiStandard(res: ScanResult): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (res.wifiStandard) {
                ScanResult.WIFI_STANDARD_11AX -> "802.11ax (WiFi 6)"
                ScanResult.WIFI_STANDARD_11AC -> "802.11ac (WiFi 5)"
                ScanResult.WIFI_STANDARD_11N -> "802.11n (WiFi 4)"
                ScanResult.WIFI_STANDARD_11G -> "802.11g"
                ScanResult.WIFI_STANDARD_11A -> "802.11a"
                ScanResult.WIFI_STANDARD_11B -> "802.11b"
                else -> if (res.frequency > 5000) "802.11ac" else "802.11n"
            }
        }
        return if (res.frequency > 5000) "802.11ac (WiFi 5)" else "802.11n (WiFi 4)"
    }

    private fun parseCapabilities(capabilities: String?): String {
        if (capabilities == null) return "OPEN"
        val cap = capabilities.uppercase()
        return when {
            cap.contains("SAE") || cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") -> "WPA2"
            cap.contains("WPA") -> "WPA"
            cap.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
    }

    private fun resolveVendor(bssid: String?): String {
        if (bssid == null || bssid.length < 8) return "Fabricante Estándar"
        val prefix = bssid.substring(0, 8).uppercase()
        return when {
            prefix.startsWith("00:26:86") || prefix.startsWith("00:40:96") -> "Cisco Systems"
            prefix.startsWith("70:3A:0E") || prefix.startsWith("00:0B:86") -> "Aruba / HPE"
            prefix.startsWith("74:83:C2") || prefix.startsWith("B4:FB:E4") -> "Ubiquiti Networks"
            prefix.startsWith("18:E8:29") || prefix.startsWith("50:D4:F7") -> "TP-Link"
            prefix.startsWith("44:D9:E7") || prefix.startsWith("CC:2D:E0") -> "MikroTik"
            prefix.startsWith("9C:3D:CF") || prefix.startsWith("24:0A:C4") -> "Espressif IoT"
            prefix.startsWith("AC:84:C6") || prefix.startsWith("20:E5:2A") -> "Netgear"
            prefix.startsWith("F4:92:BF") || prefix.startsWith("00:1E:58") -> "D-Link"
            prefix.startsWith("34:E8:94") || prefix.startsWith("A4:91:B1") -> "Intel WiFi"
            prefix.startsWith("38:F9:D3") || prefix.startsWith("BC:D0:74") -> "Apple Inc."
            prefix.startsWith("90:9A:4A") || prefix.startsWith("30:52:CB") -> "Samsung Mobile"
            prefix.startsWith("28:6C:07") || prefix.startsWith("78:45:58") -> "Xiaomi / Redmi"
            prefix.startsWith("C0:25:E9") || prefix.startsWith("F8:32:E4") -> "Huawei Technologies"
            else -> "Router / AP IEEE 802.11"
        }
    }

    private fun formatIpAddress(ip: Int): String {
        return (ip and 0xFF).toString() + "." +
                ((ip shr 8) and 0xFF) + "." +
                ((ip shr 16) and 0xFF) + "." +
                ((ip shr 24) and 0xFF)
    }

    override fun handleOnDestroy() {
        cancelScanTimeout()
        unregisterScanReceiver()
        super.handleOnDestroy()
    }
}
