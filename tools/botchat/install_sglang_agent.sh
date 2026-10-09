#!/bin/sh
# Runs SGLang (MLX backend) for bot chat as a macOS LaunchAgent: starts at login, restarts if it
# exits, listens on 127.0.0.1:30000 (the game container reaches it as host.docker.internal:30000).
# Safe to rerun; it replaces the existing agent. Uninstall:
#   launchctl bootout gui/$(id -u)/local.sglang.botchat && rm ~/Library/LaunchAgents/local.sglang.botchat.plist
#
# SGLANG_SRC: the SGLang checkout to serve from (its python/ goes first on PYTHONPATH)
# SGLANG_PY:  a Python with SGLang's srt_mps dependencies (Torch 2.13, MLX >= 0.32)
set -eu

LABEL="local.sglang.botchat"
SGLANG_SRC="${SGLANG_SRC:-$HOME/Projects/inference/sglang-botchat}"
SGLANG_PY="${SGLANG_PY:-$HOME/Projects/inference/sglang-kvfix/.venv-mps/bin/python}"
MODEL="${BOT_LLM_SERVE_MODEL:-mlx-community/Qwen3-0.6B-4bit}"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/sglang-botchat.log"

[ -x "$SGLANG_PY" ] || { echo "no python at $SGLANG_PY" >&2; exit 1; }
[ -d "$SGLANG_SRC/python/sglang" ] || { echo "no SGLang checkout at $SGLANG_SRC" >&2; exit 1; }
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"

# Memory: 1 GB cap on MLX's freed-buffer cache, a small radix pool (bot chat prompts are ~300
# tokens), and at most 8 requests decoding at once (the game gates bot chat at 8 too).
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$SGLANG_PY</string><string>-m</string><string>sglang.launch_server</string>
    <string>--model-path</string><string>$MODEL</string>
    <string>--host</string><string>127.0.0.1</string><string>--port</string><string>30000</string>
    <string>--mem-fraction-static</string><string>0.1</string>
    <string>--max-running-requests</string><string>8</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PYTHONPATH</key><string>$SGLANG_SRC/python</string>
    <key>SGLANG_USE_MLX</key><string>1</string>
    <key>SGLANG_MLX_CACHE_LIMIT_GB</key><string>1</string>
    <key>HF_HUB_OFFLINE</key><string>1</string>
  </dict>
  <key>WorkingDirectory</key><string>$SGLANG_SRC</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL ($MODEL from $SGLANG_SRC), log: $LOG"
