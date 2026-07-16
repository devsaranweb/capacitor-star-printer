# @aybinv7/capacitor-star-printer

Minimal Capacitor plugin for **Star Micronics** receipt printers via the official
**StarXpand SDK (StarIO10)**. Provides the Bluetooth transport the generic
BLE/ESC-POS plugins cannot: **Bluetooth Classic SPP on Android** and **MFi/iAP2 on
iOS** — required by e.g. the TSP143IIIBI (TSP100III Bluetooth).

The plugin is a byte pipe: encode your receipt with any StarPRNT/Star Line encoder
(e.g. `@point-of-sale/receipt-printer-encoder` model `star-tsp100iii`) and send the
bytes with `printRaw()`.

## API

```ts
import { StarPrinter } from '@aybinv7/capacitor-star-printer';

await StarPrinter.addListener('printerFound', (p) => console.log(p.identifier, p.model));
await StarPrinter.discover({ timeoutMs: 10000 }); // fires printerFound / discoveryFinished
await StarPrinter.connect({ identifier });        // BT MAC (Android) / EA identifier (iOS)
await StarPrinter.printRaw({ data: Array.from(encodedBytes) });
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
- **TSP100III emulation**: the TSP100III line ships in *Star Graphic Mode*. For raw
  StarPRNT text commands, switch the printer's emulation to StarPRNT once via the
  Star Quick Setup Utility / memory switch. See Star's KB: "How to Change the
  Emulation on Star TSP100 Series Printers".
- **Web**: stub only; every call rejects with `unavailable`.

## Install

```sh
npm i github:devsaranweb/capacitor-star-printer#v0.1.0
npx cap sync
```

`dist/` is committed — installing from git needs no build step.
