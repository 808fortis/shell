package com.shizush.shizuku

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

enum class ShellProvider {
    NONE, SHIZUKU, WIRELESS_DEBUGGING, DIRECT
}

data class WirelessDebugInfo(
    val enabled: Boolean,
    val ipAddress: String,
    val port: Int,
    val isConnected: Boolean
)

class ShizukuManager(private val context: Context) {
    companion object {
        private const val TAG = "ShizukuManager"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.manager"
        private const val SHIZUKU_REQUEST_CODE = 10001
    }

    private val _provider = MutableStateFlow(ShellProvider.NONE)
    val provider: StateFlow<ShellProvider> = _provider

    private val _wirelessInfo = MutableStateFlow(WirelessDebugInfo(false, "", 0, false))
    val wirelessInfo: StateFlow<WirelessDebugInfo> = _wirelessInfo

    private var _shizukuPermissionGranted = false

    suspend fun initialize(): ShellProvider {
        val provider = when {
            isShizukuAvailable() -> {
                Log.d(TAG, "Shizuku API available")
                ShellProvider.SHIZUKU
            }
            isWirelessDebuggingAvailable() -> {
                Log.d(TAG, "Wireless debugging available")
                ShellProvider.WIRELESS_DEBUGGING
            }
            else -> {
                Log.d(TAG, "Using direct shell")
                ShellProvider.DIRECT
            }
        }
        _provider.value = provider
        refreshWirelessInfo()
        return provider
    }

    fun refreshWirelessInfo() {
        val adbEnabled = isAdbEnabled()
        val port = getAdbPort()
        val ip = getWifiIpAddress()
        _wirelessInfo.value = WirelessDebugInfo(
            enabled = adbEnabled && port > 0,
            ipAddress = ip,
            port = port,
            isConnected = adbEnabled && port > 0 && ip.isNotEmpty()
        )
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (intent != null) {
                    context.startActivity(intent)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch Shizuku", e2)
            }
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
        } catch (e: Exception) {
            try {
                val pm = context.packageManager
                pm.getPackageInfo(SHIZUKU_PACKAGE, 0)
                false
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun isWirelessDebuggingAvailable(): Boolean {
        return isAdbEnabled() && getAdbPort() > 0
    }

    private fun isAdbEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun getAdbPort(): Int {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                "adb_wifi_enabled",
                0
            )
        } catch (e: Exception) {
            try {
                Settings.Global.getInt(
                    context.contentResolver,
                    "adb_port",
                    0
                )
            } catch (e2: Exception) {
                0
            }
        }
    }

    private fun getWifiIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            val ip = address.hostAddress ?: continue
                            if (!ip.startsWith("127.") && !ip.startsWith("192.168.43")) {
                                return ip
                            }
                        }
                    }
                }
            }
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xFF,
                        ipInt shr 8 and 0xFF,
                        ipInt shr 16 and 0xFF,
                        ipInt shr 24 and 0xFF
                    )
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi IP", e)
            ""
        }
    }

    suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        when (_provider.value) {
            ShellProvider.SHIZUKU -> executeWithShizuku(command)
            ShellProvider.WIRELESS_DEBUGGING -> executeWithAdbShell(command)
            ShellProvider.DIRECT -> executeDirect(command)
            ShellProvider.NONE -> ShellResult("", "No shell provider available", -1)
        }
    }

    private fun executeDirect(command: String): ShellResult {
        return try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec(arrayOf("/system/bin/sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    private fun executeWithShizuku(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed", e)
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    private fun executeWithAdbShell(command: String): ShellResult {
        return try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec(arrayOf("/system/bin/sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open developer settings", e)
        }
    }
}

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val output: String get() = when {
        stderr.isEmpty() -> stdout
        stdout.isEmpty() -> stderr
        else -> "$stdout\n$stderr"
    }
}
