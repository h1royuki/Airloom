#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
cd "$AIRLOOM_ROOT"
./scripts/build.sh :app:assembleRelease
exec ./scripts/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk artifacts/airloom-release.apk
