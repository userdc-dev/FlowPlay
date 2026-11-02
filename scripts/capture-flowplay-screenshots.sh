#!/usr/bin/env bash
set -euo pipefail

# These are genuine screenshots captured from a published Flow Play APK,
# running on a freshly booted Android emulator. Do not substitute mockups.
mkdir -p screenshot-output screenshot-debug

# Keep diagnostics out of the public screenshots if the emulator misbehaves.
save_failure_diagnostics() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    echo "Screenshot capture failed; collecting emulator diagnostics."
    adb exec-out screencap -p > screenshot-debug/failure-screen.png 2>/dev/null || true
    adb shell uiautomator dump /sdcard/flowplay-failure-ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/flowplay-failure-ui.xml screenshot-debug/failure-ui.xml >/dev/null 2>&1 || true
    adb shell dumpsys activity activities > screenshot-debug/activities.txt 2>/dev/null || true
  fi
}
trap save_failure_diagnostics EXIT

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

# Android 13+ can show runtime permissions during the first launch. Grant a
# normal notification permission before opening the app, when requested.
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
sleep 10

foreground_activity() {
  adb shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' || true
}

# The Android permission controller is not a Flow Play crash.
# Dismiss first-run system permission sheets and re-check the active app.
for attempt in 1 2 3 4 5 6 7 8; do
  current="$(foreground_activity)"
  echo "Foreground activity (attempt $attempt): $current"
  if [[ "$current" == *"$package"* ]]; then
    break
  fi
  if [[ "$current" == *"com.google.android.permissioncontroller"* ||
        "$current" == *"com.android.permissioncontroller"* ]]; then
    adb shell input keyevent KEYCODE_BACK
    sleep 4
    continue
  fi
  if [[ "$current" == *"launcher"* || -z "$current" ]]; then
    adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
    sleep 6
    continue
  fi
  sleep 4
done
current="$(foreground_activity)"
echo "Foreground activity after permissions: $current"
if [[ "$current" != *"$package"* ]]; then
  echo "Flow Play has not reached the foreground; refusing misleading screenshots."
  exit 1
fi

# Let the app finish first-run navigation before checking visible tabs.
sleep 8

# Do not confuse an emulator system dialog with the actual app.
# Some emulator images show "Pixel Launcher isn't responding" over Flow Play.
# Only dismiss that known launcher problem, never an app crash.
verified=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if ! adb shell uiautomator dump /sdcard/flowplay-ui.xml >/dev/null 2>&1; then
    echo "UI dump not ready (attempt $attempt)."
    sleep 4
    continue
  fi
  adb pull /sdcard/flowplay-ui.xml screenshot-output/ui.xml >/dev/null

  ui_check="$(python3 - <<'PY'
import re
import xml.etree.ElementTree as ET
root = ET.parse('screenshot-output/ui.xml').getroot()
nodes = list(root.iter('node'))
labels = [((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).casefold().strip()
          for n in nodes]
print('UI labels:', ', '.join(x for x in labels if x)[:350], file=__import__('sys').stderr)
joined = ' '.join(labels)
if 'launcher isn' in joined and 'responding' in joined:
    for node in nodes:
        label = ((node.get('text') or '') + ' ' + (node.get('content-desc') or '')).casefold()
        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds') or '')
        if 'close app' in label and match:
            x1, y1, x2, y2 = map(int, match.groups())
            print(f'DISMISS {((x1+x2)//2)} {((y1+y2)//2)}')
            raise SystemExit(0)
    print('LAUNCHER_DIALOG')
elif any('subscription' in s or "what's new" in s or 'what’s new' in s
         or 'bookmarked playlist' in s for s in labels):
    print('VERIFIED')
else:
    print('WAIT')
PY
)"
  if [[ "$ui_check" == "VERIFIED" ]]; then
    current="$(foreground_activity)"
    if [[ "$current" == *"$package"* ]]; then
      echo "Verified real Flow Play home tabs and foreground app."
      verified=1
      break
    fi
  elif [[ "$ui_check" == DISMISS* ]]; then
    read -r _ tap_x tap_y <<< "$ui_check"
    echo "Dismissing unresponsive Android launcher, not Flow Play."
    adb shell input tap "$tap_x" "$tap_y"
  elif [[ "$ui_check" == "LAUNCHER_DIALOG" ]]; then
    echo "Launcher dialog visible without a Close app button; pressing Back."
    adb shell input keyevent KEYCODE_BACK
  fi
  sleep 5
done
if [[ "$verified" -ne 1 ]]; then
  echo "No verified Flow Play home screen after retries; refusing screenshots."
  exit 1
fi

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
