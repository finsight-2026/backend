#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  staging) domain=staging.finsight.life; directory=/opt/finsight/staging ;;
  production) domain=api.finsight.life; directory=/opt/finsight/production ;;
  *) exit 2 ;;
esac
read -r verb target image actor extra <<<"${SSH_ORIGINAL_COMMAND:-}"
if [[ "$verb" != deploy || "$target" != "$domain" || -n "$extra" \
      || ! "$image" =~ ^ghcr\.io/finsight-2026/backend@sha256:[a-f0-9]{64}$ \
      || ! "$actor" =~ ^[a-zA-Z0-9-]+(\[bot\])?$ ]]; then
  echo 'Invalid deployment command' >&2
  exit 2
fi

umask 077
export DOCKER_CONFIG="$(mktemp -d)"
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
docker login ghcr.io -u "$actor" --password-stdin
/usr/local/libexec/finsight-deploy "$directory" "$domain" "$image"
