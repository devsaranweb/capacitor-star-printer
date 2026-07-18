import { WebPlugin } from '@capacitor/core';
import type { StarPrinterPlugin, StarPrinterStatusResult } from './definitions';
/**
 * Web stub — Star Bluetooth printers are native-only.
 * Every method rejects with `unavailable()` so callers can feature-detect.
 */
export declare class StarPrinterWeb extends WebPlugin implements StarPrinterPlugin {
    discover(): Promise<void>;
    stopDiscovery(): Promise<void>;
    connect(): Promise<void>;
    disconnect(): Promise<void>;
    isConnected(): Promise<{
        connected: boolean;
    }>;
    printRaw(): Promise<void>;
    printText(): Promise<void>;
    getStatus(): Promise<StarPrinterStatusResult>;
}
