package com.devsaranweb.star_printer

import android.Manifest
import android.os.Build
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarIO10CommunicationException
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.PrinterDelegate

@CapacitorPlugin(
    name = "StarPrinter",
    permissions = [
        Permission(
            alias = StarPrinterPlugin.BLUETOOTH_ALIAS,
            strings = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN]
        )
    ]
)
class StarPrinterPlugin : Plugin() {

    companion object {
        const val BLUETOOTH_ALIAS = "bluetooth"
        private const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000
    }

    private var discoveryManager: StarDeviceDiscoveryManager? = null
    private var printer: StarPrinter? = null
    private var connected = false

    // StarIO10 officially supports Android 11+ (the AAR may declare a higher
    // minSdk than the host app; the manifest override keeps the merge green,
    // this guard keeps runtime behavior honest).
    private fun unsupportedOsMessage(): String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "Star printers require Android 11 or newer" else null

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getPermissionState(BLUETOOTH_ALIAS) == PermissionState.GRANTED

    private fun withBluetoothPermission(call: PluginCall, action: () -> Unit) {
        unsupportedOsMessage()?.let {
            call.reject(it)
            return
        }
        if (hasBluetoothPermission()) {
            action()
        } else {
            requestPermissionForAlias(BLUETOOTH_ALIAS, call, "bluetoothPermissionCallback")
        }
    }

    @PermissionCallback
    private fun bluetoothPermissionCallback(call: PluginCall) {
        if (!hasBluetoothPermission()) {
            call.reject("Bluetooth permission denied")
            return
        }
        when (call.methodName) {
            "discover" -> startDiscovery(call)
            "connect" -> openPrinter(call)
            else -> call.reject("Unexpected method for permission callback: ${call.methodName}")
        }
    }

    @PluginMethod
    fun discover(call: PluginCall) {
        withBluetoothPermission(call) { startDiscovery(call) }
    }

    private fun startDiscovery(call: PluginCall) {
        try {
            discoveryManager?.stopDiscovery()

            val manager = StarDeviceDiscoveryManagerFactory.create(listOf(InterfaceType.Bluetooth), context)
            manager.discoveryTime = call.getInt("timeoutMs") ?: DEFAULT_DISCOVERY_TIMEOUT_MS
            manager.callback = object : StarDeviceDiscoveryManager.Callback {
                override fun onPrinterFound(printer: StarPrinter) {
                    val info = JSObject()
                    info.put("identifier", printer.connectionSettings.identifier)
                    info.put("model", printer.information?.model?.toString() ?: "")
                    info.put("interface", "bluetooth")
                    notifyListeners("printerFound", info)
                }

                override fun onDiscoveryFinished() {
                    discoveryManager = null
                    notifyListeners("discoveryFinished", JSObject())
                }
            }
            manager.startDiscovery()
            discoveryManager = manager
            call.resolve()
        } catch (e: Exception) {
            discoveryManager = null
            call.reject("Failed to start discovery: ${e.message}", null, e)
        }
    }

    @PluginMethod
    fun stopDiscovery(call: PluginCall) {
        try {
            discoveryManager?.stopDiscovery()
        } catch (_: Exception) {
            // stopping a finished discovery is not an error
        }
        discoveryManager = null
        call.resolve()
    }

    @PluginMethod
    fun connect(call: PluginCall) {
        if (call.getString("identifier").isNullOrBlank()) {
            call.reject("identifier is required")
            return
        }
        withBluetoothPermission(call) { openPrinter(call) }
    }

    private fun openPrinter(call: PluginCall) {
        val identifier = call.getString("identifier") ?: return call.reject("identifier is required")

        closeCurrentPrinter()

        val settings = StarConnectionSettings(InterfaceType.Bluetooth, identifier)
        val newPrinter = StarPrinter(settings, context)
        newPrinter.printerDelegate = object : PrinterDelegate() {
            override fun onCommunicationError(e: StarIO10CommunicationException) {
                val payload = JSObject()
                payload.put("message", e.message ?: "communication error")
                notifyListeners("communicationError", payload)
            }
        }

        newPrinter.openAsync().whenComplete { _, error ->
            if (error != null) {
                connected = false
                printer = null
                call.reject("Failed to connect: ${error.cause?.message ?: error.message}")
            } else {
                printer = newPrinter
                connected = true
                notifyListeners("connected", JSObject())
                call.resolve()
            }
        }
    }

    private fun closeCurrentPrinter() {
        val current = printer ?: return
        printer = null
        val wasConnected = connected
        connected = false
        try {
            current.closeAsync().whenComplete { _, _ ->
                if (wasConnected) notifyListeners("disconnected", JSObject())
            }
        } catch (_: Exception) {
            // best-effort close
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        closeCurrentPrinter()
        call.resolve()
    }

    @PluginMethod
    fun isConnected(call: PluginCall) {
        val result = JSObject()
        result.put("connected", connected && printer != null)
        call.resolve(result)
    }

    @PluginMethod
    fun printRaw(call: PluginCall) {
        val current = printer
        if (current == null || !connected) {
            call.reject("Not connected to a printer")
            return
        }
        val data = call.getArray("data")
        if (data == null || data.length() == 0) {
            call.reject("data is required")
            return
        }

        val bytes: List<Byte>
        try {
            bytes = (0 until data.length()).map { (data.getInt(it) and 0xFF).toByte() }
        } catch (e: Exception) {
            call.reject("data must be an array of bytes: ${e.message}")
            return
        }

        current.printRawDataAsync(bytes).whenComplete { _, error ->
            if (error != null) {
                call.reject("Print failed: ${error.cause?.message ?: error.message}")
            } else {
                call.resolve()
            }
        }
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val current = printer
        if (current == null || !connected) {
            call.reject("Not connected to a printer")
            return
        }
        current.getStatusAsync().whenComplete { status, error ->
            if (error != null || status == null) {
                call.reject("Failed to read status: ${error?.cause?.message ?: error?.message ?: "unknown"}")
            } else {
                val result = JSObject()
                result.put("online", !status.hasError)
                result.put("hasError", status.hasError)
                result.put("paperEmpty", status.paperEmpty)
                result.put("paperNearEmpty", status.paperNearEmpty)
                result.put("coverOpen", status.coverOpen)
                call.resolve(result)
            }
        }
    }

    override fun handleOnDestroy() {
        try {
            discoveryManager?.stopDiscovery()
        } catch (_: Exception) {
            // best-effort
        }
        discoveryManager = null
        closeCurrentPrinter()
        super.handleOnDestroy()
    }
}
