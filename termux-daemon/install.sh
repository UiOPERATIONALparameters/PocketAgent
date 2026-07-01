#!/data/data/com.termux/files/usr/bin/bash
#
# PocketAgent Termux Installer
# ============================
#
# One-line install:
#   curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash
#
# What it does:
#   1. Installs Python 3 if not already present
#   2. Downloads daemon.py to ~/.pocketagent/daemon.py
#   3. Generates auth token at ~/.pocketagent/token
#   4. Creates `pocketagent-daemon` command alias
#   5. (Optional) Adds autostart to ~/.bashrc
#   6. Prints the token (the app will scan it as QR or paste it)
#
# After install, run: pocketagent-daemon
# The app will connect automatically (same device, localhost:8765).

# set -e  # removed for verbose error reporting

PA_DIR="$HOME/.pocketagent"
DAEMON_URL="https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/daemon.py"
DAEMON_PATH="$PA_DIR/daemon.py"
TOKEN_PATH="$PA_DIR/token"
BIN_DIR="/data/data/com.termux/files/usr/bin"

# ─── Colors ────────────────────────────────────────────────────────
if [ -t 1 ]; then
    GREEN='\033[0;32m'
    CYAN='\033[0;36m'
    YELLOW='\033[0;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN=''; CYAN=''; YELLOW=''; RED=''; BOLD=''; NC=''
fi

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════╗"
echo "║  PocketAgent Daemon — Installer               ║"
echo "╚══════════════════════════════════════════════╝"
echo -e "${NC}"

# ─── Check we're in Termux ─────────────────────────────────────────
if [ ! -d "/data/data/com.termux/files/usr" ]; then
    echo -e "${RED}Error: This installer must be run inside Termux.${NC}"
    echo "Install Termux from https://f-droid.org/en/packages/com.termux/ first."
    exit 1
fi

# ─── Install Python if missing ────────────────────────────────────
if ! command -v python3 >/dev/null 2>&1; then
    echo -e "${YELLOW}Python 3 not found. Installing...${NC}"
    pkg update -y
    pkg install -y python
fi

PYTHON_VERSION=$(python3 --version 2>&1)
echo -e "${GREEN}✓${NC} $PYTHON_VERSION"

# ─── Create ~/.pocketagent/ ───────────────────────────────────────
mkdir -p "$PA_DIR"

# ─── Download daemon.py ───────────────────────────────────────────
echo -e "${CYAN}Downloading daemon...${NC}"
if command -v curl >/dev/null 2>&1; then
    curl -sL "$DAEMON_URL" -o "$DAEMON_PATH"
elif command -v wget >/dev/null 2>&1; then
    wget -qO "$DAEMON_PATH" "$DAEMON_URL"
else
    echo -e "${YELLOW}Installing curl...${NC}"
    pkg install -y curl
    curl -sL "$DAEMON_URL" -o "$DAEMON_PATH"
fi

if [ ! -s "$DAEMON_PATH" ]; then
    echo -e "${RED}Failed to download daemon.py${NC}"
    exit 1
fi
chmod 644 "$DAEMON_PATH"
echo -e "${GREEN}✓${NC} daemon.py installed"

# ─── Generate auth token (if missing) ─────────────────────────────
if [ ! -f "$TOKEN_PATH" ]; then
    python3 -c "import secrets; print(secrets.token_urlsafe(32), end='')" > "$TOKEN_PATH"
    chmod 600 "$TOKEN_PATH"
    echo -e "${GREEN}✓${NC} Auth token generated"
else
    echo -e "${GREEN}✓${NC} Auth token exists (preserved)"
fi

# ─── Create `pocketagent-daemon` command ──────────────────────────
cat > "$BIN_DIR/pocketagent-daemon" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec python3 "$DAEMON_PATH" "\$@"
EOF
chmod 755 "$BIN_DIR/pocketagent-daemon"
echo -e "${GREEN}✓${NC} Command installed: pocketagent-daemon"

# ─── Optional: autostart hook ─────────────────────────────────────
AUTOSTART_MARKER="# pocketagent-daemon-autostart"
if ! grep -q "$AUTOSTART_MARKER" "$HOME/.bashrc" 2>/dev/null; then
    echo ""
    echo -e "${CYAN}Autostart daemon when Termux opens? (recommended) [Y/n]${NC}"
    read -r response
    if [ "$response" = "" ] || [ "$response" = "y" ] || [ "$response" = "Y" ]; then
        cat >> "$HOME/.bashrc" <<EOF

$AUTOSTART_MARKER
# Start PocketAgent daemon in the background if not running
if ! pgrep -f "daemon.py" >/dev/null 2>&1; then
    nohup pocketagent-daemon >/dev/null 2>&1 &
    disown
fi
EOF
        echo -e "${GREEN}✓${NC} Autostart added to ~/.bashrc"
    else
        echo -e "${YELLOW}Skipped autostart. Run 'pocketagent-daemon' manually when needed.${NC}"
    fi
fi

# ─── Done ─────────────────────────────────────────────────────────
TOKEN=$(cat "$TOKEN_PATH")
echo ""
echo -e "${GREEN}${BOLD}✓ Installation complete!${NC}"
echo ""
echo -e "${CYAN}Next steps:${NC}"
echo -e "  1. Open the ${BOLD}PocketAgent${NC} app on your phone"
echo -e "  2. The app will detect the daemon automatically"
echo -e "  3. If asked for a token, paste this:"
echo ""
echo -e "     ${BOLD}${TOKEN}${NC}"
echo ""
echo -e "${CYAN}To start the daemon manually:${NC}"
echo -e "  pocketagent-daemon"
echo ""
echo -e "${CYAN}To stop:${NC}"
echo -e "  pkill -f daemon.py"
echo ""
echo -e "${CYAN}To uninstall:${NC}"
echo -e "  rm -rf ~/.pocketagent $BIN_DIR/pocketagent-daemon"
echo ""
