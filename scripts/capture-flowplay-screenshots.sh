#!/usr/bin/env bash
set -euo pipefail

# These are genuine screenshots captured from a published Flow Play APK,
# running on a freshly booted Android emulator. Do not substitute mockups.
mkdir -p screenshot-output
adb wait-for-device
adb install -r flow-play-release.apk

# Identify the app installed by this run instead of guessing its package name.
mapfile -t packages < <(adb shell pm list packages -3 | tr -d '\r' | sed 's/^package://' | sed '/^$/d')
if [[ "${#packages[@]}" -ne 1 ]]; then
  printf 'Expected one newly installed user app, found: %s\n' "${packages[*]}"
  exit 1
fi
package="${packages[0]}"
echo "Installed Flow Play APK as: $package"

adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
sleep 30

# Verify that the app, and not the Android launcher, is on screen.
current="$(adb shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' || true)"
echo "Foreground activity: $current"
if [[ "$current" != *"$package"* ]]; then
  echo "Flow Play did not remain in the foreground; refusing misleading screenshots."
  exit 1
fi

# A real Flow Play home screen should expose the app's main tabs.
adb shell uiautomator dump /sdcard/flowplay-ui.xml >/dev/null
adb pull /sdcard/flowplay-ui.xml screenshot-output/ui.xml >/dev/null
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('screenshot-output/ui.xml').getroot()
labels = [((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).casefold()
          for n in root.iter('node')]
print('Visible labels:', [x.strip() for x in labels if x.strip()][:60])
if not any('subscription' in s or "what's new" in s or 'what’s new' in s
           or 'bookmarked playlist' in s for s in labels):
    raise SystemExit('Could not verify the Flow Play home tabs; screenshots not published.')
PY

# Capture the real app home screen.
adb exec-out screencap -p > screenshot-output/01-flow-play-home.png

# Try to open the real navigation drawer with a normal swipe.
adb shell input swipe 4 900 830 900 450
sleep 3
current="$(adb shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' || true)"
if [[ "$current" == *"$package"* ]]; then
  adb exec-out screencap -p > screenshot-output/02-flow-play-navigation.png
fi

# Return to the home screen and tap the real Search button if present.
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell uiautomator dump /sdcard/flowplay-ui.xml >/dev/null
adb pull /sdcard/flowplay-ui.xml screenshot-output/ui.xml >/dev/null
search_xy="$(python3 - <<'PY'
import re
import xml.etree.ElementTree as ET
root = ET.parse('screenshot-output/ui.xml').getroot()
for n in root.iter('node'):
    label = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).lower()
    match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
    if 'search' in label and match:
        x1, y1, x2, y2 = map(int, match.groups())
        print(f'{(x1+x2)//2} {(y1+y2)//2}')
        break
PY
)"
if [[ -n "$search_xy" ]]; then
  adb shell input tap $search_xy
  sleep 4
  current="$(adb shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' || true)"
  if [[ "$current" == *"$package"* ]]; then
    adb exec-out screencap -p > screenshot-output/03-flow-play-search.png
  fi
fi

# Reject empty screenshot files.
for f in screenshot-output/*.png; do
  test "$(stat -c %s "$f")" -gt 10000 || {
    echo "Screenshot is unexpectedly small: $f"
    exit 1
  }
  file "$f"
done
rm -f screenshot-output/ui.xml
