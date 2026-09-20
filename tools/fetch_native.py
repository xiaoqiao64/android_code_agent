#!/usr/bin/env python3
"""Fetch Termux aarch64 proot + deps into app/src/main/jniLibs/arm64-v8a/."""
from __future__ import annotations

import io
import lzma
import os
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/jniLibs/arm64-v8a"
TMP = ROOT / "tools/.native_tmp"
BASE = "https://packages.termux.dev/apt/termux-main/pool/main"
DEBS = [
    ("p/proot", "proot_5.1.107.92_aarch64.deb"),
    ("libt/libtalloc", "libtalloc_2.4.3_aarch64.deb"),
    ("liba/libandroid-shmem", "libandroid-shmem_0.7_aarch64.deb"),
]


def fetch(path: str, name: str) -> Path:
    TMP.mkdir(parents=True, exist_ok=True)
    dest = TMP / name
    if dest.exists() and dest.stat().st_size > 1000:
        print("cached", name)
        return dest
    url = f"{BASE}/{path}/{name}"
    print("GET", url)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        f.write(r.read())
    print("saved", name, dest.stat().st_size)
    return dest


def extract_ar(deb_path: Path) -> dict[str, bytes]:
    data = deb_path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit(f"not an ar archive: {deb_path}")
    off = 8
    members: dict[str, bytes] = {}
    while off + 60 <= len(data):
        header = data[off : off + 60]
        off += 60
        name = header[0:16].decode("ascii", errors="replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        payload = data[off : off + size]
        off += size + (size & 1)
        members[name] = payload
    return members


def extract_deb(name: str) -> Path:
    deb = TMP / name
    d = TMP / Path(name).stem
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    members = extract_ar(deb)
    if "data.tar.xz" in members:
        tar_bytes = lzma.decompress(members["data.tar.xz"])
    elif "data.tar.gz" in members:
        import gzip

        tar_bytes = gzip.decompress(members["data.tar.gz"])
    else:
        tar_bytes = members["data.tar"]
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:") as tf:
        tf.extractall(d)
    return d


def find_one(root: Path, pred):
    for p in root.rglob("*"):
        if p.is_file() and pred(p):
            return p
    return None


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for path, name in DEBS:
        fetch(path, name)
    dirs = {name: extract_deb(name) for _, name in DEBS}
    proot = find_one(dirs["proot_5.1.107.92_aarch64.deb"], lambda p: p.name == "proot" and "bin" in p.parts)
    loader = find_one(dirs["proot_5.1.107.92_aarch64.deb"], lambda p: p.name == "loader")
    talloc = find_one(dirs["libtalloc_2.4.3_aarch64.deb"], lambda p: "libtalloc.so" in p.name)
    shmem = find_one(dirs["libandroid-shmem_0.7_aarch64.deb"], lambda p: "libandroid-shmem.so" in p.name)
    assert all([proot, loader, talloc, shmem]), (proot, loader, talloc, shmem)
    for src, dst in [
        (proot, OUT / "libproot.so"),
        (loader, OUT / "libprootloader.so"),
        (talloc, OUT / "libtalloc.so"),
        (shmem, OUT / "libandroid-shmem.so"),
    ]:
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        print("->", dst.name, dst.stat().st_size)
    print("Done.")


if __name__ == "__main__":
    main()
