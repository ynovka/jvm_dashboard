"""Run integration tests against a disposable MariaDB child process on Windows.

Never registers a system service. Only the named temporary test directory is used;
the child process is always terminated on test completion.
"""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

p = argparse.ArgumentParser()
p.add_argument("--mariadb-dir", required=True)
p.add_argument("--jdk-dir", required=True)
args = p.parse_args()
bin_dir = Path(args.mariadb_dir) / "bin"
data = Path(tempfile.gettempdir()) / "jvm-dashboard-build" / "integration-mariadb"
data.parent.mkdir(exist_ok=True)
if not (data / "mysql").exists():
    subprocess.run([str(bin_dir / "mariadb-install-db.exe"), "--datadir=" + str(data), "--password=integration-only", "--port=23306", "--silent"], check=True)
with open(data.parent / "db-test.log", "w") as log:
    process = subprocess.Popen([str(bin_dir / "mariadbd.exe"), "--datadir=" + str(data), "--port=23306", "--bind-address=127.0.0.1", "--console"], stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        for _ in range(30):
            try:
                with socket.create_connection(("127.0.0.1", 23306), timeout=1):
                    break
            except OSError:
                time.sleep(.5)
        subprocess.run([str(bin_dir / "mariadb.exe"), "-uroot", "-pintegration-only", "-P23306", "-h127.0.0.1", "-e", "DROP DATABASE IF EXISTS jvm_dashboard; CREATE DATABASE jvm_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"], check=True)
        env = {**os.environ, "JAVA_HOME": args.jdk_dir, "RUN_DB_TESTS": "true", "DATABASE_URL": "jdbc:mariadb://127.0.0.1:23306/jvm_dashboard?allowLocalInfile=false", "DATABASE_USER": "root", "DATABASE_PASSWORD": "integration-only", "ENCRYPTION_KEY": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AGENT_TOKEN": "integration-node-token", "PUBLIC_URL": "https://panel.example.test"}
        result = subprocess.run(["cmd", "/c", "gradlew.bat", "test", "--no-daemon", "--rerun-tasks"], env=env)
    finally:
        process.terminate()
        process.wait(timeout=10)
raise SystemExit(result.returncode)
