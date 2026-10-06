"""TASK-701 one-shot FID notification; uses the user's existing gcloud login."""

import argparse
import getpass
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", args.project_id):
        parser.error("실제 Firebase project ID를 입력하세요.")
    fid = getpass.getpass("iPhone debug 화면의 FID (입력 숨김): ").strip()
    if not fid or any(char.isspace() for char in fid):
        parser.error("등록 성공 화면의 FID를 입력하세요.")
    try:
        access_token = subprocess.run(
            ["gcloud", "auth", "print-access-token"],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout.strip()
        payload = {
            "message": {
                "fid": fid,
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
        # Raw error bodies may echo the target: print only HTTP status.
        print(f"FCM HTTP {error.code}: project/IAM/APNs/FID 구성을 확인하세요.", file=sys.stderr)
    except (OSError, subprocess.SubprocessError, ValueError, KeyError):
        print("전송 실패: gcloud 로그인/네트워크/응답을 확인하세요. 자동 재시도하지 않습니다.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
