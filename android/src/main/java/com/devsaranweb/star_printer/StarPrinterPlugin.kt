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
import com.starmicronics.stario10.PrinterDelegate
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarIO10Exception
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.starxpandcommand.DocumentBuilder
import com.starmicronics.stario10.starxpandcommand.DrawerBuilder
import com.starmicronics.stario10.starxpandcommand.PrinterBuilder
import com.starmicronics.stario10.starxpandcommand.StarXpandCommandBuilder
import com.starmicronics.stario10.starxpandcommand.drawer.Channel
import com.starmicronics.stario10.starxpandcommand.drawer.OpenParameter
import com.starmicronics.stario10.starxpandcommand.printer.CutType
import com.starmicronics.stario10.starxpandcommand.printer.ImageParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    // StarIO10's async API returns kotlinx.coroutines.Deferred — run awaits on
    // a plugin-scoped supervisor so one failed call never kills the scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var discoveryManager: StarDeviceDiscoveryManager? = null
    private var printer: StarPrinter? = null
    private var connected = false

    // Star officially supports Android 11+; the AAR's own minSdk is lower, so
    // enforce the documented floor with a clear runtime error.
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
            override fun onCommunicationError(e: StarIO10Exception) {
                val payload = JSObject()
                payload.put("message", e.message ?: "communication error")
                notifyListeners("communicationError", payload)
            }
        }

        scope.launch {
            try {
                newPrinter.openAsync().await()
                printer = newPrinter
                connected = true
                notifyListeners("connected", JSObject())
                call.resolve()
            } catch (e: Exception) {
                connected = false
                printer = null
                call.reject("Failed to connect: ${e.message}")
            }
        }
    }

    private fun closeCurrentPrinter() {
        val current = printer ?: return
        printer = null
        val wasConnected = connected
        connected = false
        scope.launch {
            try {
                current.closeAsync().await()
            } catch (_: Exception) {
                // best-effort close
            }
            if (wasConnected) notifyListeners("disconnected", JSObject())
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

        scope.launch {
            try {
                current.printRawDataAsync(bytes).await()
                call.resolve()
            } catch (e: Exception) {
                call.reject("Print failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun printText(call: PluginCall) {
        val current = printer
        if (current == null || !connected) {
            call.reject("Not connected to a printer")
            return
        }
        val text = call.getString("text")
        if (text.isNullOrEmpty()) {
            call.reject("text is required")
            return
        }
        val cut: CutType? = when (call.getString("cut") ?: "partial") {
            "none" -> null
            "full" -> CutType.Full
            else -> CutType.Partial
        }

        // DocumentBuilder path: the SDK renders per-model, so graphics-only
        // printers (TSP100III in factory Star Graphic Mode) rasterize the text
        // instead of silently dropping raw text-mode commands.
        val printerBuilder = PrinterBuilder().actionPrintText(text)
        if (cut != null) printerBuilder.actionCut(cut)
        val commands = StarXpandCommandBuilder()
            .addDocument(DocumentBuilder().addPrinter(printerBuilder))
            .getCommands()

        scope.launch {
            try {
                current.printAsync(commands).await()
                call.resolve()
            } catch (e: Exception) {
                call.reject("Print failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun printImage(call: PluginCall) {
        val current = printer
        if (current == null || !connected) {
            call.reject("Not connected to a printer")
            return
        }
        val encoded = call.getString("image")
        if (encoded.isNullOrEmpty()) {
            call.reject("image is required")
            return
        }

        val bitmap: android.graphics.Bitmap
        try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run {
                    call.reject("image is not a decodable bitmap")
                    return
                }
        } catch (e: Exception) {
            call.reject("image must be a base64-encoded PNG: ${e.message}")
            return
        }

        val width = call.getInt("width") ?: bitmap.width
        val cut: CutType? = when (call.getString("cut") ?: "partial") {
            "none" -> null
            "full" -> CutType.Full
            else -> CutType.Partial
        }

        // DocumentBuilder image path: the SDK converts to the connected model's
        // native raster commands — the only path graphics-only printers
        // (TSP100III series) can print at all.
        val printerBuilder = PrinterBuilder().actionPrintImage(ImageParameter(bitmap, width))
        if (cut != null) printerBuilder.actionCut(cut)
        val documentBuilder = DocumentBuilder().addPrinter(printerBuilder)
        if (call.getBoolean("openDrawer") == true) {
            documentBuilder.addDrawer(DrawerBuilder().actionOpen(OpenParameter().setChannel(Channel.No1)))
        }
        val commands = StarXpandCommandBuilder()
            .addDocument(documentBuilder)
            .getCommands()

        scope.launch {
            try {
                current.printAsync(commands).await()
                call.resolve()
            } catch (e: Exception) {
                call.reject("Print failed: ${e.message}")
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
        scope.launch {
            try {
                val status = current.getStatusAsync().await()
                val result = JSObject()
                result.put("online", !status.hasError)
                result.put("hasError", status.hasError)
                result.put("paperEmpty", status.paperEmpty)
                result.put("paperNearEmpty", status.paperNearEmpty)
                result.put("coverOpen", status.coverOpen)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Failed to read status: ${e.message}")
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
        scope.cancel()
        super.handleOnDestroy()
    }
}
