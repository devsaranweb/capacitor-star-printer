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
        ),
        // API <= 30 only. StarIO10 discovery is Bluetooth CLASSIC discovery,
        // which returns ZERO devices on API 30 without a granted runtime
        // location permission (`neverForLocation` only decouples the two from
        // API 31). Android 11 (API 30) is the one pre-31 release this plugin
        // supports, so without this a scan there silently finds nothing.
        Permission(
            alias = StarPrinterPlugin.LOCATION_ALIAS,
            strings = [Manifest.permission.ACCESS_FINE_LOCATION]
        )
    ]
)
class StarPrinterPlugin : Plugin() {

    companion object {
        const val BLUETOOTH_ALIAS = "bluetooth"
        const val LOCATION_ALIAS = "location"
        private const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000

        // JS-facing interface names. These literals are what callers compare
        // against, so they are lowercase and must never be derived from the
        // enum (InterfaceType.toString() is capitalised — "Bluetooth").
        private const val IFACE_BLUETOOTH = "bluetooth"
        private const val IFACE_USB = "usb"
        private const val IFACE_LAN = "lan"
    }

    /**
     * JS interface name -> StarIO10 enum.
     *
     * An absent/empty value means Bluetooth, so a v0.2.x caller that never
     * sends the option behaves exactly as before. An UNKNOWN value returns
     * null and the caller rejects — never a silent fallback, which would
     * connect a typo'd interface to the wrong transport.
     */
    private fun interfaceOf(value: String?): InterfaceType? = when (value?.lowercase()) {
        IFACE_USB -> InterfaceType.Usb
        IFACE_LAN -> InterfaceType.Lan
        IFACE_BLUETOOTH, null, "" -> InterfaceType.Bluetooth
        else -> null
    }

    /** StarIO10 enum -> JS interface name. See the IFACE_* note above. */
    private fun interfaceName(type: InterfaceType): String = when (type) {
        InterfaceType.Usb -> IFACE_USB
        InterfaceType.Lan -> IFACE_LAN
        else -> IFACE_BLUETOOTH
    }

