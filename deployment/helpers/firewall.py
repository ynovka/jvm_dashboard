#!/usr/bin/env python3
"""Serialized UFW + Docker policy, with a systemd rollback independent of the API."""
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path
if sys.platform == "linux":
    import fcntl

BASE = Path(os.environ.get("FIREWALL_STATE", "/opt/jvm_dashboard/data/firewall"))
STATE = BASE / "state.json"
CHAIN = "JVM-PANEL"


def run(*args, check=True):
    p = subprocess.run(args, capture_output=True, text=True, timeout=20)
    if check and p.returncode:
        raise ValueError(f"{args[0]} failed: {p.stderr[:500]}")
    return p.stdout.strip()


def save(state):
    tmp = STATE.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(state, f)
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, STATE)


def ipt(*args, check=True):
    return run("iptables", "--wait", "5", *args, check=check)


def reconcile(state):
    # Restrict only managed IPv4 Docker networks. Containers are not assigned IPv6.
    # IPv6 host ingress is controlled by UFW. Fail if Docker uses a different backend.
    ipt("-S", "DOCKER-USER")
    ipt("-N", CHAIN, check=False)
    ipt("-N", "JVM-HOST", check=False)
    if subprocess.run(["iptables", "--wait", "5", "-C", "INPUT", "-j", "JVM-HOST"], capture_output=True).returncode:
        ipt("-I", "INPUT", "1", "-j", "JVM-HOST")
    ipt("-F", "JVM-HOST")
    for app in state.get("apps", {}).values():
        ipt("-A", "JVM-HOST", "-s", str(ipaddress.ip_network(app["subnet"])), "-m", "conntrack", "--ctstate", "NEW", "-j", "DROP")
    ipt("-A", "JVM-HOST", "-j", "RETURN")
    if subprocess.run(["iptables", "--wait", "5", "-C", "DOCKER-USER", "-j", CHAIN], capture_output=True).returncode:
        ipt("-I", "DOCKER-USER", "1", "-j", CHAIN)
    # Block new managed ingress/egress before rebuilding to avoid a transient allow.
    guards = []
    for app in state.get("apps", {}).values():
        subnet = str(ipaddress.ip_network(app["subnet"]))
        for direction in ("-s", "-d"):
            guard = [direction, subnet, "-m", "conntrack", "--ctstate", "NEW", "-j", "DROP"]
            ipt("-I", "DOCKER-USER", "1", *guard)
            guards.append(guard)
    try:
        ipt("-F", CHAIN)
        ipt("-A", CHAIN, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "RETURN")
        for app in state.get("apps", {}).values():
            subnet = str(ipaddress.ip_network(app["subnet"]))
            for private in ("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4"):
                ipt("-A", CHAIN, "-s", subnet, "-d", private, "-j", "DROP")
            for port in app["ports"]:
                if port.get("public"):
                    ipt("-A", CHAIN, "-d", subnet, "-p", port["protocol"], "-m", "conntrack", "--ctorigdstport", str(port["host"]), "-j", "RETURN")
            ipt("-A", CHAIN, "-d", subnet, "-m", "conntrack", "--ctstate", "NEW", "-j", "DROP")
        ipt("-A", CHAIN, "-j", "RETURN")
    finally:
        for guard in guards:
            ipt("-D", "DOCKER-USER", *guard)


def rollback(state, change):
    pending = state.get("pending")
    if not pending or pending["id"] != change:
        return
    backup = BASE / ("rollback-" + change)
    for name in ("user.rules", "user6.rules", "ufw.conf"):
        shutil.copy2(backup / name, Path("/etc/ufw") / name)
    run("ufw", "--force", "enable" if pending["enabled"] else "disable")
    state["rules"] = pending["rules"]
    state["pending"] = None
    state["generation"] += 1
    save(state)
    shutil.rmtree(backup)


def schedule(state):
    if state.get("pending"):
        raise ValueError("Подтвердите или откатите предыдущее изменение")
    change = uuid.uuid4().hex
    backup = BASE / ("rollback-" + change)
    backup.mkdir(mode=0o700)
    for name in ("user.rules", "user6.rules", "ufw.conf"):
        shutil.copy2(Path("/etc/ufw") / name, backup / name)
    state["pending"] = {"id": change, "expires": int((time.time() + 90) * 1000), "enabled": "Status: active" in run("ufw", "status"), "rules": list(state["rules"])}
    save(state)
    run("systemd-run", "--quiet", "--unit=jvm-firewall-" + change, "--on-active=90s", "/usr/bin/python3", str(Path(__file__).resolve()), "--rollback", change)
    return change


