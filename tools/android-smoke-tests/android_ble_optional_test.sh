#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  printf 'optional\tBLE Service\tWARN\t0\tlogcat missing; cannot evaluate BLE service\n'
  exit 0
fi

lower="$(mktemp)"
tr '[:upper:]' '[:lower:]' < "$LOG_PATH" > "$lower"
trap 'rm -f "$lower"' EXIT

if grep -Eq "fatal exception|\\banr\\b" "$lower"; then
  printf 'optional\tBLE Service\tFAIL\t0\tFATAL/ANR found in logcat\n'
  exit 0
fi

recovery_success=false
if grep -Eq "recovery.*success|advertising restored|ble recovery.*success" "$lower"; then
  recovery_success=true
fi

if grep -Eq "gatt.*failed|advertising.*failed|advertise.*failed" "$lower" && [[ "$recovery_success" != true ]]; then
  printf 'optional\tBLE Service\tFAIL\t0\tGATT/advertising failure without recovery success\n'
  exit 0
fi

gatt_started=false
service_added=false
advertising_started=false
if grep -Eq "gatt server started|ble-gatt.*started|gatt.*started" "$lower"; then
  gatt_started=true
fi
if grep -Eq "service added success|service.*added.*success|gatt service.*success" "$lower"; then
  service_added=true
fi
if grep -Eq "advertising started|ble-adv.*started|advertise.*started" "$lower"; then
  advertising_started=true
fi

if [[ "$gatt_started" == true && "$service_added" == true && "$advertising_started" == true ]]; then
  printf 'optional\tBLE Service\tPASS\t0\tGATT server, service add, and advertising logs found\n'
else
  printf 'optional\tBLE Service\tWARN\t0\tGATT/advertising logs incomplete; service may not have been started\n'
fi