    /**
     * The interface list a discover() call asked for. Absent/empty means
     * Bluetooth only (v0.2.x compatibility); an unknown entry returns null so
     * the caller can reject.
     */
    private fun requestedInterfaces(call: PluginCall): List<InterfaceType>? {
        val raw = call.getArray("interfaces") ?: return listOf(InterfaceType.Bluetooth)
        if (raw.length() == 0) return listOf(InterfaceType.Bluetooth)
        val resolved = mutableListOf<InterfaceType>()
        for (i in 0 until raw.length()) {
            val entry = raw.opt(i) as? String ?: return null
            resolved.add(interfaceOf(entry) ?: return null)
        }
        return resolved.distinct()
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

    /**
     * The permission alias this OS level actually needs, or null when nothing
     * has to be requested.
     *
     * - API 31+: BLUETOOTH_SCAN / BLUETOOTH_CONNECT (declared `neverForLocation`
     *   by the host, so no location permission is involved).
     * - API 30: the runtime BT permissions don't exist yet, but Bluetooth
     *   CLASSIC discovery yields ZERO results without ACCESS_FINE_LOCATION.
     */
    private fun requiredPermissionAlias(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (getPermissionState(BLUETOOTH_ALIAS) == PermissionState.GRANTED) null else BLUETOOTH_ALIAS
        else ->
            if (getPermissionState(LOCATION_ALIAS) == PermissionState.GRANTED) null else LOCATION_ALIAS
    }

    /**
     * Gate a call on the runtime permission its INTERFACES actually need.
     *
     * USB needs nothing from us: StarIO10 registers its own USB_PERMISSION
     * BroadcastReceiver + PendingIntent internally, so the SDK drives the
     * system dialog. Asking for BLUETOOTH_SCAN/CONNECT — or, on API 30,
     * ACCESS_FINE_LOCATION — for a USB-only call would pop an irrelevant
     * prompt the operator can refuse, blocking a print for no reason.
     *
     * The Bluetooth branch below is unchanged from v0.2.1: Bluetooth Classic
     * discovery returns ZERO devices without the permission (it does not
     * error), so a regression here fails silently.
     */
    private fun withInterfacePermission(
        call: PluginCall,
        interfaces: List<InterfaceType>,
        action: () -> Unit
    ) {
        unsupportedOsMessage()?.let {
            call.reject(it)
            return
        }
        if (!interfaces.contains(InterfaceType.Bluetooth)) {
            action()
            return
        }
        val alias = requiredPermissionAlias()
        if (alias == null) {
            action()
        } else {
            requestPermissionForAlias(alias, call, "bluetoothPermissionCallback")
        }
    }

    @PermissionCallback
    private fun bluetoothPermissionCallback(call: PluginCall) {
        if (requiredPermissionAlias() != null) {
            call.reject(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Bluetooth permission denied"
                else "Location permission denied — Android 11 needs it to discover Bluetooth printers"
            )
            return
        }
        // Only ever reached for a Bluetooth-bearing call, but stay total:
        // re-resolve the interfaces rather than assuming.
        when (call.methodName) {
            "discover" -> startDiscovery(call, requestedInterfaces(call) ?: listOf(InterfaceType.Bluetooth))
            "connect" -> openPrinter(call, interfaceOf(call.getString("interface")) ?: InterfaceType.Bluetooth)
            else -> call.reject("Unexpected method for permission callback: ${call.methodName}")
        }
    }

    @PluginMethod
    fun discover(call: PluginCall) {
        val interfaces = requestedInterfaces(call)
        if (interfaces == null) {
            call.reject("interfaces must contain only 'bluetooth', 'usb' or 'lan'")
            return
        }
        withInterfacePermission(call, interfaces) { startDiscovery(call, interfaces) }
    }

    private fun startDiscovery(call: PluginCall, interfaces: List<InterfaceType>) {
        try {
            discoveryManager?.stopDiscovery()

            val manager = StarDeviceDiscoveryManagerFactory.create(interfaces, context)
            manager.discoveryTime = call.getInt("timeoutMs") ?: DEFAULT_DISCOVERY_TIMEOUT_MS
            manager.callback = object : StarDeviceDiscoveryManager.Callback {
                override fun onPrinterFound(printer: StarPrinter) {
                    val settings = printer.connectionSettings
                    val info = JSObject()
                    info.put("identifier", settings.identifier)
                    info.put("model", printer.information?.model?.toString() ?: "")
                    info.put("interface", interfaceName(settings.interfaceType))
                    // USB only: a display hint for a unit that reports no serial
                    // number (the serial IS the identifier on that interface).
                    printer.information?.detail?.usb?.portName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { info.put("portName", it) }
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
        val iface = interfaceOf(call.getString("interface"))
        if (iface == null) {
            call.reject("interface must be 'bluetooth', 'usb' or 'lan'")
            return
        }
        // A blank identifier is legal on USB ONLY: some TSP100 units report no
        // USB serial number, and the serial IS the identifier there. Every
        // other interface still requires one (LAN's identifier is the IP
        // address) — otherwise a typo'd Bluetooth config would fall through to
        // FIRST_FOUND_DEVICE and open whichever paired Star printer answered
        // first.
        if (iface != InterfaceType.Usb && call.getString("identifier").isNullOrBlank()) {
            call.reject("identifier is required")
            return
        }
        withInterfacePermission(call, listOf(iface)) { openPrinter(call, iface) }
    }

    private fun openPrinter(call: PluginCall, iface: InterfaceType) {
        val requested = call.getString("identifier").orEmpty()
        // Blank USB identifier -> the SDK's own "whatever is plugged in"
        // sentinel. Only ONE serial-less USB printer is addressable this way.
        val identifier = if (requested.isBlank() && iface == InterfaceType.Usb) {
            StarConnectionSettings.FIRST_FOUND_DEVICE
        } else {
            requested
        }

        closeCurrentPrinter()

        val settings = StarConnectionSettings(iface, identifier)
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
