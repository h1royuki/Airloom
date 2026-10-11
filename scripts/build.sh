#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
cd "$AIRLOOM_ROOT"
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo 'Android SDK 35 not found. Set ANDROID_HOME to your SDK directory.' >&2
  exit 1
fi
if [[ $# -eq 0 ]]; then set -- :core:test :app:assembleDebug; fi
exec ./gradlew "$@"
