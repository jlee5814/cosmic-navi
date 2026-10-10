#!/bin/zsh
# Install a LaunchAgent that runs exp_log.py every 5 minutes (EXP per hour measurement).
# macOS blocks LaunchAgents from reading ~/Documents, so the script and EXP table are copied to
# ~/Library/Application Support/cosmic-explog, which also holds exp-log.csv. Rerun after editing exp_log.py.
# Uninstall: launchctl bootout gui/$(id -u)/local.cosmic.explog && rm ~/Library/LaunchAgents/local.cosmic.explog.plist
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/cosmic-explog"
LABEL=local.cosmic.explog
PLIST=~/Library/LaunchAgents/$LABEL.plist
mkdir -p "$APP"
cp "$DIR/exp_log.py" "$APP/exp_log.py"
python3 - "$DIR/../../src/main/java/constants/game/ExpTable.java" "$APP/exp_table.json" <<'EOF'
import json, re, sys
src = open(sys.argv[1]).read()
json.dump([int(x) for x in re.search(r"exp = \{([^}]*)\}", src).group(1).split(",")], open(sys.argv[2], "w"))
EOF
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>$APP/exp_log.py</string></array>
  <key>StartInterval</key><integer>300</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/cosmic-explog.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/cosmic-explog.log</string>
</dict>
</plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
for _ in 1 2 3 4 5; do launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break; sleep 1; done
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL (every 5 min) -> $APP/exp-log.csv"
