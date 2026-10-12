import Flutter
import UIKit
import FirebaseCore
import FirebaseMessaging

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  private var productRegistration: FIDRegistrationRequest?
  private var registrationDelegate: FIDRegistrationDelegate?
#if DEBUG
  private var pendingRegistration: FirebaseSmokeRegistration?
#endif

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let productChannel = FlutterMethodChannel(
      name: "com.stopbell.stopbell/push",
      binaryMessenger: engineBridge.applicationRegistrar.messenger()
    )
    productChannel.setMethodCallHandler { [weak self] call, result in
      guard let self else {
        result(FlutterError(code: "push-unavailable", message: nil, details: nil))
        return
      }
      self.handleProductPush(call, result: result)
    }
#if DEBUG
    // FlutterFire 16.7.0 does not expose Messaging.register() to Dart yet.
    // Use the SDK already linked by FlutterFire, only for TASK-701 smoke.
    let channel = FlutterMethodChannel(
      name: "com.stopbell.stopbell/firebase-smoke",
      binaryMessenger: engineBridge.applicationRegistrar.messenger()
    )
    channel.setMethodCallHandler { [weak self] call, result in
      guard call.method == "register" || call.method == "registerAPNs" else {
        result(FlutterMethodNotImplemented)
        return
      }
      guard FirebaseApp.app() != nil else {
        result(FlutterError(code: "firebase-not-initialized", message: nil, details: nil))
        return
      }
      let messaging = Messaging.messaging()
      if call.method == "registerAPNs" {
        DispatchQueue.main.async {
          UIApplication.shared.registerForRemoteNotifications()
          result(nil)
        }
        return
      }
      guard messaging.isInstallationIdEnabled, messaging.apnsToken != nil else {
        result(FlutterError(code: "fid-or-apns-not-ready", message: nil, details: nil))
        return
      }
      guard let self else {
        result(FlutterError(code: "smoke-unavailable", message: nil, details: nil))
        return
      }
      guard self.pendingRegistration == nil, self.productRegistration == nil else {
        result(FlutterError(code: "fcm-registration-in-progress", message: nil, details: nil))
        return
      }
      let request = FirebaseSmokeRegistration { [weak self] fid, errorCode in
        self?.pendingRegistration = nil
        if let errorCode {
          result(FlutterError(code: errorCode, message: nil, details: nil))
        } else {
          result(fid)
        }
      }
      self.pendingRegistration = request
      // Firebase is configured by Dart before this explicit smoke request.
      messaging.delegate = self
      messaging.register { [weak request] error in
        DispatchQueue.main.async {
          request?.complete(error: error)
        }
      }
    }
#endif
  }

  private func handleProductPush(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    // These storage/Settings calls deliberately do not require Firebase initialization.
    if call.method == "installationMarker" || call.method == "commitInstallationMarker" {
      do {
        result(try installationMarker(commit: call.method == "commitInstallationMarker"))
      } catch {
        result(FlutterError(code: "installation-storage-failed", message: nil, details: nil))
      }
      return
    }
    if call.method == "openSettings" {
      guard let url = URL(string: UIApplication.openSettingsURLString) else {
        result(FlutterError(code: "settings-unavailable", message: nil, details: nil))
        return
      }
      UIApplication.shared.open(url) { opened in
        result(opened ? nil : FlutterError(code: "settings-unavailable", message: nil, details: nil))
      }
      return
    }
    guard call.method == "register" || call.method == "registerAPNs" else {
      result(FlutterMethodNotImplemented)
      return
    }
    guard FirebaseApp.app() != nil else {
      result(FlutterError(code: "firebase-not-initialized", message: nil, details: nil))
      return
    }
    if call.method == "registerAPNs" {
      UIApplication.shared.registerForRemoteNotifications()
      result(nil)
      return
    }
    let messaging = Messaging.messaging()
    guard messaging.isInstallationIdEnabled, messaging.apnsToken != nil else {
      result(FlutterError(code: "fid-or-apns-not-ready", message: nil, details: nil))
      return
    }
    guard productRegistration == nil else {
      result(FlutterError(code: "fcm-registration-in-progress", message: nil, details: nil))
      return
    }
#if DEBUG
    guard pendingRegistration == nil else {
      result(FlutterError(code: "fcm-registration-in-progress", message: nil, details: nil))
      return
    }
#endif
    let previous = messaging.delegate
    let proxy = FIDRegistrationDelegate(previous: previous)
    registrationDelegate = proxy
    let request = FIDRegistrationRequest { [weak self, weak proxy] fid, code in
      if messaging.delegate === proxy { messaging.delegate = previous }
      self?.productRegistration = nil
      self?.registrationDelegate = nil
      if let code {
        result(FlutterError(code: code, message: nil, details: nil))
      } else {
        result(fid)
      }
    }
    productRegistration = request
    proxy.request = request
    messaging.delegate = proxy
    messaging.register { [weak request] error in
      DispatchQueue.main.async { request?.complete(error: error) }
    }
  }

  private func installationMarker(commit: Bool) throws -> [String: Any] {
    let manager = FileManager.default
    let directory = try manager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                    appropriateFor: nil, create: true)
    var url = directory.appendingPathComponent("stopbell-installation-v1.json")
    var value: [String: Any]
    if manager.fileExists(atPath: url.path) {
      guard let stored = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
            stored["id"] is String, stored["initialized"] is Bool else {
        throw NSError(domain: "StopBellInstallation", code: 1)
      }
      value = stored
    } else {
      value = ["id": UUID().uuidString.lowercased(), "initialized": false]
    }
    if commit { value["initialized"] = true }
    try JSONSerialization.data(withJSONObject: value).write(to: url, options: .atomic)
    var resource = URLResourceValues()
    resource.isExcludedFromBackup = true
    try url.setResourceValues(resource)
    return value
  }

}

