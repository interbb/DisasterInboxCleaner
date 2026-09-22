#!/bin/bash
# Captures the eight guide screenshots from the connected Galaxy (folded, portrait) into a scratch dir outside
# the project (lib.sh sets GUIDE_RAW and wipes it on exit — the raw shots contain unmasked personal data)
# and records highlight boxes / privacy masks / crops in tools/guide/boxes.json (from uiautomator bounds).
# Usage: capture_guide.sh [STEP ...]   (default: 1 2 3 4 5 6 7 8; steps run in the order given)
# Requires: adb device, phone unlocked, Wi-Fi connected (wireless debugging needs it), the app installed with the
# notification permission, and the "Android 앱 호환성" debug-app warning already dismissed once.
# Step 3 turns wireless debugging off then on again (to catch the allow dialog) and leaves it on; step 7 temporarily
# resets the WRITE_SMS app-op so the "바로 시작" button is visible, then restores it.
set -u
. "$(dirname "$0")/lib.sh"
echo "display id: $DID"
SWITCH=NOTFOUND

step1() { echo "## 1 휴대전화 정보 → 소프트웨어 정보"
  open_settings android.settings.DEVICE_INFO_SETTINGS
  local B; B=$(find_scrolling "소프트웨어 정보") || { echo "  NOTFOUND"; return 1; }
  clearbox guide_01_software_info; shot guide_01_software_info; addbox guide_01_software_info boxes "$B"
  # The top card shows the device name, serial, phone number and IMEIs: mask every value node.
  local DEVNAME; DEVNAME=$(adb shell settings get global device_name | tr -d '\r')
  python3 - "$RAW/ui.xml" "$BOXES" "$DEVNAME" <<'EOF'
import json,re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); nodes=list(root.iter('node')); devname=sys.argv[3].strip(); masks=[]
def b(n):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get('bounds','')); return [int(x) for x in m.groups()] if m else None
labels=("시리얼 번호","전화번호","IMEI 1","IMEI 2","IMEI")
for i,n in enumerate(nodes):
    t=(n.get('text') or '').strip()
    if not t: continue
    if re.fullmatch(r"\d{2,3}-\d{3,4}-\d{4}", t) or re.fullmatch(r"\d{14,16}", t) or (devname and devname in t): masks.append(b(n))
    if t in labels and i+1 < len(nodes) and (nodes[i+1].get('text') or '').strip(): masks.append(b(nodes[i+1]))
d=json.load(open(sys.argv[2])); d['guide_01_software_info']['masks']=[m for m in masks if m]; json.dump(d,open(sys.argv[2],'w'),indent=1)
print('  masks', len(masks))
EOF
  adb shell input tap $(center "$B"); adb shell sleep 2; }

step2() { echo "## 2 빌드번호"
  local B; B=$(find_scrolling "빌드번호") || { echo "  NOTFOUND"; return 1; }
  clearbox guide_02_build_number; shot guide_02_build_number; addbox guide_02_build_number boxes "$B"; }

step3() { echo "## 3 개발자 옵션 → 무선 디버깅 스위치 (꺼진 상태)"
  adb shell settings put global adb_wifi_enabled 0; adb shell sleep 2
  open_settings android.settings.APPLICATION_DEVELOPMENT_SETTINGS
  local L; L=$(find_scrolling "무선 디버깅") || { echo "  NOTFOUND"; return 1; }
  SWITCH=$(bounds --switch "무선 디버깅"); [ "$SWITCH" = NOTFOUND ] && { echo "  switch NOTFOUND"; return 1; }
  clearbox guide_03_dev_wireless_switch; shot guide_03_dev_wireless_switch; addbox guide_03_dev_wireless_switch boxes "$(row "$L" "$SWITCH")"; }

step4() { echo "## 4 허용 확인창 (스위치를 켜면 뜸)"
  [ "$SWITCH" = NOTFOUND ] && { dump; SWITCH=$(bounds --switch "무선 디버깅"); }
  adb shell input tap $(center "$SWITCH"); adb shell sleep 2; dump
  local A; A=$(bounds --button "허용" "Allow"); [ "$A" = NOTFOUND ] && { echo "  allow button NOTFOUND"; nodes | head -12; return 1; }
  clearbox guide_04_wireless_allow
  local SSID; SSID=$(adb shell dumpsys wifi | grep -oE 'mWifiInfo SSID: "[^"]*"' | head -1 | sed 's/.*SSID: "//; s/"$//')
  if [ -n "$SSID" ]; then local M; M=$(bounds "$SSID"); [ "$M" != NOTFOUND ] && addbox guide_04_wireless_allow masks "$M"; fi
  shot guide_04_wireless_allow; addbox guide_04_wireless_allow boxes "$A"
  adb shell input tap $(center "$A"); adb shell sleep 2; }

