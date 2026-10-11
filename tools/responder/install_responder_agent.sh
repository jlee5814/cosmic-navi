#!/bin/zsh
# Install a LaunchAgent that runs the navi responder every 5 minutes. It calls a model only when the game
# server lists an open incident (/api/fleetops/incidents), so a quiet fleet costs nothing.
# macOS blocks LaunchAgents from reading ~/Documents, so the script is copied to
# ~/Library/Application Support/cosmic-responder, which also keeps runs/log.jsonl. Rerun after editing it.
# Dry run first:  tools/responder/install_responder_agent.sh --dry-run
# Uninstall: launchctl bootout gui/$(id -u)/local.cosmic.responder && rm ~/Library/LaunchAgents/local.cosmic.responder.plist
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/cosmic-responder"
LABEL=local.cosmic.responder
PLIST=~/Library/LaunchAgents/$LABEL.plist
EXTRA=""
[ "${1:-}" = "--dry-run" ] && EXTRA="<string>--dry-run</string>"
mkdir -p "$APP"
cp "$DIR/responder.py" "$APP/responder.py"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>$APP/responder.py</string>$EXTRA</array>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>HOME</key><string>$HOME</string>
  </dict>
  <key>StartInterval</key><integer>300</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/cosmic-responder.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/cosmic-responder.log</string>
</dict>
</plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
for _ in 1 2 3 4 5; do launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break; sleep 1; done
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL (every 5 min${EXTRA:+, dry run}) -> $APP/runs/log.jsonl"
