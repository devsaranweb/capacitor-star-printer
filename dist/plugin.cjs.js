'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var core = require('@capacitor/core');

const StarPrinter = core.registerPlugin('StarPrinter', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.StarPrinterWeb()),
});

/**
 * Web stub — Star printers are native-only on every interface (Bluetooth and,
 * on Android, USB). Every method rejects with `unavailable()` so callers can
 * feature-detect.
 */
class StarPrinterWeb extends core.WebPlugin {
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

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    StarPrinterWeb: StarPrinterWeb
});

exports.StarPrinter = StarPrinter;
