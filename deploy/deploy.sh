#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 ]]; then
  echo 'Usage: deploy.sh directory domain image' >&2
  exit 2
fi
directory=$1
domain=$2
image=$3

case "$domain" in
  api.finsight.life) environment=production; port=18080; memory=640m ;;
  staging.finsight.life) environment=staging; port=18081; memory=448m ;;
  *) echo 'Invalid deployment domain' >&2; exit 2 ;;
esac
if [[ ! "$image" =~ ^ghcr\.io/finsight-2026/backend@sha256:[a-f0-9]{64}$ ]]; then
  echo 'Expected an immutable FINSIGHT image digest' >&2
  exit 2
fi

cd "$directory"
test -r runtime.env && test -r compose.yaml
umask 077
exec 9>.release/deploy.lock
flock -w 120 9

export FINSIGHT_LOCAL_PORT=$port
export FINSIGHT_BACKEND_IMAGE=$image
export FINSIGHT_BACKEND_MEMORY=$memory
compose=(docker compose --project-name "finsight-$environment" -f compose.yaml)
previous=''
if [[ -f .release/current-image ]]; then
  read -r previous <.release/current-image
  if [[ ! "$previous" =~ ^ghcr\.io/finsight-2026/backend@sha256:[a-f0-9]{64}$ \
        && ! "$previous" =~ ^finsight-backend:bootstrap-[a-f0-9]{40}$ ]]; then
    echo 'The recorded release digest is invalid' >&2
    exit 1
  fi
fi

"${compose[@]}" pull backend
check_https() {
  local endpoint=$1
  curl --fail --silent --show-error --max-time 8 \
    --retry 3 --retry-connrefused --retry-all-errors --retry-delay 2 \
    "https://$domain$endpoint" >/dev/null
}

if "${compose[@]}" up -d --wait --wait-timeout 120 backend \
    && check_https /actuator/health \
    && check_https /api/v1/surveys/current; then
  printf '%s\n' "$image" >.release/current-image.new
  mv -f .release/current-image.new .release/current-image
  exit 0
fi

echo 'Release health check failed' >&2
if [[ -n "$previous" ]]; then
  export FINSIGHT_BACKEND_IMAGE=$previous
  "${compose[@]}" up -d --wait --wait-timeout 120 backend
  echo 'Previous image restored' >&2
else
  "${compose[@]}" stop backend
fi
exit 1
