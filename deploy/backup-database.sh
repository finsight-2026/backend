#!/usr/bin/env bash
set -euo pipefail
cd /opt/finsight/database
umask 077
mkdir -p backups
target="backups/finsight-$(date -u +%Y%m%dT%H%M%SZ).dump"
temporary="$(mktemp backups/.dump.XXXXXX)"
trap 'rm -f "$temporary"' EXIT
compose=(docker compose -f compose.database.yaml)
"${compose[@]}" exec -T postgres pg_dump -U postgres -Fc -d finsight </dev/null >"$temporary"
"${compose[@]}" exec -T postgres pg_restore --list <"$temporary" >/dev/null
mv "$temporary" "$target"
find backups -maxdepth 1 -name 'finsight-*.dump' -type f -mtime +2 -delete
