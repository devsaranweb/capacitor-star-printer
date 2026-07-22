# @aybinv7/capacitor-star-printer

Minimal Capacitor plugin for **Star Micronics** receipt printers via the official
**StarXpand SDK (StarIO10)**. Provides the Bluetooth transport the generic
BLE/ESC-POS plugins cannot: **Bluetooth Classic SPP on Android** and **MFi/iAP2 on
iOS** — required by e.g. the TSP143IIIBI (TSP100III Bluetooth).

Two print paths:

- **`printImage()` (v0.2.0+, recommended)** — StarXpand DocumentBuilder raster path.
  Render your receipt to a bitmap app-side (e.g. a canvas at 576px for 80mm paper)
  and send it as base64 PNG. The SDK converts it to the connected model's native
  raster commands, so it prints on **every** supported Star model — including the
  graphics-only TSP100III series.
- **`printRaw()`** — raw byte pipe for StarPRNT/Star Line command streams (e.g. from
  `@point-of-sale/receipt-printer-encoder`). Only works on models with a text-mode
  command interpreter (TSP650II, mC-Print, TSP100IV, …) — **NOT** the TSP100III.

## API

```ts
import { StarPrinter } from '@aybinv7/capacitor-star-printer';

await StarPrinter.addListener('printerFound', (p) => console.log(p.identifier, p.model));
await StarPrinter.discover({ timeoutMs: 10000 }); // fires printerFound / discoveryFinished
await StarPrinter.connect({ identifier });        // BT MAC (Android) / EA identifier (iOS)
await StarPrinter.printImage({ image: base64Png, width: 576, cut: 'partial' }); // raster path (all models)
await StarPrinter.printRaw({ data: Array.from(encodedBytes) });                 // byte pipe (text-mode models only)
await StarPrinter.printText({ text: 'Hello from StarXpand\n', cut: 'partial' }); // plain-text DocumentBuilder path
const status = await StarPrinter.getStatus();     // online / paperEmpty / paperNearEmpty / coverOpen
await StarPrinter.disconnect();
```

Events: `printerFound`, `discoveryFinished`, `connected`, `disconnected`, `communicationError`.

## Platform notes

- **iOS**: MFi printers must first be paired in **Settings → Bluetooth**. `discover()`
  lists paired accessories; there is no in-app scan. The host app must declare
  `UISupportedExternalAccessoryProtocols` = `jp.star-m.starpro` in Info.plist.
- **Android**: the plugin runtime-requests `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`
  (API 31+). The host manifest must declare them (Capacitor default templates do).
- **TSP100III is graphics-only**: the TSP100III line has **no text-mode command
  interpreter at all** — StarPRNT text emulation only exists from the TSP100IV
  onward, and there is **no emulation switch** on a TSP100III (an earlier version of
  this README suggested one; that was wrong). `printRaw()` bytes are silently
  discarded: the call resolves, nothing prints. Use `printImage()` (full receipts)
  or `printText()` (plain text) — the DocumentBuilder pipeline rasterizes per-model
  and prints correctly on the whole TSP100 series.
- **Web**: stub only; every call rejects with `unavailable`.

## Install

```sh
npm i github:devsaranweb/capacitor-star-printer#v0.2.0
npx cap sync
```

`dist/` is committed — installing from git needs no build step.
