#!/usr/bin/env python3
import base64
import concurrent.futures
import http.cookiejar
import json
import os
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

    def download(self,aid,path,expected,headers=None):
        request=urllib.request.Request(ORIGIN+"/api/v1/applications/"+aid+"/files/download?path="+urllib.parse.quote(path),headers=headers or {})
        with self.http.open(request,timeout=150) as response:
            assert response.status==expected,response.status
            return response.read()


def main():
    client = Client()
    if sys.argv[1] == "--reboot":
        client.request("/auth/login", "POST", {"email": "admin@example.test", "password": "integration-password-123"})
        client.request("/auth/me")
        expected=json.loads(Path("/root/jvm-smoke-reboot.json").read_text())
        for _ in range(60):
            apps = client.request("/applications")["items"]
            if apps and all(a["observed"] == expected[a["id"]] for a in apps):
                break
            time.sleep(2)
        assert apps and all(a["observed"] == expected[a["id"]] for a in apps), apps
        assert subprocess.check_output(["findmnt", "-n", "-o", "OPTIONS", "/opt/jvm_dashboard/data/apps"]).decode().find("prjquota") >= 0
        for app in apps:
            if expected[app["id"]]=="RUNNING":
                assert "JVM_SMOKE_READY" in client.request("/applications/"+app["id"]+"/logs")["text"]
                stopped=client.request("/applications/"+app["id"]+"/actions/stop","POST",{},202)
                client.wait(stopped["operationId"])
        print("PASS: reboot preserves accounts, manual Stop, autostart and XFS mount")
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
    reboot_expected={}
    for jdk in (8, 21):
        spec = {"name": "smoke-jdk-" + str(jdk), "jdk": jdk, "cpu": .25, "memoryMiB": 256, "diskMiB": 128, "jar": "app.jar", "autostart": True}
        created = client.request("/applications", "POST", {"workspaceId": ws, "spec": spec}, 202)
        aid = created["id"]
        reboot_expected[aid]="STOPPED"
        client.wait(created["operationId"])
        files = "/applications/" + aid + "/files"
        jar = Path(sys.argv[1]).read_bytes()
        upload = client.request(files, "POST", {"action": "uploadStart", "path": "app.jar", "size": len(jar)})
        client.request(files, "POST", {"action": "uploadChunk", "id": upload["id"], "offset": 0, "data": base64.b64encode(jar).decode()})
        client.request(files, "POST", {"action": "uploadFinish", "id": upload["id"], "path": "app.jar"})
        assert client.download(aid,"app.jar",200)==jar
        assert client.download(aid,"app.jar",206,{"Range":"bytes=0-15"})==jar[:16]
        client.request(files, "POST", {"action": "read", "path": "../etc/passwd"}, 400)
        start = client.request("/applications/" + aid + "/actions/start", "POST", {}, 202)
        client.wait(start["operationId"])
        for _ in range(30):
            metrics=client.request("/applications/"+aid+"/metrics")
            if metrics.get("state")=="RUNNING" and metrics.get("diskUsedBytes") is not None:
                break
            time.sleep(1)
        assert metrics["nodeOnline"] and metrics["diskUsedBytes"]>0 and metrics["uptimeSeconds"]>=0,metrics
        # Finishing an upload must use its saved path, not the caller's path.
        replacement=client.request(files,"POST",{"action":"uploadStart","path":"app.jar","size":len(jar)})
        client.request(files,"POST",{"action":"uploadChunk","id":replacement["id"],"offset":0,"data":base64.b64encode(jar).decode()})
        client.request(files,"POST",{"action":"uploadFinish","id":replacement["id"],"path":"unrelated.txt"},409)
        client.request(files,"POST",{"action":"uploadCancel","id":replacement["id"]})
        limits = json.loads(subprocess.check_output(["docker", "inspect", "jvm-" + aid]))[0]["HostConfig"]
        assert limits["Memory"] == 256 * 1024**2 and limits["MemorySwap"] == limits["Memory"] and limits["NanoCpus"] == 250000000 and limits["ReadonlyRootfs"]
        assert "JVM_SMOKE_READY" in client.request("/applications/" + aid + "/logs")["text"]
        stopped = client.request("/applications/" + aid + "/actions/stop", "POST", {}, 202)
        client.wait(stopped["operationId"])
        # Large file helpers have a bounded service memory budget.
        client.request(files,"POST",{"action":"write","path":"config.json","text":"{}"})
        old=client.request(files,"POST",{"action":"read","path":"config.json"})
        client.request(files,"POST",{"action":"write","path":"config.json","text":"{\"updated\":true}","etag":old["etag"]})
        client.request(files,"POST",{"action":"write","path":"config.json","text":"stale","etag":old["etag"]},409)
        client.request(files,"POST",{"action":"mkdir","path":"directory"})
        client.request(files,"POST",{"action":"write","path":"directory/a.txt","text":"copied"})
        client.request(files,"POST",{"action":"copy","path":"directory","target":"directory-copy"})
        assert client.request(files,"POST",{"action":"read","path":"directory-copy/a.txt"})["text"]=="copied"
        if jdk==21:
            volume=Path("/opt/jvm_dashboard/data/apps")/aid
            user=json.loads(subprocess.check_output(["docker","inspect","jvm-"+aid]))[0]["Config"]["User"].split(":")[0]
            with open(volume/"large.bin","wb") as output:
                for _ in range(32): output.write(os.urandom(1024**2))
            os.chown(volume/"large.bin",int(user),int(user))
            client.request(files,"POST",{"action":"copy","path":"large.bin","target":"large-copy.bin"})
            client.request(files,"POST",{"action":"archive","path":"","paths":["large.bin"],"target":"large.zip"})
            assert client.download(aid,"large.bin",206,{"Range":"bytes=1048576-1048591"})==(volume/"large.bin").read_bytes()[1048576:1048592]
            for name in ("large.bin","large-copy.bin","large.zip"): (volume/name).unlink()
            print("PASS: 32 MiB copy/archive and ranged download within service memory caps")
        print("PASS: JDK", jdk, "upload, Start, enforced Docker limits, logs, Stop")
    invite = client.request("/workspaces/" + ws + "/invitations", "POST", {"role": "VIEWER", "email": "viewer@example.test"}, 201)
    viewer = Client()
    viewer.request("/auth/register", "POST", {"name": "Viewer", "email": "viewer@example.test", "password": "viewer-password-123", "token": invite["url"].split("token=")[1]}, 201)
    viewer.request("/auth/me")
    assert viewer.request("/applications")["total"] == 0
    viewer.request("/applications/" + aid, expected=404)
    viewer.request("/applications/" + aid + "/actions/start", "POST", {}, 404)
    # One running app must autostart; the other must remain manually stopped.
    start=client.request("/applications/"+aid+"/actions/start","POST",{},202)
    client.wait(start["operationId"])
    reboot_expected[aid]="RUNNING"
    Path("/root/jvm-smoke-reboot.json").write_text(json.dumps(reboot_expected))
    rules = client.request("/nodes/local/firewall", "POST", {"action": "status"})
    assert rules["enabled"]
    print("PASS: invitations, object isolation, UFW status")
    change=client.request("/nodes/local/firewall","POST",{"action":"add","generation":rules["generation"],"protocol":"tcp","decision":"deny","port":"18080","source":"127.0.0.1","description":"smoke rollback"})
    assert change["changeId"]
    # The timer must recover without a request from this process.
    time.sleep(95)
    after=client.request("/nodes/local/firewall","POST",{"action":"status"})
    assert after["pending"] is None and after["rules"]==rules["rules"],after
    print("PASS: independent UFW rollback timer")


if __name__ == "__main__":
    main()
