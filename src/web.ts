import { WebPlugin } from '@capacitor/core';

import type { StarPrinterPlugin, StarPrinterStatusResult } from './definitions';

/**
 * Web stub — Star Bluetooth printers are native-only.
 * Every method rejects with `unavailable()` so callers can feature-detect.
 */
export class StarPrinterWeb extends WebPlugin implements StarPrinterPlugin {
  async discover(): Promise<void> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async stopDiscovery(): Promise<void> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async connect(): Promise<void> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async disconnect(): Promise<void> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async isConnected(): Promise<{ connected: boolean }> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async printRaw(): Promise<void> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
  async getStatus(): Promise<StarPrinterStatusResult> {
    throw this.unavailable('StarPrinter is not available on web.');
  }
}
