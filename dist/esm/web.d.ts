import { WebPlugin } from '@capacitor/core';
import type { StarPrinterPlugin, StarPrinterStatusResult } from './definitions';
/**
 * Web stub — Star printers are native-only on every interface (Bluetooth and,
 * on Android, USB). Every method rejects with `unavailable()` so callers can
 * feature-detect.
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
    printImage(): Promise<void>;
    getStatus(): Promise<StarPrinterStatusResult>;
}
