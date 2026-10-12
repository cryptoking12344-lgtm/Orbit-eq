#!/usr/bin/env python3
"""Tiny UI driver for the emulator test: find views by id / text / description and tap them."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.ck.orbiteq:id/"

def sh(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def dump():
    for _ in range(6):
        out = sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped" in out:
            sh("pull", "/sdcard/ui.xml", "/tmp/ui.xml")
            try:
                return ET.parse("/tmp/ui.xml").getroot()
            except Exception:
                pass
        time.sleep(1.5)
    return None

def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2

def dismiss_anr(root):
    """Taps 'Wait' on emulator 'isn't responding' popups (system apps, not Orbit)."""
    if root is None:
        return root
    for _ in range(3):
        texts = [(n.get("text") or "") for n in root.iter("node")]
        if not any("isn't responding" in t or "keeps stopping" in t for t in texts):
            return root
        for n in root.iter("node"):
            if (n.get("text") or "") in ("Wait", "Close app", "OK"):
                x1, y1, x2, y2 = bounds(n)
                sh("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                break
        time.sleep(1.5)
        root = dump()
    return root

def find(kind, value, index=0):
    root = dismiss_anr(dump())
    if root is None:
        return None
    hits = []
    for n in root.iter("node"):
        if kind == "id" and n.get("resource-id") == PKG + value: hits.append(n)
        elif kind == "text" and (n.get("text") or "").strip() == value: hits.append(n)
        elif kind == "desc" and (n.get("content-desc") or "") == value: hits.append(n)
    return hits[index] if len(hits) > index else None

def main():
    if sys.argv[1] == "dismiss":
        dismiss_anr(dump())
        return
    cmd, kind, value = sys.argv[1], sys.argv[2], sys.argv[3]
    fx = float(sys.argv[4]) if len(sys.argv) > 4 else 0.5
    fy = float(sys.argv[5]) if len(sys.argv) > 5 else 0.5
    index = int(sys.argv[6]) if len(sys.argv) > 6 else 0
    n = find(kind, value, index)
    if n is None:
        print(f"NOT FOUND: {kind}={value}")
        sys.exit(0)
    x1, y1, x2, y2 = bounds(n)
    x = int(x1 + (x2 - x1) * fx); y = int(y1 + (y2 - y1) * fy)
    print(f"tap {kind}={value} at {x},{y}")
    sh("shell", "input", "tap", str(x), str(y))

main()
