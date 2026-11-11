# Install-watchOS.ps1's rootfs step
progress 2 "copying watchOS onto its disk"
# mk-rootw.sh's mount point; what the copy ends at is not measured for the watch: the image's size times
# iOS 26's ratio (9.52 GB of image became 18 GB on the disk)
img=$(blockdev --getsize64 "$W/os/{OS}" 2> /dev/null || stat -Lc %s "$W/os/{OS}" 2> /dev/null || echo 9000000000)
copy_watch /tmp/mkrootw/root $((img / 1024 * 19 / 10)) "copying watchOS onto its disk" &
cw=$!
step rootfs
kill $cw 2> /dev/null || true
