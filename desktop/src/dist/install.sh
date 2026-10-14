#!/usr/bin/env bash
# Installs or updates Remind desktop reminders for the current user (no sudo needed).
# Run it from an unpacked build, e.g.:
#   ./gradlew :desktop:installDist && desktop/build/install/remind-desktop/install.sh
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
DEST="$DATA/remind-desktop"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/remind"

if [[ ! -x "$SRC/runtime/bin/java" ]] && ! command -v java >/dev/null; then
    echo "This build has no bundled Java runtime; install Java 17+ (e.g. openjdk-21-jre-headless / jre21-openjdk)." >&2
    exit 1
fi

# Program files
if [[ "$SRC" != "$DEST" ]]; then
    rm -rf "$DEST.new"
    cp -r "$SRC" "$DEST.new"
    rm -rf "$DEST"
    mv "$DEST.new" "$DEST"
fi
mkdir -p "$HOME/.local/bin" "$STATE_DIR"
ln -sfn "$DEST/bin/remind-desktop" "$HOME/.local/bin/remind-desktop"

# Icon and launcher entry (also used by Plasma to group the notifications)
install -Dm644 "$DEST/share/remind.svg" "$DATA/icons/hicolor/scalable/apps/remind.svg"
install -d "$DATA/applications"
cat > "$DATA/applications/remind.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Remind
GenericName=Reminders
Comment=Open your Remind tasks in the browser
Exec=$DEST/bin/remind-desktop open
Icon=remind
Terminal=false
Categories=Utility;
DESKTOP

# Service: runs with the Plasma session
install -d "$UNIT_DIR"
cat > "$UNIT_DIR/remind-desktop.service" <<UNIT
[Unit]
Description=Remind desktop reminders
PartOf=graphical-session.target
After=graphical-session.target

[Service]
ExecStart=$DEST/bin/remind-desktop run
Restart=on-failure
RestartSec=30

# Only needs to read its config and write its task cache.
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=$STATE_DIR
PrivateTmp=yes

[Install]
WantedBy=graphical-session.target
UNIT

systemctl --user daemon-reload
systemctl --user enable remind-desktop.service >/dev/null
CLI="$DEST/bin/remind-desktop"
if "$CLI" status >/dev/null 2>&1; then
    systemctl --user restart remind-desktop.service
    echo "Updated and restarted. Logs: journalctl --user -u remind-desktop -f"
elif [[ -t 0 ]]; then
    # First install from a terminal: connect to the server right away.
    echo "Installed. Now connecting it to your Remind server:"
    "$CLI" setup
    systemctl --user start remind-desktop.service
    echo "Running. Try: $CLI test-notification"
else
    echo "Installed. Next: $CLI setup && systemctl --user start remind-desktop"
fi
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) echo "Note: ~/.local/bin isn't on your PATH, so use the full path: $CLI" ;;
esac
