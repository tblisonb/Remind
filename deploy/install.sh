#!/usr/bin/env bash
# Installs or updates the Remind server as a system service. From the repo root:
#   ./gradlew :server:installDist && sudo deploy/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."

DIST=server/build/install/remind-server
[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
[[ -x $DIST/bin/remind-server ]] || { echo "Build first: ./gradlew :server:installDist" >&2; exit 1; }
command -v java >/dev/null || { echo "Install a Java runtime first: apt install openjdk-21-jre-headless" >&2; exit 1; }

# Program files: owned by root, so the service can't modify its own code.
rm -rf /opt/remind.new
cp -r "$DIST" /opt/remind.new
chown -R root:root /opt/remind.new
chmod -R u=rwX,go=rX /opt/remind.new
rm -rf /opt/remind.old
if [[ -d /opt/remind ]]; then mv /opt/remind /opt/remind.old; fi
mv /opt/remind.new /opt/remind
rm -rf /opt/remind.old

install -d -m 700 /etc/remind
if [[ ! -s /etc/remind/token ]]; then
    (umask 077 && openssl rand -base64 32 > /etc/remind/token)
    echo "Generated a new access token: sudo cat /etc/remind/token"
fi

install -m 644 deploy/remind.service /etc/systemd/system/remind.service
systemctl daemon-reload
systemctl enable remind.service
systemctl restart remind.service
sleep 2
systemctl --no-pager --lines=5 status remind.service
