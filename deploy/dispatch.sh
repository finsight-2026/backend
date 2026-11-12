#!/usr/bin/env bash
set -euo pipefail

case "${DEPLOY_DOMAIN:-}" in
  api.finsight.life|staging.finsight.life) ;;
  *) echo 'Invalid deployment domain' >&2; exit 2 ;;
esac
if [[ ! "${DEPLOY_HOST:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ \
      || ! "${DEPLOY_USER:-}" =~ ^[a-z_][a-z0-9_-]*$ \
      || ! "${DEPLOY_IMAGE:-}" =~ ^ghcr\.io/finsight-2026/backend@sha256:[a-f0-9]{64}$ \
      || ! "${GITHUB_ACTOR:-}" =~ ^[a-zA-Z0-9-]+(\[bot\])?$ \
      || -z "${GHCR_TOKEN:-}" || -z "${DEPLOY_SSH_KEY:-}" || -z "${DEPLOY_KNOWN_HOSTS:-}" ]]; then
  echo 'Deployment environment variables, digest or SSH credentials are missing or invalid' >&2
  exit 2
fi

umask 077
credentials="$(mktemp -d)"
trap 'rm -f "$credentials/key" "$credentials/known_hosts"; rmdir "$credentials"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" >"$credentials/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" >"$credentials/known_hosts"
printf '%s\n' "$GHCR_TOKEN" | ssh -i "$credentials/key" -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=15 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$credentials/known_hosts" \
  "$DEPLOY_USER@$DEPLOY_HOST" \
  "deploy $DEPLOY_DOMAIN $DEPLOY_IMAGE $GITHUB_ACTOR"
