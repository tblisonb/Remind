#!/usr/bin/env bash
# Builds the self-contained desktop package and publishes it as a GitHub release.
# Bump `version` in desktop/build.gradle.kts and commit first, then:
#   deploy/release-desktop.sh
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(sed -nE 's/^version = "(.*)"/\1/p' desktop/build.gradle.kts)
TAG="desktop-v$VERSION"
NAME="remind-desktop-$VERSION-linux-x86_64.tar.gz"
FILE="desktop/build/distributions/$NAME"

[[ -z "$(git status --porcelain)" ]] || { echo "Commit your changes first." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "$TAG already exists; bump the version in desktop/build.gradle.kts." >&2
    exit 1
fi

./gradlew :desktop:test :desktop:bundledDist
(cd "$(dirname "$FILE")" && sha256sum "$NAME" > "$NAME.sha256")

git tag -a "$TAG" -m "Remind desktop $VERSION"
git push origin "$TAG"
gh release create "$TAG" "$FILE" "$FILE.sha256" --title "Remind desktop $VERSION" --notes "$(cat <<NOTES
Self-contained build for x86_64 Linux with KDE Plasma (includes its own Java runtime).

Install or update:

\`\`\`sh
gh release download $TAG --repo tblisonb/Remind --pattern '*.tar.gz*' && sha256sum -c $NAME.sha256 && tar xzf $NAME && ${NAME%.tar.gz}/install.sh
\`\`\`
NOTES
)"