#if DEBUG
extension AppDelegate: MessagingDelegate {
  func messaging(_ messaging: Messaging, didReceiveRegistration installationId: String?) {
    DispatchQueue.main.async { [weak self] in
      self?.pendingRegistration?.receiveRegistration(installationId)
    }
  }
}

// One TASK-701 smoke request. All events are handled on the main queue.
final class FirebaseSmokeRegistration {
  private var result: ((String?, String?) -> Void)?
  private var completionSucceeded = false
  private var fid: String?
  private var timeout: DispatchWorkItem?

  init(timeoutSeconds: TimeInterval = 30, result: @escaping (String?, String?) -> Void) {
    self.result = result
    let timeout = DispatchWorkItem { [weak self] in
      self?.finish(errorCode: "fcm-registration-timeout")
    }
    self.timeout = timeout
    DispatchQueue.main.asyncAfter(deadline: .now() + timeoutSeconds, execute: timeout)
  }

  func complete(error: Error?) {
    if error != nil {
      finish(errorCode: "fcm-registration-failed")
      return
    }
    completionSucceeded = true
    finishIfReady()
  }

  func receiveRegistration(_ installationId: String?) {
    guard let installationId, !installationId.isEmpty else {
      finish(errorCode: "fcm-registration-fid-missing")
      return
    }
    fid = installationId
    finishIfReady()
  }

  private func finishIfReady() {
    guard completionSucceeded, fid != nil else { return }
    finish(errorCode: nil)
  }

  private func finish(errorCode: String?) {
    guard let result else { return }
    self.result = nil
    timeout?.cancel()
    timeout = nil
    result(errorCode == nil ? fid : nil, errorCode)
    fid = nil
  }
}
#endif

// Temporarily observes FID registration while forwarding existing FlutterFire callbacks.
final class FIDRegistrationDelegate: NSObject, MessagingDelegate {
  let previous: MessagingDelegate?
  weak var request: FIDRegistrationRequest?
  init(previous: MessagingDelegate?) { self.previous = previous }

  func messaging(_ messaging: Messaging, didReceiveRegistration installationId: String?) {
    previous?.messaging?(messaging, didReceiveRegistration: installationId)
    DispatchQueue.main.async { [weak self] in self?.request?.receiveRegistration(installationId) }
  }

  func messaging(_ messaging: Messaging, didReceiveRegistrationToken token: String?) {
    previous?.messaging?(messaging, didReceiveRegistrationToken: token)
  }
}

// Completion and FID callback may arrive in either order. Exactly one bounded result.
final class FIDRegistrationRequest {
  private var result: ((String?, String?) -> Void)?
  private var completed = false
  private var fid: String?
  private var timeout: DispatchWorkItem?

  init(result: @escaping (String?, String?) -> Void) {
    self.result = result
    let timeout = DispatchWorkItem { [weak self] in self?.finish("fcm-registration-timeout") }
    self.timeout = timeout
    DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
  }
  func complete(error: Error?) {
    if error != nil { finish("fcm-registration-failed"); return }
    completed = true
    if fid != nil { finish(nil) }
  }
  func receiveRegistration(_ value: String?) {
    guard let value, !value.isEmpty else { finish("fcm-registration-fid-missing"); return }
    fid = value
    if completed { finish(nil) }
  }
  private func finish(_ code: String?) {
    guard let result else { return }
    self.result = nil
    timeout?.cancel()
    timeout = nil
    result(code == nil ? fid : nil, code)
    fid = nil
  }
}
