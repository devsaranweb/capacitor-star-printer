import Foundation
import Capacitor
import StarIO10

/// Capacitor bridge for the StarXpand SDK (StarIO10).
///
/// iOS notes:
/// - MFi Bluetooth printers pair in Settings > Bluetooth; `discover()` lists
///   paired accessories (there is no in-app scan on iOS).
/// - The HOST app must declare `UISupportedExternalAccessoryProtocols` with
///   `jp.star-m.starpro` in its Info.plist.
@objc(StarPrinterPlugin)
public class StarPrinterPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "StarPrinterPlugin"
    public let jsName = "StarPrinter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "discover", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopDiscovery", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isConnected", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printRaw", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printText", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise)
    ]

    private var discoveryManager: StarDeviceDiscoveryManager?
    // StarIO10 delegates are weak — the plugin holds the strong references.
    private var discoveryDelegate: DiscoveryDelegateBridge?
    private var printerDelegate: PrinterDelegateBridge?
    private var printer: StarPrinter?
    private var connected = false

    private static let defaultDiscoveryTimeoutMs = 10_000

    @objc func discover(_ call: CAPPluginCall) {
        do {
            try discoveryManager?.stopDiscovery()
        } catch {
            // stopping a finished discovery is not an error
        }

        do {
            let manager = try StarDeviceDiscoveryManagerFactory.create(interfaceTypes: [.bluetooth])
            manager.discoveryTime = call.getInt("timeoutMs") ?? Self.defaultDiscoveryTimeoutMs

            let delegate = DiscoveryDelegateBridge(
                onFound: { [weak self] identifier, model in
                    self?.notifyListeners("printerFound", data: [
                        "identifier": identifier,
                        "model": model,
                        "interface": "bluetooth"
                    ])
                },
                onFinished: { [weak self] in
                    self?.discoveryManager = nil
                    self?.discoveryDelegate = nil
                    self?.notifyListeners("discoveryFinished", data: [:])
                }
            )
            manager.delegate = delegate
            try manager.startDiscovery()

            discoveryManager = manager
            discoveryDelegate = delegate
            call.resolve()
        } catch {
            discoveryManager = nil
            discoveryDelegate = nil
            call.reject("Failed to start discovery: \(error.localizedDescription)")
        }
    }

    @objc func stopDiscovery(_ call: CAPPluginCall) {
        do {
            try discoveryManager?.stopDiscovery()
        } catch {
            // best-effort
        }
        discoveryManager = nil
        discoveryDelegate = nil
        call.resolve()
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let identifier = call.getString("identifier"), !identifier.isEmpty else {
            call.reject("identifier is required")
            return
        }

        closeCurrentPrinter()

        let settings = StarConnectionSettings(interfaceType: .bluetooth, identifier: identifier)
        let newPrinter = StarPrinter(settings)
        let delegate = PrinterDelegateBridge { [weak self] message in
            self?.notifyListeners("communicationError", data: ["message": message])
        }
        newPrinter.printerDelegate = delegate

        Task { [weak self] in
            do {
                try await newPrinter.open()
                self?.printer = newPrinter
                self?.printerDelegate = delegate
                self?.connected = true
                self?.notifyListeners("connected", data: [:])
                call.resolve()
            } catch {
                self?.printer = nil
                self?.printerDelegate = nil
                self?.connected = false
                call.reject("Failed to connect: \(error.localizedDescription)")
            }
        }
    }

    private func closeCurrentPrinter() {
        guard let current = printer else { return }
        let wasConnected = connected
        printer = nil
        printerDelegate = nil
        connected = false
        Task { [weak self] in
            await current.close()
            if wasConnected {
                self?.notifyListeners("disconnected", data: [:])
            }
        }
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        closeCurrentPrinter()
        call.resolve()
    }

    @objc func isConnected(_ call: CAPPluginCall) {
        call.resolve(["connected": connected && printer != nil])
    }

    @objc func printRaw(_ call: CAPPluginCall) {
        guard let current = printer, connected else {
            call.reject("Not connected to a printer")
            return
        }
        guard let raw = call.getArray("data"), !raw.isEmpty else {
            call.reject("data is required")
            return
        }

        var bytes = [UInt8]()
        bytes.reserveCapacity(raw.count)
        for value in raw {
            guard let number = value as? NSNumber else {
                call.reject("data must be an array of bytes")
                return
            }
            bytes.append(UInt8(truncating: number))
        }
        let data = Data(bytes)

        Task {
            do {
                // StarXpand manual: raw/binary printing on iOS. If the SDK
                // renames this entry point, this is the single line to adjust.
                try await current.print(raw: data)
                call.resolve()
            } catch {
                call.reject("Print failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func printText(_ call: CAPPluginCall) {
        guard let current = printer, connected else {
            call.reject("Not connected to a printer")
            return
        }
        guard let text = call.getString("text"), !text.isEmpty else {
            call.reject("text is required")
            return
        }

        // DocumentBuilder path: the SDK renders per-model, so graphics-only
        // printers (TSP100III in factory Star Graphic Mode) rasterize the text
        // instead of silently dropping raw text-mode commands.
        let printerBuilder = StarXpandCommand.PrinterBuilder().actionPrintText(text)
        switch call.getString("cut") ?? "partial" {
        case "none":
            break
        case "full":
            _ = printerBuilder.actionCut(.full)
        default:
            _ = printerBuilder.actionCut(.partial)
        }
        let commands = StarXpandCommand.StarXpandCommandBuilder()
            .addDocument(StarXpandCommand.DocumentBuilder().addPrinter(printerBuilder))
            .getCommands()

        Task {
            do {
                try await current.print(command: commands)
                call.resolve()
            } catch {
                call.reject("Print failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        guard let current = printer, connected else {
            call.reject("Not connected to a printer")
            return
        }
        Task {
            do {
                let status = try await current.getStatus()
                call.resolve([
                    "online": !status.hasError,
                    "hasError": status.hasError,
                    "paperEmpty": status.paperEmpty,
                    "paperNearEmpty": status.paperNearEmpty,
                    "coverOpen": status.coverOpen
                ])
            } catch {
                call.reject("Failed to read status: \(error.localizedDescription)")
            }
        }
    }
}

private final class DiscoveryDelegateBridge: NSObject, StarDeviceDiscoveryManagerDelegate {
    private let onFound: (String, String) -> Void
    private let onFinished: () -> Void

    init(onFound: @escaping (String, String) -> Void, onFinished: @escaping () -> Void) {
        self.onFound = onFound
        self.onFinished = onFinished
    }

    func manager(_ manager: StarDeviceDiscoveryManager, didFind printer: StarPrinter) {
        onFound(
            printer.connectionSettings.identifier,
            printer.information.map { String(describing: $0.model) } ?? ""
        )
    }

    func managerDidFinishDiscovery(_ manager: StarDeviceDiscoveryManager) {
        onFinished()
    }
}

// StarIO10's iOS PrinterDelegate is an @objc protocol with optional methods
// (unlike Android, where it is an abstract class).
private final class PrinterDelegateBridge: NSObject, PrinterDelegate {
    private let onCommunicationError: (String) -> Void

    init(onCommunicationError: @escaping (String) -> Void) {
        self.onCommunicationError = onCommunicationError
        super.init()
    }

    func printer(_ printer: StarPrinter, communicationErrorDidOccur error: Error) {
        onCommunicationError(error.localizedDescription)
    }
}
