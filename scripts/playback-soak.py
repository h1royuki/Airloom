#!/usr/bin/env python3
"""Observe foreground Airloom playback without changing device state or logging media URLs."""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--port", type=int, default=5037)
parser.add_argument("--seconds", type=int, default=3600)
parser.add_argument("--interval", type=int, default=20)
parser.add_argument("--output", default="artifacts/playback-soak.jsonl")
args = parser.parse_args()
if args.seconds < 1 or args.interval < 1:
    parser.error("Durations must be positive")
adb = ["adb", "-P", str(args.port), "-s", args.serial]
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)

def shell(*command):
    result = subprocess.run(adb + ["shell", *command], capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise RuntimeError("Device command failed")
    return result.stdout

started = time.monotonic()
samples = []
baseline_pid = None
baseline_media = None
with output.open("w") as log:
    while True:
        elapsed = time.monotonic() - started
        sample = {"elapsed_s": round(elapsed, 1)}
        try:
            sample["pid"] = shell("pidof", "tv.airloom.app").strip()
            sessions = shell("dumpsys", "media_session")
            match = re.search(r"^    .*tv\.airloom\.app/.*$", sessions, re.M)
            if match is None:
                raise RuntimeError("Airloom media session missing")
            block = re.split(r"\n    \S", sessions[match.end():], maxsplit=1)[0]
            state = re.search(r"state=PlaybackState \{state=(\d+), position=(-?\d+)", block)
            if state is None:
                raise RuntimeError("Playback state missing")
            sample["state"] = int(state[1])
            sample["position_ms"] = int(state[2])
            metadata = re.search(r"metadata: (.*)", block)
            media = hashlib.sha256((metadata[1] if metadata else "").encode()).hexdigest()
            if baseline_pid is None:
                baseline_pid, baseline_media = sample["pid"], media
            sample["same_process"] = baseline_pid == sample["pid"]
            sample["same_media"] = baseline_media == media
            if not samples or elapsed - samples[-1].get("last_memory_s", 0) >= 60:
                memory = shell("dumpsys", "meminfo", "tv.airloom.app")
                pss = re.search(r"TOTAL PSS:\s*(\d+)", memory)
                if pss:
                    sample["pss_kb"] = int(pss[1])
                sample["last_memory_s"] = elapsed
            else:
                sample["last_memory_s"] = samples[-1].get("last_memory_s", 0)
            sample["healthy"] = sample["state"] == 3 and sample["same_process"] and sample["same_media"]
        except Exception as error:
            sample["healthy"] = False
            sample["error"] = type(error).__name__
        samples.append(sample)
        line = json.dumps(sample)
        log.write(line + "\n")
        log.flush()
        print(line, flush=True)
        if elapsed >= args.seconds:
            break
        time.sleep(min(args.interval, args.seconds - elapsed))
    memory = [s["pss_kb"] for s in samples if "pss_kb" in s]
    summary = {"duration_s": round(time.monotonic() - started, 1), "samples": len(samples),
               "healthy_samples": sum(s["healthy"] for s in samples),
               "pss_min_kb": min(memory) if memory else None,
               "pss_max_kb": max(memory) if memory else None,
               "passed": all(s["healthy"] for s in samples)}
    log.write(json.dumps({"summary": summary}) + "\n")
    print(json.dumps({"summary": summary}), flush=True)
raise SystemExit(0 if summary["passed"] else 1)
