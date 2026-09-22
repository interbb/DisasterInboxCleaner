# Shared helpers for the guide capture scripts. Source this file: . "$(dirname "$0")/lib.sh"
GUIDE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Scratch for screenshots and uiautomator dumps. These hold unmasked personal data (device name,
# phone number, IMEI, IP:port, pairing codes), so they live outside the project and are wiped on exit.
# Pass GUIDE_RAW to keep the captures after the script exits (needed to re-run annotate_guide.py
# on its own); delete that directory yourself when done. Without it, a private temp dir is used
# and wiped on exit.
if [ -n "${GUIDE_RAW:-}" ]; then
  RAW="$GUIDE_RAW"; mkdir -p "$RAW"
else
  RAW="${TMPDIR:-/tmp}/guide-raw-$$"; mkdir -p "$RAW"; export GUIDE_RAW="$RAW"
  trap 'rm -rf "$RAW"' EXIT
fi
BOXES="$GUIDE_DIR/boxes.json"; [ -f "$BOXES" ] || echo "{}" > "$BOXES"
PKG=com.interbb.disasterinboxcleaner
# Foldables expose several displays; screencap needs the one that is ON.
DID=$(adb shell dumpsys display | grep 'DisplayDeviceInfo' | grep 'state ON' | grep -oE 'local:[0-9]+' | head -1 | cut -d: -f2)
[ -z "$DID" ] && DID=$(adb shell dumpsys SurfaceFlinger --display-id | head -1 | grep -oE '[0-9]{6,}' | head -1)
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$RAW/ui.xml" >/dev/null 2>&1; }
bounds() { python3 "$GUIDE_DIR/find_bounds.py" "$RAW/ui.xml" "$@"; }
center() { echo "$1" | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
row() { echo "$1 $2" | awk '{l=$1; t=($2<$6)?$2:$6; r=$7; b=($4>$8)?$4:$8; printf "%d %d %d %d", l, t, r, b}'; }
shot() { adb shell screencap -p -d "$DID" /sdcard/guide_shot.png >/dev/null 2>&1; adb pull /sdcard/guide_shot.png "$RAW/$1.png" >/dev/null 2>&1; adb shell rm /sdcard/guide_shot.png; echo "shot $1"; }
addbox() { python3 - "$BOXES" "$1" "$2" "$3" <<'EOF'
import json,sys
p,name,kind,b=sys.argv[1:5]; d=json.load(open(p)); e=d.setdefault(name,{"boxes":[],"masks":[]}); e[kind].append([int(x) for x in b.split()]); json.dump(d,open(p,'w'),indent=1)
EOF
}
clearbox() { python3 - "$BOXES" "$1" <<'EOF'
import json,sys
p,name=sys.argv[1:3]; d=json.load(open(p)); d[name]={"boxes":[],"masks":[]}; json.dump(d,open(p,'w'),indent=1)
EOF
}
find_scrolling() { # queries...; scrolls down up to 6 times until found; prints bounds
  for i in 1 2 3 4 5 6; do dump; b=$(bounds "$@"); [ "$b" != "NOTFOUND" ] && { echo "$b"; return 0; }; adb shell input swipe 540 1700 540 700 300; adb shell sleep 1; done; echo NOTFOUND; return 1; }
open_settings() { adb shell am force-stop com.android.settings; adb shell am start -a "$1" >/dev/null; adb shell sleep 2; }
nodes() { python3 - "$RAW/ui.xml" <<'EOF'
import xml.etree.ElementTree as ET,sys
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    t=n.get('text') or ''; d=n.get('content-desc') or ''; c=n.get('class','').split('.')[-1]
    if t or d or 'Switch' in c or 'Button' in c or 'EditText' in c:
        print(f"{c:16} click={n.get('clickable')} checked={n.get('checked')} {n.get('bounds')} {t[:40]!r} {d[:30]!r}")
EOF
}
