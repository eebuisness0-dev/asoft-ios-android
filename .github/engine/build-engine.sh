#!/bin/bash
# The phone engine, libqemu26.so: qemu-sptm (asoft-android) cross-built with the NDK, with reims-vgpu
# (Vulkan platform, a static archive found through pkg-config) and metal2vulkan inside it.
# A reconstruction of the author's private 13-qemu.sh: it has to be fitted to the runner by its logs.
#
# Needs in the environment: SRC (the checkouts: qemu-sptm, reims-vgpu, metal2vulkan-win), NDK (NDK root),
# OUT (where libqemu26.so, engine-features.txt and needed.txt go).
set -euo pipefail
API=29
HOST_TAG=linux-x86_64
TC=$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin
TRIPLE=aarch64-linux-android
export PATH=$TC:$PATH
mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)

echo "::group::Termux libraries"
SYS=$PWD/sysroot
mkdir -p "$SYS"
python3 "$HERE/termux-sysroot.py" "$SYS" glib libpixman libslirp pcre2 libffi libiconv zlib
USR=$SYS/data/data/com.termux/files/usr
echo "::endgroup::"

echo "::group::reims-vgpu (Vulkan platform) for Android"
cd "$SRC/reims-vgpu"
rustup target add $TRIPLE   # for the toolchain rust-toolchain.toml pins
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/${TRIPLE}${API}-clang
export CC_aarch64_linux_android=$TC/${TRIPLE}${API}-clang
export CXX_aarch64_linux_android=$TC/${TRIPLE}${API}-clang++
export AR_aarch64_linux_android=$TC/llvm-ar
# the library's inline assembly uses ARMv8.4 atomics (LSE) and RCpc loads: phones of the last years have them
export RUSTFLAGS="-C target-feature=+lse,+rcpc"
cargo rustc --release --target $TRIPLE -p reims-vgpu-c --lib \
    --no-default-features --features vulkan --crate-type staticlib \
    --message-format json -- --print native-static-libs > "$OUT/cargo-reims.json" 2> >(tee "$OUT/cargo-reims.err" >&2) || { grep -o '"rendered":"[^"]*"' "$OUT/cargo-reims.json" | head -20; exit 1; }
LIBS=$(python3 - "$OUT/cargo-reims.json" <<'PY'
import sys
t = open(sys.argv[1]).read()
i = t.find("native-static-libs: ")
if i < 0:
    sys.exit("rustc printed no native-static-libs note")
r = t[i + len("native-static-libs: "):]
print(r[:min([x for x in (r.find(c) for c in ('"', '\\', '\n')) if x >= 0] or [len(r)])].strip())
PY
)
echo "Libs.private: $LIBS"
BUILT=$SRC/reims-vgpu/target/$TRIPLE/release/libreims_vgpu_c.a
# the archive as one object that exports only the rvg_ API, as `cargo xtask dist` does on Linux
DIST=$SYS/dist
mkdir -p "$DIST/lib/pkgconfig" "$DIST/include" "$PWD/work"
ld.lld -r --whole-archive "$BUILT" -o "$PWD/work/reims_vgpu.o"
llvm-nm --defined-only --extern-only "$PWD/work/reims_vgpu.o" | awk '$3 ~ /^rvg_/ {print $3}' > "$PWD/work/keep"
test -s "$PWD/work/keep" || { echo "no rvg_ symbol in the archive"; exit 1; }
llvm-objcopy --keep-global-symbols="$PWD/work/keep" "$PWD/work/reims_vgpu.o"
rm -f "$DIST/lib/libreims_vgpu.a"
llvm-ar rcs "$DIST/lib/libreims_vgpu.a" "$PWD/work/reims_vgpu.o"
cp crates/reims-vgpu-c/include/reims_vgpu.h "$DIST/include/"
# prefix /dist, under the sysroot QEMU's pkg-config prepends to every path
cat > "$DIST/lib/pkgconfig/reims-vgpu.pc" <<PC
prefix=/dist
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: reims-vgpu
Description: reims-vgpu, the paravirtualized GPU, on the Vulkan platform
Version: 0.1.0
Libs: -L\${libdir} -lreims_vgpu
Libs.private: $LIBS
Cflags: -I\${includedir}
PC
cd - > /dev/null
echo "::endgroup::"

echo "::group::QEMU configure"
export PKG_CONFIG_SYSROOT_DIR=$SYS
export PKG_CONFIG_LIBDIR=$USR/lib/pkgconfig:$USR/share/pkgconfig:$DIST/lib/pkgconfig
export PKG_CONFIG_PATH=
export AR=$TC/llvm-ar NM=$TC/llvm-nm RANLIB=$TC/llvm-ranlib STRIP=$TC/llvm-strip OBJCOPY=$TC/llvm-objcopy
export PKG_CONFIG=pkg-config
# bionic has no librt (nor libutil): meson looks for them, empty archives stand in
STUBS=$PWD/stubs
mkdir -p "$STUBS"
for l in rt util; do printf '!<arch>\n' > "$STUBS/lib$l.a"; done
mkdir -p qemu-build && cd qemu-build
"$SRC/qemu-sptm/configure" \
    --cross-prefix="${TRIPLE}-" --cpu=aarch64 \
    --cc="$TC/${TRIPLE}${API}-clang" --cxx="$TC/${TRIPLE}${API}-clang++" --host-cc=gcc \
    --extra-cflags="-I$USR/include -Wno-error" --extra-ldflags="-L$STUBS -L$USR/lib -Wl,-rpath-link,$USR/lib" \
    --target-list=aarch64-softmmu --disable-werror --disable-docs --disable-guest-agent \
    --disable-tools --disable-user --disable-linux-user --disable-bsd-user \
    --disable-gtk --disable-sdl --disable-curses --disable-opengl --disable-vnc \
    --disable-gnutls --disable-nettle --disable-gcrypt --disable-capstone --disable-libusb \
    --disable-linux-io-uring --disable-vhost-user --disable-vhost-net --disable-vhost-kernel \
    --enable-slirp --enable-reims-vgpu || {
    echo "::error::QEMU configure failed; its config.log follows"; tail -n 80 meson-logs/meson-log.txt 2>/dev/null || true; exit 1; }
echo "::endgroup::"

echo "::group::QEMU build"
ninja qemu-system-aarch64
echo "::endgroup::"

llvm-strip -o "$OUT/libqemu26.so" qemu-system-aarch64
# which libraries it needs at run time: pull-libs.sh puts only these in the APK
llvm-readelf -d "$OUT/libqemu26.so" | awk '/NEEDED/ {gsub(/[\[\]]/, "", $5); print $5}' | sort > "$OUT/needed.txt"
printf 'unordered-fetch\n' > "$OUT/engine-features.txt"
ls -la "$OUT"
