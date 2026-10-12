#!/usr/bin/env bash
# Installs StarDeck for the current user: files in ~/.local/share/stardeck, a menu entry, no root needed.
set -euo pipefail
src="$(cd "$(dirname "$0")" && pwd)"
dest="$HOME/.local/share/stardeck"
mkdir -p "$dest" "$HOME/.local/share/applications" "$HOME/Pictures/StarDeck"
cp "$src/stardeck" "$src/ffmpeg" "$dest/"
chmod +x "$dest/stardeck" "$dest/ffmpeg"
cat > "$HOME/.local/share/applications/stardeck.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=StarDeck
Comment=Seestar telescope control
Exec=$dest/stardeck
Path=$HOME/Pictures/StarDeck
Terminal=false
Categories=Science;Astronomy;
DESKTOP
echo "Installed. Start StarDeck from the application menu, or run $dest/stardeck"