step5() { echo "## 5 무선 디버깅 화면 → 페어링 코드로 기기 페어링"
  dump; local L; L=$(bounds "무선 디버깅"); adb shell input tap $(center "$L"); adb shell sleep 2
  local P; P=$(find_scrolling "페어링 코드로 기기 페어링" "페어링 코드") || { echo "  NOTFOUND"; return 1; }
  clearbox guide_05_wireless_page
  python3 - "$RAW/ui.xml" "$BOXES" <<'EOF'
import json,re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); nodes=list(root.iter('node')); masks=[]
def b(n):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get('bounds','')); return [int(x) for x in m.groups()] if m else None
for i,n in enumerate(nodes):
    t=n.get('text') or ''
    if '@' in t or re.search(r'\d+\.\d+\.\d+\.\d+', t): masks.append(b(n))
    if t.strip()=='기기 이름' and i+1 < len(nodes) and (nodes[i+1].get('text') or '').strip(): masks.append(b(nodes[i+1]))
d=json.load(open(sys.argv[2])); d.setdefault('guide_05_wireless_page',{"boxes":[],"masks":[]})['masks']=[m for m in masks if m]
json.dump(d,open(sys.argv[2],'w'),indent=1); print('  masks', len(masks))
EOF
  shot guide_05_wireless_page; addbox guide_05_wireless_page boxes "$P"
  adb shell input tap $(center "$P"); adb shell sleep 2; }

step6() { echo "## 6 페어링 대화상자 (코드·IP 마스킹)"
  dump; local C IPV; C=$(bounds --regex '[0-9]{6}')
  if [ "$C" = NOTFOUND ]; then   # dialog not open (step 6 run on its own): navigate from developer options
    open_settings android.settings.APPLICATION_DEVELOPMENT_SETTINGS
    local L P; L=$(find_scrolling "무선 디버깅") || { echo "  NOTFOUND"; return 1; }; adb shell input tap $(center "$L"); adb shell sleep 2
    P=$(find_scrolling "페어링 코드로 기기 페어링" "페어링 코드") || { echo "  NOTFOUND"; return 1; }; adb shell input tap $(center "$P"); adb shell sleep 2
    dump; C=$(bounds --regex '[0-9]{6}')
  fi
  IPV=$(bounds --regex '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+')
  echo "  code=$([ "$C" != NOTFOUND ] && echo found || echo NOTFOUND) ip=$([ "$IPV" != NOTFOUND ] && echo found || echo NOTFOUND)"
  [ "$C" = NOTFOUND ] && { nodes | sed -E 's/[0-9]/#/g' | head -14; }
  clearbox guide_06_pairing_dialog; shot guide_06_pairing_dialog
  [ "$C" != NOTFOUND ] && { addbox guide_06_pairing_dialog masks "$C"; addbox guide_06_pairing_dialog boxes "$C"; }
  [ "$IPV" != NOTFOUND ] && addbox guide_06_pairing_dialog masks "$IPV"
  # The page behind the dialog still shows the device name, IP:port and paired accounts (same layout as step 5):
  # reuse step 5's masks, clipped to the part not covered by the dialog panel.
  python3 - "$RAW/ui.xml" "$BOXES" <<'EOF'
import json,re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); rects=[]
for n in root.iter('node'):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get('bounds','')); r=[int(x) for x in m.groups()]
    if (n.get('text') or n.get('content-desc')) and r[2]-r[0] < 1200: rects.append(r)
d=json.load(open(sys.argv[2])); e=d['guide_06_pairing_dialog']; added=0
if rects:
    pad=45; dl=min(r[0] for r in rects)-pad; dt=min(r[1] for r in rects)-pad; dr=max(r[2] for r in rects)+pad; db=max(r[3] for r in rects)+pad
    for l,t,r,b in d.get('guide_05_wireless_page',{}).get('masks',[]):
        if b <= dt or t >= db or r <= dl or l >= dr: e['masks'].append([l,t,r,b]); added+=1     # fully outside the dialog
        elif l < dl: e['masks'].append([l,t,min(r,dl+6),b]); added+=1                            # sliver left of the dialog
    print('  dialog', [dl,dt,dr,db], 'background masks', added)