def handle(b, state):
    action = b.get("action")
    if action in ("status", "reconcile"):
        if action == "reconcile":
            # Persisted subnets remain authoritative across API/Docker restarts.
            reconcile(state)
        return {"rules": state["rules"], "raw": run("ufw", "status", "numbered"), "generation": state["generation"], "pending": state.get("pending"), "enabled": "Status: active" in run("ufw", "status"), "ipv6Applications": False}
    if action in ("app", "removeApp"):
        aid = b["id"]
        if not re.fullmatch(r"[a-f0-9-]{36}", aid):
            raise ValueError("Invalid application id")
        if action == "app":
            subnet = ipaddress.ip_network(b["subnet"])
            if subnet.version != 4 or not subnet.is_private:
                raise ValueError("Managed subnet must be private IPv4")
            for p in b["ports"]:
                if p["protocol"] not in ("tcp", "udp") or not 10000 <= int(p["host"]) <= 60000:
                    raise ValueError("Invalid application port")
            state["apps"][aid] = {"subnet": str(subnet), "ports": b["ports"]}
        else:
            state["apps"].pop(aid, None)
        save(state)
        reconcile(state)
        return {}
    if action == "confirm":
        pending = state.get("pending")
        if not pending or pending["id"] != b.get("id") or pending["expires"] < time.time() * 1000:
            raise ValueError("Изменение уже откатилось или не найдено")
        run("systemctl", "stop", "jvm-firewall-" + pending["id"] + ".timer")
        shutil.rmtree(BASE / ("rollback-" + pending["id"]))
        state["pending"] = None
        save(state)
        return {"confirmed": True}
    if action == "rollback":
        rollback(state, b["id"])
        return {}
    if b.get("generation") != state["generation"]:
        raise ValueError("Firewall изменён. Обновите состояние")
    if action == "add":
        protocol = b.get("protocol")
        decision = b.get("decision")
        if protocol not in ("tcp", "udp") or decision not in ("allow", "deny"):
            raise ValueError("Недопустимый protocol/action")
        port = str(b.get("port", ""))
        if not re.fullmatch(r"\d{1,5}(:\d{1,5})?", port):
            raise ValueError("Порт или диапазон start:end")
        values = [int(p) for p in port.split(":")]
        if any(p < 1 or p > 65535 for p in values) or values[0] > values[-1]:
            raise ValueError("Недопустимый диапазон")
        source = b.get("source", "any")
        if source != "any":
            source = str(ipaddress.ip_network(source, strict=False))
        description = b.get("description", "")
        if not re.fullmatch(r"[\w .-]{0,60}", description):
            raise ValueError("Описание: до 60 букв, цифр, пробелов, точек и дефисов")
        rid = uuid.uuid4().hex[:12]
        args = [decision, "proto", protocol, "from", source, "to", "any", "port", port, "comment", "jvm:" + rid + " " + description]
        change = schedule(state)
        try:
            run("ufw", *args)
            state["rules"].append({"id": rid, "protocol": protocol, "decision": decision, "port": port, "source": source, "description": description, "args": args})
        except BaseException:
            rollback(state, change)
            raise
    elif action == "remove":
        rule = next((r for r in state["rules"] if r["id"] == b.get("id")), None)
        if not rule:
            raise ValueError("Правило не найдено")
        change = schedule(state)
        try:
            run("ufw", "--force", "delete", *rule["args"])
            state["rules"].remove(rule)
        except BaseException:
            rollback(state, change)
            raise
    elif action == "removeExternal":
        number = int(b["number"])
        raw = run("ufw", "status", "numbered")
        if not re.search(r"\[\s*" + str(number) + r"\]", raw):
            raise ValueError("Номер правила не найден")
        change = schedule(state)
        run("ufw", "--force", "delete", str(number))
    elif action == "toggle":
        if b.get("enabled"):
            # These protected rules exist before any enable, including after partial installation.
            for port in os.environ.get("SSH_PORTS", "22").split(","):
                if not port.isdigit() or not 1 <= int(port) <= 65535:
                    raise ValueError("Invalid protected SSH port")
                run("ufw", "allow", port + "/tcp", "comment", "jvm:protected:ssh")
            for port in ("80", "443"):
                run("ufw", "allow", port + "/tcp", "comment", "jvm:protected:caddy")
        change = schedule(state)
        run("ufw", "--force", "enable" if b.get("enabled") else "disable")
    else:
        raise ValueError("Неизвестное действие firewall")
    state["generation"] += 1
    save(state)
    return {"changeId": change, "expires": state["pending"]["expires"], "generation": state["generation"]}


def main():
    if sys.platform != "linux":
        raise SystemExit("Linux required")
    BASE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(BASE / "lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = json.loads(STATE.read_text()) if STATE.exists() else {"generation": 1, "rules": [], "apps": {}, "pending": None}
        if len(sys.argv) == 3 and sys.argv[1] == "--rollback":
            rollback(state, sys.argv[2])
            return
        if state.get("pending") and state["pending"]["expires"] < time.time() * 1000:
            rollback(state, state["pending"]["id"])
        try:
            b = {"action": "reconcile"} if sys.argv[1:] == ["--reconcile"] else json.loads(sys.stdin.buffer.read(65537))
            print(json.dumps({"ok": True, **handle(b, state)}, ensure_ascii=False))
        except (ValueError, KeyError, OSError, subprocess.SubprocessError) as e:
            print(json.dumps({"ok": False, "code": "FIREWALL_ERROR", "message": str(e)}, ensure_ascii=False))
            if sys.argv[1:] == ["--reconcile"]:
                sys.exit(1)


if __name__ == "__main__":
    main()
