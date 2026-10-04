#!/usr/bin/env python3
"""Real cgroups/XFS/bubblewrap checks without creating a panel account."""
import json
import os
import pathlib
import random
import shutil
import subprocess
import sys
import time
import uuid

STORAGE=pathlib.Path("/opt/jvm_dashboard/data/apps")
HELPERS="/opt/jvm_dashboard/current/deployment/helpers"

def run(*args, timeout=180, check=True):
    return subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout,check=check).stdout

def main():
    manifest=json.loads(pathlib.Path(sys.argv[1]).read_text())
    jar=pathlib.Path(sys.argv[2])
    project=random.randint(500000,999999);uid=2000000+project
    volume=STORAGE/str(uuid.uuid4());volume.mkdir(mode=0o700)
    name="jvm-foundation-"+uuid.uuid4().hex[:12]
    try:
        run("xfs_quota","-x","-c",f"project -s -p {volume} {project}",str(STORAGE))
        empty_quota=run("xfs_quota","-x","-c",f"quota -p -b -N -v {project}",str(STORAGE)).split()
        assert empty_quota[0].startswith("/dev/") and int(empty_quota[1])==0,empty_quota
        print("PASS: empty XFS project has a readable device usage row",flush=True)
        run("xfs_quota","-x","-c",f"limit -p bhard=128m bsoft=128m ihard=100 isoft=100 {project}",str(STORAGE))
        os.chown(volume,uid,uid)
        for sub in (".panel-upload",".panel-trash",".panel-tmp"):
            (volume/sub).mkdir(mode=0o700);os.chown(volume/sub,uid,uid)
        shutil.copyfile(jar,volume/"app.jar");os.chown(volume/"app.jar",uid,uid)
        bwrap=["bwrap","--unshare-pid","--unshare-net","--unshare-uts","--unshare-ipc","--unshare-cgroup","--die-with-parent","--new-session","--cap-drop","ALL","--cap-add","CAP_SETUID","--cap-add","CAP_SETGID","--cap-add","CAP_SETPCAP","--ro-bind","/usr","/usr","--ro-bind","/lib","/lib","--ro-bind","/lib64","/lib64","--proc","/proc","--dev","/dev","--bind",str(volume),"/data","--ro-bind",HELPERS+"/files.py","/worker.py","--chdir","/","setpriv","--reuid",str(uid),"--regid",str(uid),"--clear-groups","--bounding-set=-all","--inh-caps=-all","--ambient-caps=-all","--no-new-privs","python3","/worker.py"]
        probe=subprocess.run(bwrap,input=json.dumps({"action":"list","path":""}),text=True,capture_output=True)
        assert probe.returncode==0,probe.stderr
        assert json.loads(probe.stdout)["ok"],probe.stdout
        identity=subprocess.run(bwrap[:-1]+["-c","import os;print(os.getuid());print([line for line in open('/proc/self/status') if line.startswith('CapEff:')][0])"],text=True,capture_output=True,check=True).stdout
        assert identity.startswith(str(uid)+"\n") and "0000000000000000" in identity,identity
        print("PASS: per-app UID and bubblewrap file worker",flush=True)
        for jdk in (8,21):
            image=manifest["images"][str(jdk)];run("docker","pull",image)
            args=["docker","run","-d","--name",name,"--network","none","--user",f"{uid}:{uid}","--cpus",".25","--memory","256m","--memory-swap","256m","--pids-limit","64","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--workdir","/data","--mount",f"type=bind,src={volume},dst=/data",image,"java","-Xms16m","-Xmx179m","-jar","/data/app.jar"]
            run(*args);time.sleep(2)
            assert "JVM_SMOKE_READY" in run("docker","logs",name)
            run("docker","rm","-f",name)
            print(f"PASS: JDK {jdk} starts as non-root with read-only rootfs",flush=True)
        image=manifest["images"]["21"]
        args[-1:] = ["/data/app.jar","cpu"]
        run(*args);time.sleep(1)
        state=json.loads(run("docker","inspect",name))[0]["State"]
        group=pathlib.Path("/sys/fs/cgroup")/pathlib.Path(f"/proc/{state['Pid']}/cgroup").read_text().strip().split("::")[1].lstrip("/")
        def usage():return int(dict(line.split() for line in (group/"cpu.stat").read_text().splitlines())["usage_usec"])
        before=usage();start=time.monotonic();time.sleep(5);cores=(usage()-before)/1e6/(time.monotonic()-start)
        assert .1<cores<.32,cores
        run("docker","rm","-f",name)
        print(f"PASS: CPU hard limit .25 vCPU, measured {cores:.3f}",flush=True)
        args[-1]="quota";run(*args)
        run("docker","wait",name,timeout=60)
        logs=run("docker","logs",name)
        assert "Disk quota exceeded" in logs or "No space left on device" in logs,logs
        assert (volume/"quota.bin").stat().st_size<=128*1024**2
        run("docker","rm","-f",name);(volume/"quota.bin").unlink()
        print("PASS: container write hits XFS block quota",flush=True)
        args[-1]="memory";args[args.index("-Xmx179m")]="-Xmx512m"
        run(*args);run("docker","wait",name,timeout=60)
        assert json.loads(run("docker","inspect",name))[0]["State"]["OOMKilled"]
        run("docker","rm","-f",name)
        print("PASS: cgroup RAM limit kills memory-bound JVM",flush=True)
        # A file helper cannot escape to another application or host path.
        (volume/"escape").symlink_to("/etc/passwd")
        probe=subprocess.run(bwrap,input=json.dumps({"action":"read","path":"escape"}),text=True,capture_output=True)
        assert json.loads(probe.stdout)["code"]=="UNSAFE_PATH",probe.stdout
        print("PASS: symlink escape rejected inside sandbox",flush=True)
    finally:
        run("docker","rm","-f",name,check=False)
        # Only this newly-created UUID directory is removed.
        assert volume.parent==STORAGE and volume.name.count("-")==4
        shutil.rmtree(volume)
        run("xfs_quota","-x","-c",f"limit -p bhard=0 bsoft=0 ihard=0 isoft=0 {project}",str(STORAGE))

if __name__=="__main__":main()
