#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
cd "$AIRLOOM_ROOT"
if [[ $# -ne 2 ]]; then echo "Usage: $0 input.apk output.apk" >&2; exit 1; fi
airloom_input="$1"
airloom_output="$2"
if [[ ! -f "$airloom_input" ]]; then echo 'Input APK does not exist.' >&2; exit 1; fi
airloom_signing_dir="${AIRLOOM_SIGNING_DIR:-$HOME/.local/share/airloom/signing}"
umask 077
mkdir -p "$airloom_signing_dir" "$(dirname "$airloom_output")"
airloom_signing_dir="$(cd "$airloom_signing_dir" && pwd -P)"
case "$airloom_signing_dir/" in "$AIRLOOM_ROOT/"*) echo 'Signing keys must be outside the repository.' >&2; exit 1;; esac
airloom_key="$airloom_signing_dir/release.p12"
airloom_password="$airloom_signing_dir/password"
if [[ ! -f "$airloom_key" ]]; then
  if [[ ! -f "$airloom_password" ]]; then openssl rand -hex 32 > "$airloom_password"; fi
  keytool -genkeypair -keystore "$airloom_key" -storetype PKCS12 -alias airloom \
    -storepass:file "$airloom_password" -keypass:file "$airloom_password" \
    -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Airloom Personal, O=Airloom'
fi
if [[ ! -f "$airloom_password" ]]; then echo 'Signing password file missing; restore the original signing files.' >&2; exit 1; fi
airloom_tools="$ANDROID_HOME/build-tools/35.0.0"
airloom_aligned="$(mktemp "${TMPDIR:-/tmp}/airloom-aligned.XXXXXX")"
trap 'rm -f "$airloom_aligned"' EXIT
"$airloom_tools/zipalign" -f -p 4 "$airloom_input" "$airloom_aligned"
"$airloom_tools/apksigner" sign --ks "$airloom_key" --ks-key-alias airloom \
  --ks-pass "file:$airloom_password" \
  --out "$airloom_output" "$airloom_aligned"
"$airloom_tools/apksigner" verify --verbose "$airloom_output"
echo "APK: $airloom_output"
echo "Back up the signing directory securely: $airloom_signing_dir"
