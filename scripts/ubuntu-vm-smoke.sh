#!/usr/bin/env bash
set -Eeuo pipefail
ARTIFACTS=$(realpath "${1:?artifacts}")
TAG=${2:?tag}
mkdir -p vm-results
sudo apt-get update
sudo apt-get install -y qemu-system-x86 cloud-image-utils
WORK=$(mktemp -d)
VM_PID=
cleanup() {
  status=$?
  if ((status!=0)) && [[ -n $VM_PID ]] && [[ -f $WORK/key ]]; then
    "${SSH[@]}" 'sudo journalctl -u jvm-dashboard-api -u jvm-dashboard-agent -u caddy --no-pager | sed "s/BOOTSTRAP_INVITATION=.*/BOOTSTRAP_INVITATION=[REDACTED]/"' >vm-results/failure-services.log 2>&1 || true
  fi
  [[ -z $VM_PID ]] || kill "$VM_PID" 2>/dev/null || true
  [[ $WORK == /tmp/tmp.* ]] && rm -rf -- "$WORK"
}
trap cleanup EXIT
curl -fsSL https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img -o "$WORK/base.img"
curl -fsSL https://cloud-images.ubuntu.com/noble/current/SHA256SUMS -o "$WORK/SHA256SUMS"
(cd "$WORK"; expected=$(awk '$2=="*noble-server-cloudimg-amd64.img" || $2=="noble-server-cloudimg-amd64.img" {print $1}' SHA256SUMS); [[ $(sha256sum base.img | cut -d' ' -f1) == "$expected" ]])
qemu-img create -f qcow2 -F qcow2 -b "$WORK/base.img" "$WORK/disk.qcow2" 45G
ssh-keygen -q -t ed25519 -N '' -f "$WORK/key"
cat >"$WORK/user-data" <<EOF
#cloud-config
users:
  - name: tester
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys:
      - $(cat "$WORK/key.pub")
runcmd:
  - echo '127.0.0.1 panel.jvm.test' >> /etc/hosts
EOF
printf 'instance-id: jvm-smoke\nlocal-hostname: jvm-smoke\n' >"$WORK/meta-data"
cloud-localds "$WORK/seed.img" "$WORK/user-data" "$WORK/meta-data"
ACCEL=tcg
if [[ -e /dev/kvm ]]; then sudo chmod a+rw /dev/kvm; ACCEL=kvm; fi
qemu-system-x86_64 -accel "$ACCEL" -m 4096 -smp 2 -nographic -drive "file=$WORK/disk.qcow2,format=qcow2" -drive "file=$WORK/seed.img,format=raw" -netdev user,id=n1,hostfwd=tcp:127.0.0.1:2222-:22,hostfwd=tcp:127.0.0.1:8443-:443 -device virtio-net-pci,netdev=n1 >vm-results/console.log 2>&1 &
VM_PID=$!
SSH=(ssh -i "$WORK/key" -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 tester@127.0.0.1)
SCP=(scp -i "$WORK/key" -P 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
for i in {1..90}; do
  kill -0 "$VM_PID" 2>/dev/null || { cat vm-results/console.log; exit 1; }
  "${SSH[@]}" true 2>/dev/null && break
  sleep 3
done
"${SSH[@]}" 'cloud-init status --wait'
"${SCP[@]}" -r "$ARTIFACTS" tests/linux/smoke.py tests/linux/foundation.py tester@127.0.0.1:/home/tester/
"${SSH[@]}" "sudo bash /home/tester/$(basename "$ARTIFACTS")/install.sh --artifact-dir /home/tester/$(basename "$ARTIFACTS") --version '$TAG' --domain panel.jvm.test --storage-size 1G --ssh-port 22 --tls-mode internal" | tee vm-results/install.log
"${SSH[@]}" "sudo python3 /home/tester/foundation.py /home/tester/$(basename "$ARTIFACTS")/release.json /home/tester/$(basename "$ARTIFACTS")/smoke.jar" | tee vm-results/foundation.log
"${SSH[@]}" "sudo python3 /home/tester/smoke.py /home/tester/$(basename "$ARTIFACTS")/smoke.jar" | tee vm-results/api.log
# External HTTPS validates actual routing and static assets outside the VM.
"${SSH[@]}" 'sudo cat /opt/jvm_dashboard/data/caddy/pki/authorities/local/root.crt' >"$WORK/root.crt"
curl --cacert "$WORK/root.crt" --resolve panel.jvm.test:8443:127.0.0.1 -fsS https://panel.jvm.test:8443/login >/dev/null
"${SSH[@]}" "sudo bash /home/tester/$(basename "$ARTIFACTS")/install.sh --artifact-dir /home/tester/$(basename "$ARTIFACTS") --version '$TAG' --domain panel.jvm.test --storage-size 1G --ssh-port 22 --tls-mode internal" | tee vm-results/rerun.log
"${SSH[@]}" 'sudo reboot' || true
sleep 10
for i in {1..60}; do "${SSH[@]}" 'curl -fsS http://127.0.0.1:8080/ready' 2>/dev/null && break; sleep 3; done
"${SSH[@]}" 'sudo python3 /home/tester/smoke.py --reboot' | tee vm-results/reboot.log
"${SSH[@]}" 'sudo journalctl -u jvm-dashboard-api -u jvm-dashboard-agent -u caddy --no-pager' >vm-results/services.log
