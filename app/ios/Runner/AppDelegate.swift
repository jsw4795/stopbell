import Flutter
import UIKit
#if DEBUG
import FirebaseCore
import FirebaseMessaging
#endif

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
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
      guard self.pendingRegistration == nil else {
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
