# Install-watchOS.ps1's rootfs step
progress 2 "copying watchOS onto its disk"
# mk-rootw.sh's mount point; watchOS 27.2's 8.97 GB image ended at 10.7 GB on it (S25 Ultra, 2026-11-12): the
# image's size times 1.2, for another build's image
img=$(blockdev --getsize64 "$W/os/{OS}" 2> /dev/null || stat -Lc %s "$W/os/{OS}" 2> /dev/null || echo 9000000000)
copy_watch /tmp/mkrootw/root $((img / 1024 * 12 / 10)) "copying watchOS onto its disk" &
cw=$!
step rootfs
kill $cw 2> /dev/null || true
