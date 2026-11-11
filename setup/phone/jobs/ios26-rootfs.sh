# Install-iOS26.ps1's rootfs and trustcache steps
progress 2 "copying iOS onto its disk"
# mk-root26.sh's mount point; iOS 26.6.2 ends at 18 GB on it (S25 Ultra, 2026-11-08)
copy_watch /tmp/mkroot26/root $((18 * 1048576)) "copying iOS onto its disk" &
cw=$!
step rootfs
kill $cw 2> /dev/null || true
progress 97 "its trust cache"
python3 -I $S/mktc.py -2 root26.tc iphone/{OS}.aea.trustcache iphone/{SYSOS}.aea.trustcache iphone/{APPOS}.trustcache root26.qcow2.hashes
