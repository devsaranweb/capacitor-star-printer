import type { PluginListenerHandle } from '@capacitor/core';
/**
 * Transport the plugin reaches a Star printer over.
 *
 * `usb` is ANDROID-ONLY: the iOS StarXpand SDK has no usable USB interface
 * (MFi/Lightning), so the iOS side accepts the option and refuses it.
 *
 * BACKWARD COMPATIBILITY: an OMITTED interface means `bluetooth` everywhere —
 * on the wire and in the native code — so a v0.2.x caller is unaffected.
 */
export type StarPrinterInterface = 'bluetooth' | 'usb';
/**
 * A Star printer found during discovery.
 *
 * `identifier` is opaque to callers: the Bluetooth MAC address (Android
 * Bluetooth), the External Accessory identifier (iOS), or the USB SERIAL
 * NUMBER (Android USB). Pass it back to `connect()`.
 */
export interface StarPrinterDeviceInfo {
    /**
     * See above. A USB printer may report a BLANK serial (observed on TSP100
     * units); passing an empty identifier back on the `usb` interface addresses
     * "the printer that is currently plugged in" (StarIO10's
     * `StarConnectionSettings.FIRST_FOUND_DEVICE`). Only ONE serial-less USB
     * printer is addressable that way.
     */
    identifier: string;
    /** Printer model name as reported by StarIO10 (e.g. "TSP143III"), if known. */
    model?: string;
    /** Interface the printer was found on. Omitted by pre-0.3.0 binaries = bluetooth. */
    interface?: StarPrinterInterface;
    /** Android USB only: the SDK's port name — a display hint for a serial-less unit. */
    portName?: string;
}
/**
 * Snapshot of the connected printer's hardware status.
 */
export interface StarPrinterStatusResult {
    online: boolean;
    hasError: boolean;
    paperEmpty: boolean;
    paperNearEmpty: boolean;
    coverOpen: boolean;
}
export interface StarPrinterPlugin {
    /**
     * Start discovery. Found printers are emitted via the `printerFound` event;
     * `discoveryFinished` fires when the scan ends.
     *
     * Android Bluetooth: classic Bluetooth discovery via StarIO10 (runtime
     * BLUETOOTH_SCAN/BLUETOOTH_CONNECT — or ACCESS_FINE_LOCATION on API 30 —
     * are requested by the plugin).
     * Android USB: enumerates printers on the USB host port. Returns instantly,
     * needs no runtime permission from the plugin (StarIO10 owns the USB
     * permission dialog), and only sees a printer that is plugged in AND powered.
     * iOS: lists MFi printers already paired in Settings > Bluetooth — there is
     * no in-app scan on iOS, and a USB-only request finds nothing.
     *
     * @param options.timeoutMs  discovery window in ms (default 10000)
     * @param options.interfaces interfaces to scan (default `['bluetooth']`);
     *                           `'usb'` is Android-only
     */
    discover(options?: {
        timeoutMs?: number;
        interfaces?: StarPrinterInterface[];
    }): Promise<void>;
    /** Stop an in-progress discovery. No-op when none is running. */
    stopDiscovery(): Promise<void>;
    /**
     * Open a session to the printer with the given identifier.
     *
     * @param options.interface  transport (default `'bluetooth'`). `'usb'` is
     *                           Android-only and is the ONLY interface that
     *                           accepts an empty `identifier` — see
     *                           {@link StarPrinterDeviceInfo.identifier}.
     */
    connect(options: {
        identifier: string;
        interface?: StarPrinterInterface;
    }): Promise<void>;
    /** Close the current printer session. No-op when not connected. */
    disconnect(): Promise<void>;
    /** Whether a printer session is currently open. */
    isConnected(): Promise<{
        connected: boolean;
    }>;
    /**
     * Send raw command bytes (e.g. StarPRNT output from a receipt encoder)
     * straight to the connected printer.
     *
     * CAVEAT: TSP100III-series printers are GRAPHICS-ONLY — they have no
     * text-mode command interpreter at all (StarPRNT text emulation only exists
     * from TSP100IV onward; there is no emulation switch on a TSP100III). Raw
     * text-mode StarPRNT bytes are silently discarded: the call resolves and
     * nothing prints. Use `printImage()` (or `printText()`) for those models —
     * the StarXpand DocumentBuilder pipeline rasterizes per-model.
     */
    printRaw(options: {
        data: number[];
    }): Promise<void>;
    /**
     * Print plain text through the StarXpand DocumentBuilder pipeline.
     *
     * Unlike `printRaw`, the SDK renders the document appropriately for the
     * connected model — on graphics-only printers (TSP100III series in factory
     * Star Graphic Mode) the text is rasterized, so this path prints correctly
     * without a printer emulation switch. Use it as the fallback / spike probe
     * when raw StarPRNT output comes out blank.
     *
     * @param options.text text to print (may contain \n line breaks)
     * @param options.cut  paper cut after printing: 'partial' (default), 'full', or 'none'
     */
    printText(options: {
        text: string;
        cut?: 'partial' | 'full' | 'none';
    }): Promise<void>;
    /**
     * Print a raster image through the StarXpand DocumentBuilder pipeline.
     *
     * This is THE print path for graphics-only printers (TSP100III series): the
     * SDK converts the document to the model's native raster commands, so it
     * prints on every supported Star model regardless of emulation. Render the
     * receipt to a bitmap app-side (e.g. a canvas) and pass it here.
     *
     * @param options.image      base64-encoded PNG (no `data:` prefix)
     * @param options.width      print width in dots (576 = 80mm @ 203dpi,
     *                           384 = 58mm); defaults to the image's own width
     * @param options.cut        paper cut after printing: 'partial' (default), 'full', or 'none'
     * @param options.openDrawer kick the cash drawer after printing (default false)
     */
    printImage(options: {
        image: string;
        width?: number;
        cut?: 'partial' | 'full' | 'none';
        openDrawer?: boolean;
    }): Promise<void>;
    /** Read the connected printer's hardware status. */
    getStatus(): Promise<StarPrinterStatusResult>;
    addListener(eventName: 'printerFound', listenerFunc: (printer: StarPrinterDeviceInfo) => void): Promise<PluginListenerHandle>;
    addListener(eventName: 'discoveryFinished', listenerFunc: () => void): Promise<PluginListenerHandle>;
    addListener(eventName: 'connected', listenerFunc: () => void): Promise<PluginListenerHandle>;
    addListener(eventName: 'disconnected', listenerFunc: () => void): Promise<PluginListenerHandle>;
    addListener(eventName: 'communicationError', listenerFunc: (error: {
        message: string;
    }) => void): Promise<PluginListenerHandle>;
    removeAllListeners(): Promise<void>;
}
