#!/usr/bin/env python3
import base64
import concurrent.futures
import http.cookiejar
import json
import re
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ORIGIN = "https://panel.jvm.test"
CONTEXT = ssl.create_default_context(cafile="/opt/jvm_dashboard/data/caddy/pki/authorities/local/root.crt")


class Client:
    def __init__(self):
        self.csrf = ""
        self.http = urllib.request.build_opener(urllib.request.HTTPSHandler(context=CONTEXT), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, method="GET", body=None, expected=200):
        headers = {"Origin": ORIGIN, "X-CSRF-Token": self.csrf}
        if body is not None:
            headers["Content-Type"] = "application/json"
        r = urllib.request.Request(ORIGIN + "/api/v1" + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
        try:
            response = self.http.open(r, timeout=150)
        except urllib.error.HTTPError as e:
            response = e
        data = json.loads(response.read())
        assert response.status == expected, (path, response.status, data)
        if path == "/auth/me":
            self.csrf = data["csrf"]
        return data

    def wait(self, operation):
        for _ in range(150):
            r = self.request("/operations/" + operation)
            if r["status"] in ("SUCCEEDED", "FAILED"):
                assert r["status"] == "SUCCEEDED", r
                return
            time.sleep(1)
        raise AssertionError("Operation did not finish")


def main():
    client = Client()
    if sys.argv[1] == "--reboot":
        client.request("/auth/login", "POST", {"email": "admin@example.test", "password": "integration-password-123"})
        client.request("/auth/me")
        for _ in range(30):
            apps = client.request("/applications")["items"]
            if all(a["observed"] == "STOPPED" for a in apps):
                break
            time.sleep(2)
        assert apps and all(a["observed"] == "STOPPED" for a in apps), apps
        assert subprocess.check_output(["findmnt", "-n", "-o", "OPTIONS", "/opt/jvm_dashboard/data/apps"]).decode().find("prjquota") >= 0
        print("PASS: reboot preserves accounts, manual Stop and XFS mount")
        return
    journal = subprocess.check_output(["journalctl", "-u", "jvm-dashboard-api", "-o", "cat", "--no-pager"]).decode()
    invitation = re.findall(r"BOOTSTRAP_INVITATION=.*#token=([^\s]+)", journal)[-1]
    account = {"name": "Admin", "email": "admin@example.test", "password": "integration-password-123", "token": invitation}
    client.request("/auth/register", "POST", account, 201)
    Client().request("/auth/register", "POST", {**account, "email": "second@example.test"}, 400)
    me = client.request("/auth/me")
    ws = me["workspaces"][0]["id"]
    runtimes = client.request("/runtimes")["items"]
    assert len(runtimes) >= 2
    for jdk in (8, 21):
        spec = {"name": "smoke-jdk-" + str(jdk), "jdk": jdk, "cpu": .25, "memoryMiB": 256, "diskMiB": 128, "jar": "app.jar", "autostart": True}
        created = client.request("/applications", "POST", {"workspaceId": ws, "spec": spec}, 202)
        aid = created["id"]
        client.wait(created["operationId"])
        files = "/applications/" + aid + "/files"
        jar = Path(sys.argv[1]).read_bytes()
        upload = client.request(files, "POST", {"action": "uploadStart", "path": "app.jar", "size": len(jar)})
        client.request(files, "POST", {"action": "uploadChunk", "id": upload["id"], "offset": 0, "data": base64.b64encode(jar).decode()})
        client.request(files, "POST", {"action": "uploadFinish", "id": upload["id"], "path": "app.jar"})
        client.request(files, "POST", {"action": "read", "path": "../etc/passwd"}, 400)
        start = client.request("/applications/" + aid + "/actions/start", "POST", {}, 202)
        client.wait(start["operationId"])
        limits = json.loads(subprocess.check_output(["docker", "inspect", "jvm-" + aid]))[0]["HostConfig"]
        assert limits["Memory"] == 256 * 1024**2 and limits["MemorySwap"] == limits["Memory"] and limits["NanoCpus"] == 250000000 and limits["ReadonlyRootfs"]
        assert "JVM_SMOKE_READY" in client.request("/applications/" + aid + "/logs")["text"]
        stopped = client.request("/applications/" + aid + "/actions/stop", "POST", {}, 202)
        client.wait(stopped["operationId"])
        print("PASS: JDK", jdk, "upload, Start, enforced Docker limits, logs, Stop")
    invite = client.request("/workspaces/" + ws + "/invitations", "POST", {"role": "VIEWER", "email": "viewer@example.test"}, 201)
    viewer = Client()
    viewer.request("/auth/register", "POST", {"name": "Viewer", "email": "viewer@example.test", "password": "viewer-password-123", "token": invite["url"].split("token=")[1]}, 201)
    viewer.request("/auth/me")
    assert viewer.request("/applications")["total"] == 0
    viewer.request("/applications/" + aid, expected=404)
    viewer.request("/applications/" + aid + "/actions/start", "POST", {}, 404)
    rules = client.request("/nodes/local/firewall", "POST", {"action": "status"})
    assert rules["enabled"]
    print("PASS: invitations, object isolation, UFW status")


if __name__ == "__main__":
    main()
