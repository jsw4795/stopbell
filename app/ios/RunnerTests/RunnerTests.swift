import Foundation
import XCTest
@testable import Runner

#if DEBUG
class RunnerTests: XCTestCase {
  func test_completion_waits_for_registration_callback() {
    var results = 0
    let request = FirebaseSmokeRegistration { fid, error in
      results += 1
      XCTAssertTrue(fid == "registered-fixture")
      XCTAssertNil(error)
    }
    request.complete(error: nil)
    XCTAssertEqual(results, 0)
    request.receiveRegistration("registered-fixture")
    request.receiveRegistration("registered-fixture")
    request.complete(error: nil)
    XCTAssertEqual(results, 1)
  }

  func test_callback_waits_for_completion() {
    var results = 0
    let request = FirebaseSmokeRegistration { fid, error in
      results += 1
      XCTAssertTrue(fid == "registered-fixture")
      XCTAssertNil(error)
    }
    request.receiveRegistration("registered-fixture")
    XCTAssertEqual(results, 0)
    request.complete(error: nil)
    XCTAssertEqual(results, 1)
  }

  func test_completion_error_wins_over_early_callback() {
    var results = 0
    let request = FirebaseSmokeRegistration { fid, error in
      results += 1
      XCTAssertNil(fid)
      XCTAssertEqual(error, "fcm-registration-failed")
    }
    request.receiveRegistration("registered-fixture")
    request.complete(error: NSError(domain: "fixture", code: 1))
    request.complete(error: nil)
    request.receiveRegistration("registered-fixture")
    XCTAssertEqual(results, 1)
  }

  func test_completion_error_returns_without_callback() {
    var results = 0
    let request = FirebaseSmokeRegistration { fid, error in
      results += 1
      XCTAssertNil(fid)
      XCTAssertEqual(error, "fcm-registration-failed")
    }
    request.complete(error: NSError(domain: "fixture", code: 1))
    XCTAssertEqual(results, 1)
  }

  func test_missing_callback_fid_is_rejected() {
    for fid in [nil, ""] as [String?] {
      var results = 0
      let request = FirebaseSmokeRegistration { fid, error in
        results += 1
        XCTAssertNil(fid)
        XCTAssertEqual(error, "fcm-registration-fid-missing")
      }
      request.complete(error: nil)
      request.receiveRegistration(fid)
      request.receiveRegistration("registered-fixture")
      XCTAssertEqual(results, 1)
    }
  }

  func test_timeout_bounds_missing_completion_or_callback() {
    for callbackFirst in [false, true] {
      var results = 0
      let timedOut = expectation(description: "bounded registration")
      let request = FirebaseSmokeRegistration(timeoutSeconds: 0.01) { fid, error in
        results += 1
        XCTAssertNil(fid)
        XCTAssertEqual(error, "fcm-registration-timeout")
        timedOut.fulfill()
      }
      if callbackFirst {
        request.receiveRegistration("registered-fixture")
      } else {
        request.complete(error: nil)
      }
      wait(for: [timedOut], timeout: 1)
      request.complete(error: nil)
      request.receiveRegistration("registered-fixture")
      XCTAssertEqual(results, 1)
    }
  }
}
#endif
