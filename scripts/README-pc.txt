StarDeck PC app

Windows: unzip the folder and double-click stardeck.exe. Keep ffmpeg.exe in the same folder.
  Windows can show a firewall question at the first start. Allow access on private networks.
Linux:   unpack, then run ./install-linux.sh (adds a menu entry), or run ./stardeck in the folder.
  Needs glibc 2.31 or later (Ubuntu 20.04, Debian 11, Fedora 32 and later).

The PC and the telescope must be on the same network. The host field holds SeeStar.local.
If the connection fails, type the IP address of the telescope.
In the field: join the telescope's hotspot in the PC's Wi-Fi settings. When SeeStar.local does not
answer, the app connects to the Wi-Fi gateway, which is the telescope. The Field section shows the
hotspot name and password; "Bluetooth scan" finds telescopes nearby and reads them over Bluetooth.
Photo and Video follow the Storage choice. HUD copies for the telescope go to its share
"EMMC Images", folder MyWorks\StarDeck.
Saved frames and timelapse folders go into the folder the app starts in.

ffmpeg is included for the live video. It is a build from https://github.com/BtbN/FFmpeg-Builds
under the LGPL, version 2.1 or later. Source: https://ffmpeg.org
Object catalogue: OpenNGC by Mattia Verga, CC-BY-SA-4.0.

Web version: run stardeck-bridge (Windows: stardeck-bridge.exe) and open http://127.0.0.1:4791/
or https://alanspaceaudits.github.io/stardeck/ . Add --lan to use the page from a phone or tablet
on the same network (open http://<this computer's address>:4791/ there).
