StarDeck PC app

Windows: unzip the folder and double-click stardeck.exe. Keep ffmpeg.exe in the same folder.
  Windows can show a firewall question at the first start. Allow access on private networks.
Linux:   unpack, then run ./install-linux.sh (adds a menu entry), or run ./stardeck in the folder.
  Needs glibc 2.31 or later (Ubuntu 20.04, Debian 11, Fedora 32 and later).

The PC and the telescope must be on the same network. The host field holds SeeStar.local.
If the connection fails, type the IP address of the telescope.
Saved frames and timelapse folders go into the folder the app starts in.

ffmpeg is included for the live video. It is a build from https://github.com/BtbN/FFmpeg-Builds
under the LGPL, version 2.1 or later. Source: https://ffmpeg.org
Object catalogue: OpenNGC by Mattia Verga, CC-BY-SA-4.0.
