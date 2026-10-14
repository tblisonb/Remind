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

command -v java >/dev/null || { echo "A Java 17+ runtime is required: sudo apt install openjdk-21-jre-headless" >&2; exit 1; }

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
if "$DEST/bin/remind-desktop" status >/dev/null 2>&1; then
    systemctl --user restart remind-desktop.service
    echo "Installed and (re)started. Logs: journalctl --user -u remind-desktop -f"
else
    echo "Installed. Next: remind-desktop setup && systemctl --user start remind-desktop"
fi
