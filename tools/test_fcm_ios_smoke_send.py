"""One-shot FID sender contract; run with stdlib unittest, without real sends."""

import contextlib
import io
import json
import unittest
import urllib.error
from unittest.mock import patch

import fcm_ios_smoke_send as sender


TARGET = "private-registration-value"
ACCESS_TOKEN = "private-access-token"
FCM_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.FcmError"
UNREGISTERED_BODY = json.dumps({
    "error": {
        "status": "NOT_FOUND",
        "message": TARGET,
        "details": [{"@type": FCM_ERROR_TYPE, "errorCode": "UNREGISTERED"}],
    }
}).encode()


class SenderTest(unittest.TestCase):
    def run_main(self, options, body):
        error = urllib.error.HTTPError("https://example.invalid", 404, TARGET, {}, io.BytesIO(body))
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch("sys.argv", ["sender", "--project-id", "stopbell", *options]),
            patch.object(sender.getpass, "getpass", return_value=TARGET) as prompt,
            patch.object(sender.subprocess, "run") as auth,
            patch.object(sender.urllib.request, "urlopen", side_effect=error) as send,
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            auth.return_value.stdout = ACCESS_TOKEN
            result = sender.main()
        self.assertEqual(result, 1)
        auth.assert_called_once()
        prompt.assert_called_once()
        send.assert_called_once()  # HTTP failure must not retry or switch fields.
        output = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(TARGET, output)
        self.assertNotIn(ACCESS_TOKEN, output)
        if body:
            self.assertNotIn(body.decode("utf-8", errors="replace"), output)
        payload = json.loads(send.call_args.args[0].data)
        return payload, stderr.getvalue().strip()

    def test_sends_only_fid_once_without_fallback(self):
        payload, output = self.run_main([], UNREGISTERED_BODY)
        message = payload["message"]
        self.assertEqual(set(message) & {"fid", "token"}, {"fid"})
        self.assertEqual(message["fid"], TARGET)
        self.assertEqual(output, "FCM HTTP 404: status=NOT_FOUND, fcmErrorCode=UNREGISTERED")

    def test_payload_preserves_notification_and_apns(self):
        message = sender.build_payload(TARGET)["message"]
        self.assertEqual(message["notification"], {
            "title": "StopBell TASK-701 smoke",
            "body": "실제 iPhone에서 이 알림의 수신을 확인하세요.",
        })
        self.assertEqual(message["apns"], {
            "headers": {"apns-push-type": "alert", "apns-priority": "10"},
            "payload": {"aps": {"sound": "default"}},
        })

    def test_malformed_body_exits_without_disclosing_body(self):
        for body in (b"", TARGET.encode(), b'{"error":', b"\xff", b"[" * 2000):
            with self.subTest(body=body[:20]):
                _, output = self.run_main([], body)
                self.assertEqual(output, "FCM HTTP 404")

    def test_unexpected_schemas_and_echoed_values_are_not_logged(self):
        for response in (
            None, [], 12, "text", {}, {"error": []},
            {"error": {"status": [], "details": {}}},
            {"error": {"status": TARGET, "details": [
                None, [], {"@type": FCM_ERROR_TYPE, "errorCode": TARGET},
                {"@type": FCM_ERROR_TYPE, "errorCode": []},
                {"@type": "other-type", "errorCode": "UNREGISTERED"},
            ]}},
        ):
            with self.subTest(response=response):
                self.assertEqual(sender.format_http_error(404, json.dumps(response)), "FCM HTTP 404")

    def test_reads_only_fcm_error_detail(self):
        response = {"error": {"status": "NOT_FOUND", "details": [
            {"@type": "other-type", "errorCode": "INTERNAL"},
            None,
            {"@type": FCM_ERROR_TYPE, "errorCode": "UNREGISTERED"},
        ]}}
        self.assertEqual(sender.format_http_error(404, json.dumps(response)),
                         "FCM HTTP 404: status=NOT_FOUND, fcmErrorCode=UNREGISTERED")

    def test_empty_and_whitespace_target_validation_is_hidden(self):
        for target in ("", "  ", TARGET + " other"):
            with (
                self.subTest(target=target),
                patch("sys.argv", ["sender", "--project-id", "stopbell"]),
                patch.object(sender.getpass, "getpass", return_value=target),
                patch.object(sender.subprocess, "run") as auth,
                contextlib.redirect_stderr(io.StringIO()) as stderr,
                self.assertRaises(SystemExit) as exit_result,
            ):
                sender.main()
            self.assertEqual(exit_result.exception.code, 2)
            auth.assert_not_called()
            self.assertNotIn(TARGET, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
