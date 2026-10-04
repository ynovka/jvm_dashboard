#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
ROOT=/opt/jvm_dashboard
VERSION= DOMAIN= REPO= SSH_PORT= STORAGE_SIZE=20G ARTIFACT_DIR= TLS_MODE=public
PHASE=arguments
die() { printf 'ERROR [%s]: %s\n' "$PHASE" "$*" >&2; exit 1; }
usage() { printf '%s\n' 'install.sh --repo OWNER/REPO --version TAG --domain panel.example.com [--storage-size 20G] [--ssh-port 22] [--artifact-dir /verified/local/artifacts] [--tls-mode internal]'; }
trap 'printf "Installation failed at %s (line %s). Data are preserved. Fix the cause and run the same command again.\n" "$PHASE" "$LINENO" >&2' ERR
while (($#)); do
  case "$1" in
    --repo) REPO=${2:?}; shift 2;; --version) VERSION=${2:?}; shift 2;; --domain) DOMAIN=${2:?}; shift 2;;
    --storage-size) STORAGE_SIZE=${2:?}; shift 2;; --ssh-port) SSH_PORT=${2:?}; shift 2;;
    --artifact-dir) ARTIFACT_DIR=${2:?}; shift 2;; --tls-mode) TLS_MODE=${2:?}; shift 2;;
    --help) usage; exit 0;; *) die "Unknown option $1";;
  esac
