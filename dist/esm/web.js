import { WebPlugin } from '@capacitor/core';
/**
 * Web stub — Star printers are native-only on every interface (Bluetooth and,
 * on Android, USB). Every method rejects with `unavailable()` so callers can
 * feature-detect.
 */
export class StarPrinterWeb extends WebPlugin {
    async discover() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async stopDiscovery() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async connect() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async disconnect() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async isConnected() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async printRaw() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async printText() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async printImage() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
    async getStatus() {
        throw this.unavailable('StarPrinter is not available on web.');
    }
}
