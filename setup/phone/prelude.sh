#!/bin/bash
# The helper-VM prelude of the phone's iOS 26 / watchOS setup (PhoneSetup26), before each job's script: what
# Install-iOS26.ps1's Helper-Prelude gives a step body (the folders and disk_tools, in_root, overlay, fresh,
# as the helper VM has them: the disks are QEMU's, linked where the files would be; the host made the
# overlays before the VM started), and what the phone adds:
#   step NAME      runs the shared body steps/NAME.sh (ios26/steps or watchos/steps of asoft-ios, its
#                  {PLACEHOLDERS} filled by the app from asoft-setup.json)
#   progress N T   a line for the setup screen
#   the scratch disk (serial scratch, when the job has one): ext4, for the folders .job/scratch names,
#                  which are too big for /work (job-run.sh's RAM folder)
# Set by the app above this: ASOFT_S, the scripts folder (scripts, or watchos/scripts for the watch).
set -euo pipefail
export ASOFT26=/work ASOFT26_LINUX=/root/ios26
A=$ASOFT26 L=$ASOFT26_LINUX S=$ASOFT26/${ASOFT_S:-scripts} W=$ASOFT26/watchos
export A L S W
mkdir -p "$L"
cd "$A"
disk_tools() {
	grep -q hfsplus /proc/filesystems || { echo "the helper VM's kernel has no hfsplus"; exit 1; }
}
in_root() {
	local f
	f=$(mktemp /tmp/asoft-in-root.XXXXXX)
	cat > "$f"
	bash "$S/root26-do.sh" "$A/$1" "set -euo pipefail; . $f"
	rm -f "${f:?}"
}
overlay() {
	[ -b "$A/${2:?}" ] || { echo "$2 is not one of this VM's disks"; exit 1; }
	echo "$2: an overlay on $1"
}
fresh() { :; }
step() {
	echo "== step $1"
	. "$A/steps/$1.sh"
}
progress() { echo "ASOFT: progress $1 $2"; }
# copy_watch MOUNT WANT_KB TEXT &: progress lines while a step copies an OS onto the disk mounted at MOUNT, from
# what is on it so far against WANT_KB (what the copy ends at, measured). The copy is one rsync of half an
# hour that prints nothing: without these the setup screen stood at 2 % all that time and read as stuck
# (0.1.4 and before). 3 to 95 %, never backwards; the caller kills it after the step.
copy_watch() {
	local m=$1 want=$2 text=$3 used pct last=3
	while sleep 20; do
		mountpoint -q "$m" || continue
		used=$(df -k "$m" 2> /dev/null | awk 'NR == 2 { print $3 }') || continue
		case $used in ''|*[!0-9]*) continue;; esac
		pct=$((3 + used * 92 / want))
		[ "$pct" -le 95 ] || pct=95
		[ "$pct" -ge "$last" ] || pct=$last
		last=$pct
		echo "ASOFT: progress $pct $text ($((used / 1048576)).$((used % 1048576 * 10 / 1048576)) GB so far)"
	done
}
# the new CDHash a dsc-patch.py run printed ("NAME OLD NEW"), edge-patch.py's ("NAME NEW ...")
new_hash() { grep -E '^dyld_shared_cache_arm64e\.[0-9]+ [0-9a-f]{40} [0-9a-f]{40}$' | awk '{print $3}'; }
edge_hash() { grep -E '^dyld_shared_cache_arm64e\.[0-9]+ [0-9a-f]{40}( |$)' | awk '{print $2}'; }
scratch=
for d in /sys/block/vd*; do
	if [ "$(cat "$d/serial" 2> /dev/null)" = scratch ]; then scratch=/dev/${d##*/}; fi
done
if [ -n "$scratch" ] && [ -s .job/scratch ]; then
	mkfs.ext4 -q -F -O ^has_journal "$scratch"
	mkdir -p /scratch
	mount "$scratch" /scratch
	while read -r p; do
		[ -n "$p" ] || continue
		mkdir -p "/scratch/$p" "$(dirname "$A/$p")"
		rm -rf "${A:?}/$p"
		ln -s "/scratch/$p" "$A/$p"
	done < .job/scratch
	echo "scratch: $(df -h /scratch | tail -1)"
fi
