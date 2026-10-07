"""TASK-701 one-shot FID notification; token targeting is diagnostic-only."""

import argparse
import getpass
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request


def build_payload(target, target_field="fid"):
    # FID remains the TASK-701 contract; token is only a legacy compatibility diagnostic.
    if target_field not in ("fid", "token"):
        raise ValueError("Unsupported target field")
    return {
        "message": {
            target_field: target,
            "notification": {
                "title": "StopBell TASK-701 smoke",
                "body": "실제 iPhone에서 이 알림의 수신을 확인하세요.",
            },
            "apns": {
                "headers": {"apns-push-type": "alert", "apns-priority": "10"},
                "payload": {"aps": {"sound": "default"}},
            },
        }
    }


def format_http_error(http_status, body):
    summary = f"FCM HTTP {http_status}"
    try:
        response = json.loads(body)
    except (ValueError, TypeError, RecursionError):
        return summary
    error = response.get("error") if isinstance(response, dict) else None
    if not isinstance(error, dict):
        return summary
    fields = []
    status = error.get("status")
    # Allow only known enum values: arbitrary response strings may echo a target.
    if isinstance(status, str) and status in (
        "OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED",
        "NOT_FOUND", "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED",
        "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED",
        "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "UNAUTHENTICATED",
    ):
        fields.append(f"status={status}")
    details = error.get("details")
    if isinstance(details, list):
        for detail in details:
            if not isinstance(detail, dict) or detail.get("@type") != (
                "type.googleapis.com/google.firebase.fcm.v1.FcmError"
            ):
                continue
            code = detail.get("errorCode")
            if isinstance(code, str) and code in (
                "UNSPECIFIED_ERROR", "INVALID_ARGUMENT", "UNREGISTERED",
                "SENDER_ID_MISMATCH", "QUOTA_EXCEEDED", "APNS_AUTH_ERROR",
                "THIRD_PARTY_AUTH_ERROR", "UNAVAILABLE", "INTERNAL",
            ):
                fields.append(f"fcmErrorCode={code}")
                break
    return summary + (": " + ", ".join(fields) if fields else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    parser.add_argument(
        "--target-field", choices=("fid", "token"), default="fid",
        help="Target field (default: fid); token is diagnostic-only for legacy registration compatibility.",
    )
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", args.project_id):
        parser.error("실제 Firebase project ID를 입력하세요.")
    target = getpass.getpass("iPhone debug 화면의 registration value (입력 숨김): ").strip()
    if not target or any(char.isspace() for char in target):
        parser.error("등록 성공 화면의 registration value를 입력하세요.")
    try:
        access_token = subprocess.run(
            ["gcloud", "auth", "print-access-token"],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout.strip()
        payload = build_payload(target, args.target_field)
        request = urllib.request.Request(
            f"https://fcm.googleapis.com/v1/projects/{args.project_id}/messages:send",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            message_id = json.load(response)["name"]
        print(f"FCM accepted: {message_id}")
        print("Provider acceptance는 iPhone 수신 성공이 아닙니다. 실제 화면을 확인하세요.")
        return 0
    except urllib.error.HTTPError as error:
        # Never log the raw response, request payload, target, or access token.
        with error:
            try:
                body = error.read()
            except (OSError, ValueError):
                body = b""
        print(format_http_error(error.code, body), file=sys.stderr)
    except (OSError, subprocess.SubprocessError, ValueError, KeyError):
        print("전송 실패: gcloud 로그인/네트워크/응답을 확인하세요. 자동 재시도하지 않습니다.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
