#!/data/data/com.termux/files/usr/bin/bash
# Short alias for the v6 Termux installer.
# Usage:  curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/install.sh | bash
# This wrapper exists only so the URL is shorter. It downloads and runs the real installer.
set -e
echo "PocketAgent installer — fetching real installer..."
INSTALLER_URL="https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh"
# Download to a temp file, then run it (avoids process substitution issues in some shells)
TMPFILE=$(mktemp /tmp/pa-install.XXXXXX.sh) 2>/dev/null || TMPFILE="$HOME/.pocketagent-install-$$.sh"
curl -sL "$INSTALLER_URL" -o "$TMPFILE"
if [ ! -s "$TMPFILE" ]; then
    echo "ERROR: Failed to download installer from $INSTALLER_URL"
    echo "Check your internet connection and try again."
    rm -f "$TMPFILE"
    exit 1
fi
bash "$TMPFILE"
EXIT_CODE=$?
rm -f "$TMPFILE"
exit $EXIT_CODE