done
[[ $EUID == 0 ]] || die 'Run with sudo/root'
[[ -n $VERSION && $VERSION =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || die 'Provide --version TAG'
if [[ -z $DOMAIN && -r /dev/tty ]]; then read -r -p 'Panel domain: ' DOMAIN </dev/tty; fi
[[ $DOMAIN =~ ^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$ && ${#DOMAIN} -le 253 ]] || die 'Provide a valid --domain'
[[ $TLS_MODE == public || $TLS_MODE == internal ]] || die 'Invalid --tls-mode'
[[ $STORAGE_SIZE =~ ^[1-9][0-9]*(G|M)$ ]] || die 'Storage size must be an integer G or M'
[[ -n $ARTIFACT_DIR || $REPO =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die 'Provide --repo OWNER/REPO'
PHASE=preflight
[[ $(. /etc/os-release; printf '%s:%s' "$ID" "$VERSION_ID") == ubuntu:24.04 && $(uname -m) == x86_64 ]] || die 'Ubuntu 24.04 amd64 required'
[[ -d /run/systemd/system ]] || die 'systemd required'
exec 9>/run/jvm-dashboard-install.lock
flock -n 9 || die 'Another installation is running'
# Refuse symlinks in every path that will be privileged or mounted.
for p in "$ROOT" "$ROOT/config" "$ROOT/secrets" "$ROOT/storage" "$ROOT/storage/app-data.xfs" "$ROOT/data" "$ROOT/data/apps" "$ROOT/data/mariadb" "$ROOT/data/prometheus" "$ROOT/releases" "$ROOT/runtimes"; do [[ ! -L $p ]] || die "Unexpected symlink: $p"; done
OWN=false
if [[ -f $ROOT/config/installation.json ]]; then
  OWN=true
  [[ $(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["domain"])' "$ROOT/config/installation.json") == "$DOMAIN" ]] || die 'Existing installation uses another domain'
elif [[ -e $ROOT ]]; then
  [[ -z $(ls -A "$ROOT") ]] || die "$ROOT belongs to another installation"
fi
if [[ $OWN == false ]]; then
  for s in mariadb mysql prometheus caddy docker; do systemctl is-active --quiet "$s" && die "Existing $s service: use a fresh host"; done
  for p in 80 443 3000 3306 8080 8302 8444 9090 2019; do
    if ss -H -lnt "sport = :$p" | read -r _; then die "Port $p is occupied"; fi
  done
  [[ ! -e /var/lib/mysql/mysql ]] || die 'Existing MariaDB data must not be reinitialized'
  [[ ! -f /etc/docker/daemon.json ]] || die 'Existing Docker configuration requires manual migration'
fi
(( $(awk '/MemTotal/{print $2}' /proc/meminfo) >= 1900000 )) || die 'At least 2 GiB RAM required'
getent ahostsv4 "$DOMAIN" >/dev/null || die 'Domain has no IPv4 DNS record'
if [[ -z $SSH_PORT ]]; then
  mapfile -t SSH_PORTS_FOUND < <(/usr/sbin/sshd -T 2>/dev/null | awk '$1=="port"{print $2}' | sort -un)
  ((${#SSH_PORTS_FOUND[@]} > 0)) || die 'Cannot detect SSH: supply --ssh-port'
  SSH_PORT=$(IFS=,; printf '%s' "${SSH_PORTS_FOUND[*]}")
fi
[[ $SSH_PORT =~ ^[0-9]+(,[0-9]+)*$ ]] || die 'Invalid SSH port(s)'
IFS=, read -ra SSH_VALUES <<<"$SSH_PORT"
for p in "${SSH_VALUES[@]}"; do ((p >= 1 && p <= 65535)) || die 'Invalid SSH port'; done
mkdir -p "$ROOT"/{config,secrets,storage,releases,runtimes,data/{apps,mariadb,prometheus,caddy,agent,firewall}}
chmod 755 "$ROOT" "$ROOT/config" "$ROOT/data" "$ROOT/releases" "$ROOT/runtimes"
chmod 700 "$ROOT/secrets" "$ROOT/storage" "$ROOT/data/agent" "$ROOT/data/firewall"
printf '{"domain":"%s","format":1}\n' "$DOMAIN" >"$ROOT/config/installation.json"
PHASE=packages
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl gnupg jq openssl xfsprogs ufw bubblewrap python3 openjdk-21-jre-headless mariadb-server prometheus
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
printf 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable\n' >/etc/apt/sources.list.d/jvm-docker.list
curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/caddy.gpg
chmod a+r /etc/apt/keyrings/caddy.gpg
printf 'deb [signed-by=/etc/apt/keyrings/caddy.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main\n' >/etc/apt/sources.list.d/jvm-caddy.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io caddy
PHASE=artifacts
STAGING=$(mktemp -d "$ROOT/releases/.staging.XXXXXX")
cleanup() { [[ -n ${STAGING:-} && $STAGING == "$ROOT/releases/".staging.* ]] && rm -rf -- "$STAGING"; }
trap cleanup EXIT
download() {
  if [[ -n $ARTIFACT_DIR ]]; then cp -- "$ARTIFACT_DIR/$1" "$STAGING/$1";
  else curl --proto '=https' --tlsv1.2 -fSL --retry 3 "https://github.com/$REPO/releases/download/$VERSION/$1" -o "$STAGING/$1"; fi
}
for asset in install.sh release.json SHA256SUMS frontend-linux-amd64.tar.gz backend.tar.gz agent-linux-amd64.tar.gz deployment.tar.gz; do download "$asset"; done
(cd "$STAGING"; sha256sum --strict --check SHA256SUMS)
[[ $(jq -r .version "$STAGING/release.json") == "$VERSION" && $(jq -r .architecture "$STAGING/release.json") == amd64 && $(jq -r .protocol "$STAGING/release.json") == 1 ]] || die 'Release version/architecture/protocol mismatch'
NODE_VERSION=$(jq -r .node "$STAGING/release.json")
[[ $NODE_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'Invalid Node version'
if [[ ! -x $ROOT/runtimes/node-v$NODE_VERSION-linux-x64/bin/node ]]; then
  curl --proto '=https' -fSL "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.xz" -o "$STAGING/node.tar.xz"
  [[ $(sha256sum "$STAGING/node.tar.xz" | cut -d' ' -f1) == $(jq -r .nodeSha256 "$STAGING/release.json") ]] || die 'Node checksum mismatch'
  tar -xJf "$STAGING/node.tar.xz" -C "$ROOT/runtimes"
fi
ln -sfn "node-v$NODE_VERSION-linux-x64" "$ROOT/runtimes/node"
# Validate tar members before extracting root-owned release data.
python3 - "$STAGING" <<'PY'
import pathlib,sys,tarfile
for archive in pathlib.Path(sys.argv[1]).glob('*.tar.gz'):
    with tarfile.open(archive) as t:
        for m in t:
            p=pathlib.PurePosixPath(m.name)
            if p.is_absolute() or '..' in p.parts or m.isdev() or m.isfifo() or m.islnk(): raise SystemExit('Unsafe release archive')
            if m.issym():
                target=pathlib.PurePosixPath(m.linkname)
                if target.is_absolute() or '..' in target.parts: raise SystemExit('Unsafe release symlink')
PY
for pair in 'frontend-linux-amd64.tar.gz frontend' 'backend.tar.gz backend' 'agent-linux-amd64.tar.gz agent' 'deployment.tar.gz deployment'; do
  read -r archive dir <<<"$pair"; mkdir -p "$STAGING/release/$dir"; tar -xzf "$STAGING/$archive" -C "$STAGING/release/$dir" --no-same-owner
done
cp "$STAGING/release.json" "$STAGING/release/release.json"
if [[ -d $ROOT/releases/$VERSION ]]; then
  cmp "$ROOT/releases/$VERSION/release.json" "$STAGING/release.json" || die 'An existing tag has different artifacts'
else mv "$STAGING/release" "$ROOT/releases/$VERSION"; fi
chmod -R a+rX "$ROOT/releases/$VERSION"
PHASE=storage
if ! mountpoint -q "$ROOT/data/apps"; then
  if [[ ! -e $ROOT/storage/app-data.xfs ]]; then
    SIZE_BYTES=$(numfmt --from=iec "$STORAGE_SIZE")
    FREE_BYTES=$(df -B1 --output=avail "$ROOT" | tail -1)
    ((FREE_BYTES > SIZE_BYTES + 4*1024*1024*1024)) || die 'Insufficient disk: storage plus 4 GiB host reserve required'
    fallocate -l "$SIZE_BYTES" "$ROOT/storage/app-data.xfs"
    mkfs.xfs -f "$ROOT/storage/app-data.xfs"
  fi
  [[ $(blkid -p -s TYPE -o value "$ROOT/storage/app-data.xfs") == xfs ]] || die 'Existing storage file is not XFS; it will not be formatted'
  cat > /etc/systemd/system/opt-jvm_dashboard-data-apps.mount <<EOF
[Unit]
Description=JVM Dashboard quota storage
Before=jvm-dashboard-agent.service
[Mount]
What=$ROOT/storage/app-data.xfs
Where=$ROOT/data/apps
Type=xfs
Options=loop,prjquota,nosuid,nodev
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now opt-jvm_dashboard-data-apps.mount
fi
findmnt -n -o FSTYPE,OPTIONS --target "$ROOT/data/apps" | grep -Eq 'xfs.*(prjquota|pquota)' || die 'Working XFS project quotas required'
PHASE=identity
for user in jvm-api jvm-web; do id "$user" &>/dev/null || useradd --system --home-dir "$ROOT" --shell /usr/sbin/nologin "$user"; done
if [[ ! -f $ROOT/secrets/credentials.env ]]; then
  printf 'DATABASE_PASSWORD=%s\nENCRYPTION_KEY=%s\nAGENT_TOKEN=%s\nNODE_KEYSTORE_PASSWORD=%s\n' "$(openssl rand -hex 32)" "$(openssl rand -base64 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 24)" >"$ROOT/secrets/credentials.env"
fi
source "$ROOT/secrets/credentials.env"
if [[ ! -f $ROOT/secrets/node.p12 ]]; then
  openssl req -x509 -newkey rsa:3072 -nodes -keyout "$ROOT/secrets/ca.key" -out "$ROOT/secrets/ca.crt" -days 3650 -subj /CN=JVM-Dashboard-Node-CA
  for kind in server node; do
    openssl req -newkey rsa:3072 -nodes -keyout "$ROOT/secrets/$kind.key" -out "$STAGING/$kind.csr" -subj "/CN=$kind"
    if [[ $kind == server ]]; then printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' >"$STAGING/extensions"; else printf 'extendedKeyUsage=clientAuth\n' >"$STAGING/extensions"; fi
    openssl x509 -req -in "$STAGING/$kind.csr" -CA "$ROOT/secrets/ca.crt" -CAkey "$ROOT/secrets/ca.key" -CAcreateserial -out "$ROOT/secrets/$kind.crt" -days 365 -extfile "$STAGING/extensions"
  done
  openssl pkcs12 -export -in "$ROOT/secrets/node.crt" -inkey "$ROOT/secrets/node.key" -certfile "$ROOT/secrets/ca.crt" -out "$ROOT/secrets/node.p12" -passout "pass:$NODE_KEYSTORE_PASSWORD"
  keytool -importcert -noprompt -alias panel-ca -file "$ROOT/secrets/ca.crt" -keystore "$ROOT/secrets/trust.p12" -storetype PKCS12 -storepass "$NODE_KEYSTORE_PASSWORD"
fi
mkdir -p "$ROOT/config/tls"
cp "$ROOT/secrets/"{ca.crt,server.crt,server.key} "$ROOT/config/tls/"
chown -R root:caddy "$ROOT/config/tls"; chmod 750 "$ROOT/config/tls"; chmod 640 "$ROOT/config/tls/"*
JDK_IMAGES=$(jq -c .images "$ROOT/releases/$VERSION/release.json")
NODE_CPU=$(awk -v n="$(nproc)" 'BEGIN{print (n>1?n-1:0.5)}')
NODE_MEMORY_MIB=$(( $(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo) - 1536 ))
NODE_DISK_MIB=$(( $(df -B1 --output=size "$ROOT/data/apps" | tail -1) / 1024 / 1024 * 9 / 10 ))
cat >"$ROOT/config/backend.env" <<EOF
PUBLIC_URL=https://$DOMAIN
DATABASE_URL=jdbc:mariadb://127.0.0.1:3306/jvm_dashboard?allowLocalInfile=false
DATABASE_USER=jvm_dashboard
DATABASE_PASSWORD=$DATABASE_PASSWORD
ENCRYPTION_KEY=$ENCRYPTION_KEY
AGENT_TOKEN=$AGENT_TOKEN
NODE_CPU=$NODE_CPU
NODE_MEMORY_MIB=$NODE_MEMORY_MIB
NODE_DISK_MIB=$NODE_DISK_MIB
JDK_IMAGES_JSON=$JDK_IMAGES
EOF
chown jvm-api:jvm-api "$ROOT/config/backend.env"; chmod 600 "$ROOT/config/backend.env"
cat >"$ROOT/config/agent.env" <<EOF
AGENT_API_URL=https://localhost:8444
AGENT_TOKEN=$AGENT_TOKEN
ENCRYPTION_KEY=$ENCRYPTION_KEY
NODE_KEYSTORE=$ROOT/secrets/node.p12
NODE_TRUSTSTORE=$ROOT/secrets/trust.p12
NODE_KEYSTORE_PASSWORD=$NODE_KEYSTORE_PASSWORD
SSH_PORTS=$SSH_PORT
EOF
PHASE=mariadb
systemctl stop mariadb
cat >/etc/mysql/mariadb.conf.d/90-jvm-dashboard.cnf <<EOF
[mysqld]
bind-address=127.0.0.1
datadir=$ROOT/data/mariadb
local_infile=0
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
innodb_buffer_pool_size=64M
max_connections=32
EOF
chown -R mysql:mysql "$ROOT/data/mariadb"
mkdir -p /etc/systemd/system/mariadb.service.d
printf '[Service]\nReadWritePaths=%s/data/mariadb\nMemoryMax=256M\n' "$ROOT" >/etc/systemd/system/mariadb.service.d/jvm-dashboard.conf
for profile in usr.sbin.mysqld usr.sbin.mariadbd; do
  if [[ -f /etc/apparmor.d/$profile ]]; then mkdir -p /etc/apparmor.d/local; printf '%s/data/mariadb/ r,\n%s/data/mariadb/** rwk,\n' "$ROOT" "$ROOT" >/etc/apparmor.d/local/$profile; apparmor_parser -r "/etc/apparmor.d/$profile"; fi
done
if [[ ! -d $ROOT/data/mariadb/mysql ]]; then mariadb-install-db --user=mysql --datadir="$ROOT/data/mariadb"; fi
systemctl daemon-reload
systemctl enable --now mariadb
mariadb <<SQL
CREATE DATABASE IF NOT EXISTS jvm_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'jvm_dashboard'@'127.0.0.1' IDENTIFIED BY '$DATABASE_PASSWORD';
GRANT ALL PRIVILEGES ON jvm_dashboard.* TO 'jvm_dashboard'@'127.0.0.1';
SQL
PHASE=services
[[ -f $ROOT/current/release.json ]] && ln -sfn "$(readlink "$ROOT/current")" "$ROOT/previous"
ln -sfn "releases/$VERSION" "$ROOT/current"
mkdir -p "$ROOT/current/frontend/.next/cache"
chown jvm-web:jvm-web "$ROOT/current/frontend/.next/cache"
cp "$ROOT/current/deployment/systemd/"*.service /etc/systemd/system/
chown -R caddy:caddy "$ROOT/data/caddy"
TLS_EXTRA=; [[ $TLS_MODE == internal ]] && TLS_EXTRA='tls internal'
cat >"$ROOT/config/Caddyfile" <<EOF
{
  admin 127.0.0.1:2019
  storage file_system $ROOT/data/caddy
  servers {
    protocols h1 h2
  }
}
$DOMAIN {
  $TLS_EXTRA
  header Referrer-Policy no-referrer
  @api path /api/v1/* /ws/*
  handle @api {
    request_body {
      max_size 2MB
    }
    reverse_proxy 127.0.0.1:8080
  }
  handle {
    reverse_proxy 127.0.0.1:3000
  }
}
https://localhost:8444 {
  bind 127.0.0.1
  tls $ROOT/config/tls/server.crt $ROOT/config/tls/server.key {
    client_auth {
      mode require_and_verify
      trust_pool file {
        pem_file $ROOT/config/tls/ca.crt
      }
    }
  }
  @internal path /internal/v1/*
  handle @internal {
    reverse_proxy 127.0.0.1:8080
  }
  respond 404
}
EOF
chmod 644 "$ROOT/config/Caddyfile"
caddy validate --config "$ROOT/config/Caddyfile" --adapter caddyfile
mkdir -p /etc/systemd/system/caddy.service.d
printf '[Service]\nExecStart=\nExecStart=/usr/bin/caddy run --environ --config %s/config/Caddyfile\nExecReload=\nExecReload=/usr/bin/caddy reload --config %s/config/Caddyfile\nReadWritePaths=%s/data/caddy\nMemoryMax=96M\n' "$ROOT" "$ROOT" "$ROOT" >/etc/systemd/system/caddy.service.d/jvm-dashboard.conf
cat >"$ROOT/config/prometheus.yml" <<EOF
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: panel
    static_configs:
      - targets: ['127.0.0.1:8080', '127.0.0.1:8302']
EOF
chmod 644 "$ROOT/config/prometheus.yml"; chown -R prometheus:prometheus "$ROOT/data/prometheus"
mkdir -p /etc/systemd/system/prometheus.service.d
printf '[Service]\nExecStart=\nExecStart=/usr/bin/prometheus --config.file=%s/config/prometheus.yml --web.listen-address=127.0.0.1:9090 --storage.tsdb.path=%s/data/prometheus --storage.tsdb.retention.time=30d --storage.tsdb.retention.size=1GB\nReadWritePaths=%s/data/prometheus\nMemoryMax=128M\n' "$ROOT" "$ROOT" "$ROOT" >/etc/systemd/system/prometheus.service.d/jvm-dashboard.conf
PHASE=firewall
[[ -f $ROOT/config/ufw-before.tar ]] || tar -cf "$ROOT/config/ufw-before.tar" -C /etc ufw
ufw default deny incoming
ufw default allow outgoing
for p in "${SSH_VALUES[@]}"; do ufw allow "$p/tcp" comment 'jvm:protected:ssh'; done
ufw allow 80/tcp comment 'jvm:protected:caddy'
ufw allow 443/tcp comment 'jvm:protected:caddy'
ufw --force enable
if [[ ! -f /etc/docker/daemon.json ]]; then printf '{"iptables":true,"ip6tables":true,"userland-proxy":false,"ipv6":false}\n' >/etc/docker/daemon.json; systemctl restart docker; fi
mkdir -p /etc/systemd/system/docker.service.d
printf '[Service]\nExecStartPost=/usr/bin/python3 %s/current/deployment/helpers/firewall.py --reconcile\n' "$ROOT" >/etc/systemd/system/docker.service.d/jvm-dashboard.conf
python3 "$ROOT/current/deployment/helpers/firewall.py" --reconcile
PHASE=start
systemctl daemon-reload
systemctl enable --now docker prometheus caddy jvm-dashboard-api jvm-dashboard-web jvm-dashboard-agent
systemctl restart prometheus caddy jvm-dashboard-api jvm-dashboard-web jvm-dashboard-agent
for i in {1..60}; do curl -fsS http://127.0.0.1:8080/ready >/dev/null && curl -fsS http://127.0.0.1:8302/ready >/dev/null && curl -fsS http://127.0.0.1:3000/login >/dev/null && break; sleep 2; done
curl -fsS http://127.0.0.1:8080/ready >/dev/null
curl -fsS http://127.0.0.1:8302/ready >/dev/null
CURL_TLS=(); [[ $TLS_MODE == internal ]] && CURL_TLS=(--cacert "$ROOT/data/caddy/pki/authorities/local/root.crt")
for i in {1..60}; do
  if curl -fsS "${CURL_TLS[@]}" --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/login" -o "$STAGING/page.html" 2>/dev/null; then break; fi
  sleep 2
done
curl -fsS "${CURL_TLS[@]}" --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/login" -o "$STAGING/page.html"
ASSET=$(grep -oE '/_next/static/[^" ]+\.css' "$STAGING/page.html" | head -1)
[[ -n $ASSET ]] || die 'Standalone CSS assets missing'
curl -fsS "${CURL_TLS[@]}" --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN$ASSET" >/dev/null
printf '\nJVM Dashboard %s is ready: https://%s\n' "$VERSION" "$DOMAIN"
journalctl -u jvm-dashboard-api --since '-3 minutes' -o cat --no-pager | grep 'BOOTSTRAP_INVITATION=' | tail -1 || true
printf 'Diagnostics: systemctl status jvm-dashboard-{api,agent,web}; journalctl -u jvm-dashboard-agent -f\n'
printf 'External cloud firewall must permit TCP 80/443 and your SSH ports.\n'
