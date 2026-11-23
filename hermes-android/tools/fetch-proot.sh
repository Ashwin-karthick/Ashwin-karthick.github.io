#!/usr/bin/env bash
# Downloads Termux's proot build and its two libraries (aarch64), checks their SHA-256, and places
# them under app/src/main/jniLibs/arm64-v8a with lib*.so names so Android installs them as files.
# proot is GPL-2.0, talloc LGPL-3.0, libandroid-shmem BSD-3-Clause; their source is linked in NOTICE.md.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=https://packages.termux.dev/apt/termux-main/pool/main
DEBS=(
  "p/proot/proot_5.1.107.102_aarch64.deb 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"
  "libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
OUT=app/src/main/jniLibs/arm64-v8a
mkdir -p "$OUT"
for entry in "${DEBS[@]}"; do
  path=${entry% *}; sum=${entry#* }; file=$WORK/$(basename "$path")
  curl -fsSL --retry 3 -o "$file" "$BASE/$path"
  echo "$sum  $file" | sha256sum -c - >/dev/null || { echo "Checksum mismatch for $path" >&2; exit 1; }
  dir=$WORK/x-$(basename "$path" .deb); mkdir -p "$dir"
  (cd "$dir" && ar x "$file" && tar -xf data.tar.*)
done
P=data/data/com.termux/files/usr
install -m 0755 "$WORK"/x-proot_*/$P/bin/proot                "$OUT/libproot.so"
install -m 0755 "$WORK"/x-proot_*/$P/libexec/proot/loader     "$OUT/libproot-loader.so"
install -m 0755 "$WORK"/x-libtalloc_*/$P/lib/libtalloc.so.2.5.0 "$OUT/libtalloc.so"
install -m 0755 "$WORK"/x-libandroid-shmem_*/$P/lib/libandroid-shmem.so "$OUT/libandroid-shmem.so"
ls -l "$OUT"