json.dump(d,open(sys.argv[2],'w'),indent=1)
EOF
  adb shell input keyevent BACK; adb shell sleep 1; }

step7() { echo "## 7 코드 입력 알림 (앱 설정 → '바로 시작' → 알림 펼침 → '보내기')"
  adb shell am force-stop $PKG; adb shell appops set $PKG WRITE_SMS default
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; adb shell sleep 4; dump
  if [ "$(bounds "바로 시작")" = NOTFOUND ]; then
    local G; G=$(bounds --button ""); [ "$G" = NOTFOUND ] && G="1085 142 1237 247"   # top-right settings icon
    adb shell input tap $(center "$G"); adb shell sleep 2
  fi
  local B; B=$(find_scrolling "바로 시작") || { echo "  start button NOTFOUND"; nodes | head -8; step7_restore; return 1; }
  adb shell input tap $(center "$B"); adb shell sleep 4
  adb shell cmd statusbar expand-notifications; adb shell sleep 2; dump
  local T; T=$(bounds "페어링 코드 입력"); [ "$T" = NOTFOUND ] && { echo "  notification NOTFOUND"; adb shell cmd statusbar collapse; step7_restore; return 1; }
  local TY; TY=$(echo "$T" | awk '{printf "%d", ($2+$4)/2}')
  adb shell input tap 1067 $TY; adb shell sleep 2; dump          # expand arrow at the right edge of our notification
  local N; N=$(bounds "보내기"); [ "$N" != NOTFOUND ] && { adb shell input tap $(center "$N"); adb shell sleep 2; dump; }
  echo "  send=$([ "$N" != NOTFOUND ] && echo found || echo NOTFOUND) field=$([ "$(bounds "6자리 코드")" != NOTFOUND ] && echo found || echo NOTFOUND)"
  shot guide_07_notification_input
  python3 - "$RAW/ui.xml" "$BOXES" <<'EOF'
import json,re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); want=("문자함 정리","페어링 코드 입력","6자리","보내기","취소","설정의 페어링 창"); hit=[]
for n in root.iter('node'):
    t=(n.get('text') or '')+'|'+(n.get('content-desc') or ''); c=n.get('class','')
    if any(w in t for w in want) or 'EditText' in c:
        m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get('bounds','')); hit.append((t[:30],c.split('.')[-1],[int(x) for x in m.groups()]))
e={"boxes":[],"masks":[]}
if hit:
    l=min(b[0] for *_,b in hit); t=min(b[1] for *_,b in hit); r=max(b[2] for *_,b in hit); bt=max(b[3] for *_,b in hit)
    e['crop']=[max(0,l-70), max(0,t-70), min(1248,r+70), min(1972,bt+30)]
    field=[b for x,c,b in hit if 'EditText' in c] or [b for x,c,b in hit if '6자리' in x] or [b for x,c,b in hit if '보내기' in x]
    e['boxes']=[field[0]] if field else []
print('  crop', e.get('crop'), 'box', e['boxes'])
d=json.load(open(sys.argv[2])); d['guide_07_notification_input']=e; json.dump(d,open(sys.argv[2],'w'),indent=1)
EOF
  adb shell input keyevent BACK; adb shell sleep 1; adb shell cmd statusbar collapse; step7_restore; }
step7_restore() { adb shell am force-stop $PKG; adb shell appops set $PKG WRITE_SMS allow; }

step8() { echo "## 8 무선 디버깅 끄기 (개발자 옵션의 켜진 스위치)"
  open_settings android.settings.APPLICATION_DEVELOPMENT_SETTINGS
  local L S; L=$(find_scrolling "무선 디버깅") || { echo "  NOTFOUND"; return 1; }
  S=$(bounds --switch "무선 디버깅"); [ "$S" = NOTFOUND ] && { echo "  switch NOTFOUND"; return 1; }
  clearbox guide_08_toggle_off; shot guide_08_toggle_off; addbox guide_08_toggle_off boxes "$(row "$L" "$S")"; }

STEPS=${*:-1 2 3 4 5 6 7 8}
for s in $STEPS; do "step$s"; done
echo "done: $(ls "$RAW"/guide_*.png 2>/dev/null | wc -l) shots; boxes in $BOXES"
if [ -n "${GUIDE_RAW_KEPT:-}" ] || [ "${RAW#${TMPDIR:-/tmp}}" = "$RAW" ]; then echo "raw captures kept in $RAW — delete them when done (they are unmasked)"; else
  python3 "$GUIDE_DIR/annotate_guide.py"; echo "annotated; raw captures wiped on exit"; fi
