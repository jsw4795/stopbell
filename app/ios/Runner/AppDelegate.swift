import Flutter
import UIKit
#if DEBUG
import FirebaseCore
import FirebaseMessaging
#endif

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
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
    channel.setMethodCallHandler { call, result in
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
      messaging.register { error in
        DispatchQueue.main.async {
          if error != nil {
            result(FlutterError(code: "fcm-registration-failed", message: nil, details: nil))
          } else {
            result(nil)
          }
        }
      }
    }
#endif
  }
}
