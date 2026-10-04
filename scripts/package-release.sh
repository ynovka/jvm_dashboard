#!/usr/bin/env bash
set -Eeuo pipefail
TAG=${1:?release tag}
[[ $TAG =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || exit 1
OUT=${2:-artifacts}
mkdir -p "$OUT" .next/standalone/.next
cp -R public .next/standalone/
cp -R .next/static .next/standalone/.next/
tar -czf "$OUT/frontend-linux-amd64.tar.gz" -C .next/standalone .
tar -czf "$OUT/backend.tar.gz" -C services/backend/build/install/backend .
tar -czf "$OUT/agent-linux-amd64.tar.gz" -C services/agent/build/install/agent .
tar -czf "$OUT/deployment.tar.gz" -C deployment .
cp deployment/install.sh "$OUT/install.sh"
NODE_VERSION=24.16.0
NODE_HASH=$(curl -fsSL "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt" | awk '$2=="node-v24.16.0-linux-x64.tar.xz"{print $1}')
[[ $NODE_HASH =~ ^[a-f0-9]{64}$ ]]
IMAGES='{}'
for jdk in 8 11 17 21 25; do
  DIGEST=$(docker buildx imagetools inspect "eclipse-temurin:$jdk-jre-jammy" --format '{{json .Manifest}}' | jq -r .digest)
  [[ $DIGEST =~ ^sha256:[a-f0-9]{64}$ ]]
  IMAGES=$(jq --arg jdk "$jdk" --arg image "eclipse-temurin@$DIGEST" '. + {($jdk):$image}' <<<"$IMAGES")
done
jq -n --arg tag "$TAG" --arg node "$NODE_VERSION" --arg hash "$NODE_HASH" --argjson images "$IMAGES" '{version:$tag,architecture:"amd64",protocol:1,jdk:21,node:$node,nodeSha256:$hash,images:$images}' >"$OUT/release.json"
(cd "$OUT"; sha256sum install.sh release.json *.tar.gz > SHA256SUMS)
