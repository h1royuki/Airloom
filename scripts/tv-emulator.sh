#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
airloom_api="${AIRLOOM_TV_API:-31}"
case "$(uname -m)" in arm64|aarch64) airloom_abi=arm64-v8a;; *) airloom_abi=x86;; esac
airloom_package="system-images;android-$airloom_api;android-tv;$airloom_abi"
if [[ ! -f "$ANDROID_HOME/system-images/android-$airloom_api/android-tv/$airloom_abi/system.img" ]]; then
  echo "Install the TV system image first:"
  echo "\"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" '$airloom_package'"
  exit 1
fi
export ANDROID_AVD_HOME="${AIRLOOM_AVD_HOME:-$HOME/.cache/airloom-tools/avd}"
mkdir -p "$ANDROID_AVD_HOME"
airloom_avd="Airloom_TV_API$airloom_api"
if [[ ! -f "$ANDROID_AVD_HOME/$airloom_avd.ini" ]]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name "$airloom_avd" \
    --package "$airloom_package" --device tv_1080p --path "$ANDROID_AVD_HOME/$airloom_avd.avd"
fi
airloom_config="$ANDROID_AVD_HOME/$airloom_avd.avd/config.ini"
if [[ -f "$airloom_config" ]]; then
  if grep -q '^hw.keyboard=' "$airloom_config"; then
    if [[ "$(uname)" == Darwin ]]; then
      sed -i '' 's/^hw.keyboard=.*/hw.keyboard=yes/' "$airloom_config"
    else
      sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$airloom_config"
    fi
  else
    printf '\nhw.keyboard=yes\n' >> "$airloom_config"
  fi
fi
exec "$ANDROID_HOME/emulator/emulator" -avd "$airloom_avd" -port "${AIRLOOM_EMULATOR_PORT:-5558}" \
  -no-snapshot-save -no-metrics "$@"
