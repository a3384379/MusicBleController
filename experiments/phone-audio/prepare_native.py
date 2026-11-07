#!/usr/bin/env python3
"""Prepare only hash-locked arm64 JNI libraries; no arbitrary archive extraction or source execution."""
import argparse
import hashlib
import json
import os
import struct
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAX_APK_BYTES = 40 * 1024 * 1024
MAX_LIBRARY_BYTES = 16 * 1024 * 1024


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def load_libraries(apk, lock):
    if apk.stat().st_size > MAX_APK_BYTES or digest(apk) != lock["apk_sha256"]:
        raise ValueError("Upstream APK SHA-256 mismatch")
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for name, expected in lock["native_libraries"].items():
            if Path(name).name != name or not name.endswith(".so"):
                raise ValueError("Invalid locked library name")
            entry = archive.getinfo("lib/arm64-v8a/" + name)
            if entry.file_size > MAX_LIBRARY_BYTES:
                raise ValueError("Native library too large")
            data = archive.read(entry)
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("Native library SHA-256 mismatch: " + name)
            if (len(data) < 20 or data[:6] != b"\x7fELF\x02\x01"
                    or struct.unpack_from("<H", data, 18)[0] != 183):
                raise ValueError("Library is not arm64 ELF: " + name)
            libraries[name] = data
    return libraries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Use a local copy of the locked release APK")
    args = parser.parse_args()
    lock = json.loads((ROOT / "upstream.lock.json").read_text())
    cache = ROOT / ".cache"
    cache.mkdir(exist_ok=True)
    apk = args.apk or cache / "upstream-v0.0.31.apk"
    if args.apk is None and not apk.exists():
        request = urllib.request.Request(lock["apk_url"], headers={"User-Agent": "PhoneAudioLab/0.1"})
        with urllib.request.urlopen(request, timeout=45) as response, tempfile.NamedTemporaryFile(
                dir=cache, delete=False) as target:
            temp = Path(target.name)
            try:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_APK_BYTES:
                        raise ValueError("Upstream download exceeded limit")
                    target.write(chunk)
                target.flush()
                if digest(temp) != lock["apk_sha256"]:
                    raise ValueError("Downloaded APK SHA-256 mismatch")
                os.replace(temp, apk)
            finally:
                temp.unlink(missing_ok=True)
    # Verify every entry before changing any destination.
    libraries = load_libraries(apk, lock)
    dest = ROOT / "receiver/app/src/main/jniLibs/arm64-v8a"
    dest.mkdir(parents=True, exist_ok=True)
    for name, data in libraries.items():
        (dest / name).write_bytes(data)
    print(json.dumps({"result": "PASS", "upstream_commit": lock["commit"],
                      "apk_sha256": lock["apk_sha256"], "libraries": lock["native_libraries"]}, indent=2))


if __name__ == "__main__":
    main()
