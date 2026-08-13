#!/usr/bin/env bash
#
# Grants the two app-ops the watch needs before an approval can wake a sleeping screen.
#
# Neither can be granted from inside the app: setting an app-op requires the shell uid or
# MANAGE_APP_OPS_MODES, which is signature|privileged. Nor can they be granted on the watch itself
# — Wear ships a cut-down Settings with no screen for either. ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
# does not resolve at all on a Galaxy Watch 6, and ACTION_MANAGE_OVERLAY_PERMISSION opens a page
# with no such toggle. So this is a host-side step, run once per install.
#
#   USE_FULL_SCREEN_INTENT  lets the notification service honour setFullScreenIntent at all.
#   SYSTEM_ALERT_WINDOW     the background-activity-launch exemption. Nothing is ever drawn over
#                           anything; this is held solely so the approval screen may start while
#                           no activity of ours is visible. Without it the launch is refused even
#                           with the full-screen op granted and the PendingIntent's creator and
#                           sender both opted in.
#
# Usage: scripts/grant-wear-prompt-ops.sh [adb-serial]
# With no serial, picks the only attached device, or lists them and stops if there is more than one.

set -euo pipefail

PACKAGE="com.yukarlo.unlockmymac"
OPS=(USE_FULL_SCREEN_INTENT SYSTEM_ALERT_WINDOW)

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || {
    echo "adb not found. Set ADB=/path/to/adb." >&2
    exit 1
}

serial="${1:-}"
if [[ -z "$serial" ]]; then
    # Read into an array the long way: macOS ships bash 3.2, which has no mapfile. Serials can
    # contain spaces — a wirelessly paired watch appears as "adb-XXXX-yyyy (2)._adb-tls-connect._tcp"
    # — so split on newlines only.
    devices=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && devices+=("$line")
    done < <("$ADB" devices | sed -n 's/[[:space:]]*device$//p')

    case "${#devices[@]}" in
        0) echo "No device attached." >&2; exit 1 ;;
        1) serial="${devices[0]}" ;;
        *)
            echo "More than one device attached. Pass the serial of the watch:" >&2
            printf '  %s\n' "${devices[@]}" >&2
            exit 1
            ;;
    esac
fi

"$ADB" -s "$serial" shell pm list packages | grep -qx "package:$PACKAGE" || {
    echo "$PACKAGE is not installed on $serial. Install it first." >&2
    exit 1
}

for op in "${OPS[@]}"; do
    "$ADB" -s "$serial" shell appops set "$PACKAGE" "$op" allow
    # appops set is silent on failure, including when the package does not declare the permission,
    # so read the state back rather than trusting the exit code.
    state="$("$ADB" -s "$serial" shell cmd appops get "$PACKAGE" "$op" | head -1 | tr -d '\r')"
    case "$state" in
        *": allow"*) echo "ok   $op" ;;
        *) echo "FAIL $op -> ${state:-<no state>}" >&2; exit 1 ;;
    esac
done

echo
echo "Granted on $serial. Survives reinstall, not uninstall."
