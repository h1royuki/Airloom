#!/usr/bin/env bash
# Shared local tool discovery. Explicit environment variables always win.
AIRLOOM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  airloom_bundled_jdk="$AIRLOOM_ROOT/tools/jdk-expanded/net.temurin.21.jdk.pkg/Payload/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
  if [[ -x "$airloom_bundled_jdk/bin/java" ]]; then
    export JAVA_HOME="$airloom_bundled_jdk"
  elif [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for airloom_sdk in "${ANDROID_SDK_ROOT:-}" "$AIRLOOM_ROOT/tools/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$airloom_sdk" && -d "$airloom_sdk/platforms/android-35" ]]; then
      export ANDROID_HOME="$airloom_sdk"
      break
    fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -n "${ANDROID_HOME:-}" ]]; then export PATH="$ANDROID_HOME/platform-tools:$PATH"; fi
