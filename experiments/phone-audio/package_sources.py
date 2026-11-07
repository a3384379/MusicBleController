#!/usr/bin/env python3
"""Package the lab and pinned upstream sources beside the experimental APK."""
import argparse
import hashlib
import json
import stat
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
IGNORED = {".cache", ".gradle", "artifacts", "build", "jniLibs", "__pycache__"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(path, *arguments):
    return subprocess.check_output(["git", "-C", str(path), *arguments], timeout=30)


def verify_checkout(path, commit):
    if git(path, "rev-parse", "HEAD").decode().strip() != commit:
        raise ValueError("Source checkout does not match the locked commit: " + str(path))
    subprocess.run(["git", "-C", str(path), "diff", "--quiet", "HEAD", "--"], check=True, timeout=30)


def add_file(archive, path, name):
    if path.is_symlink():
        # Preserve source symlinks rather than reading files outside the source tree.
        info = zipfile.ZipInfo(str(name))
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive.writestr(info, path.readlink().as_posix())
    else:
        archive.write(path, str(name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="Clean recursive checkout of the locked revision")
    parser.add_argument("--openssl-archive", type=Path, required=True)
    parser.add_argument("--oboe-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/PhoneAudioLab-sources.zip")
    args = parser.parse_args()
    lock = json.loads((ROOT / "upstream.lock.json").read_text())
    try:
        verify_checkout(args.upstream, lock["commit"])
        for path, commit in lock["submodules"].items():
            verify_checkout(args.upstream / path, commit)
        dependencies = {"openssl": args.openssl_archive, "oboe": args.oboe_archive}
        for name, path in dependencies.items():
            if sha256(path) != lock["source_dependencies"][name]["archive_sha256"]:
                raise ValueError("Dependency source archive hash mismatch: " + name)
            with zipfile.ZipFile(path) as source:
                if source.testzip() is not None:
                    raise ValueError("Damaged source archive: " + name)
        files = git(args.upstream, "ls-files", "--recurse-submodules", "-z").decode().split("\0")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(".zip.tmp")
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for relative in files:
                    if relative:
                        add_file(archive, args.upstream / relative, Path("upstream") / relative)
                for name, path in dependencies.items():
                    archive.write(path, "dependency-sources/" + name + ".zip")
                for path in sorted(ROOT.rglob("*")):
                    relative = path.relative_to(ROOT)
                    if path.is_file() and not any(part in IGNORED for part in relative.parts):
                        add_file(archive, path, Path("lab/MusicBleController/experiments/phone-audio") / relative)
                repo = ROOT.parent.parent
                for relative in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                                 "gradle/wrapper/gradle-wrapper.properties"):
                    archive.write(repo / relative, "lab/MusicBleController/" + relative)
                archive.writestr("SOURCE-MANIFEST.json", json.dumps(lock, indent=2) + "\n")
            temporary.replace(args.output)
        finally:
            temporary.unlink(missing_ok=True)
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(2, "Source packaging failed: " + str(error) + "\n")
    digest = sha256(args.output)
    args.output.with_suffix(".zip.sha256").write_text(digest + "  " + args.output.name + "\n")
    print(json.dumps({"sources": str(args.output), "sha256": digest,
                      "native_recompiled_locally": False}, indent=2))


if __name__ == "__main__":
    main()
