#!/bin/sh
# Probe the mounted PostgreSQL filesystem. This process has no database credentials.
set -eu
data_path=${CUTOVER_DATA_PATH:-/var/lib/postgresql}
report_path=/run/cutover-volume
mkdir -p "$report_path"
trap 'rm -f "$report_path/observation.tmp"; exit 0' TERM INT
while :; do
  if values=$(LC_ALL=C df -Pk "$data_path" | tail -n 1); then
    set -- $values
    total=$2
    available=$4
    case "$total:$available" in *[!0-9:]*|:*) exit 1;; esac
    printf '{"protocol":1,"observedEpoch":%s,"totalBytes":%s,"availableBytes":%s}\n' \
      "$(date +%s)" "$((total * 1024))" "$((available * 1024))" > "$report_path/observation.tmp"
    mv -f "$report_path/observation.tmp" "$report_path/observation.json"
  fi
  sleep 2
done
