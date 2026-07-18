import type { PluginListenerHandle } from '@capacitor/core';
/**
 * A Star printer found during discovery.
 *
 * `identifier` is opaque to callers: the Bluetooth MAC address on Android and
 * the External Accessory identifier on iOS. Pass it back to `connect()`.
 */
export interface StarPrinterDeviceInfo {
    identifier: string;
    /** Printer model name as reported by StarIO10 (e.g. "TSP143III"), if known. */
    model?: string;
    /** Interface the printer was found on. Always "bluetooth" for this plugin. */
    interface: string;
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
     * Start Bluetooth discovery. Found printers are emitted via the
     * `printerFound` event; `discoveryFinished` fires when the scan ends.
     *
     * Android: classic Bluetooth discovery via StarIO10 (runtime
     * BLUETOOTH_SCAN/BLUETOOTH_CONNECT permissions are requested by the plugin).
     * iOS: lists MFi printers already paired in Settings > Bluetooth — there is
     * no in-app scan on iOS.
     *
     * @param options.timeoutMs discovery window in ms (default 10000)
     */
    discover(options?: {
        timeoutMs?: number;
    }): Promise<void>;
    /** Stop an in-progress discovery. No-op when none is running. */
    stopDiscovery(): Promise<void>;
    /** Open a session to the printer with the given identifier. */
    connect(options: {
        identifier: string;
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
     * CAVEAT: TSP100III-series printers ship in Star Graphic Mode, where raw
     * text-mode StarPRNT may print nothing until the printer's emulation is
     * switched to StarPRNT. `printText()` works regardless of emulation.
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
