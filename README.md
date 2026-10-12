# StarDeck

Control apps for ZWO Seestar telescopes: a PC app (Linux, Windows) and an Android app.
Developed and tested on a Seestar S50 with firmware 8.46. The S30 uses the same protocol; it is not tested yet.

The phone or PC and the telescope must be on the same Wi-Fi network (station mode), or the device must be joined to the telescope's own hotspot.

## Install

Download the files from the latest release.

| File | Use |
|---|---|
| `stardeck-android-debug.apk` | Android 10 or later. Allow installs from your browser or file manager, then open the file. |
| `stardeck-windows-x86_64.exe` | Windows. Put `ffmpeg.exe` on the PATH for live video. |
| `stardeck-linux-x86_64` | Linux. Needs `ffmpeg` for live video. `chmod +x` the file first. |

## Connect

1. Turn the telescope on and connect it to your network with the official app.
2. Start StarDeck. The host field holds `SeeStar.local`. If the connection fails, type the telescope's IP address.
3. Press Connect. The pointing data shows when the connection is good.
4. Press Scenery (or Star, Solar), then Video.

Do not point the telescope at the Sun without the solar filter.

## Functions

| Function | Android | PC |
|---|---|---|
| Azimuth, elevation, RA, Dec readout | yes | yes |
| Live video | yes | yes |
| HUD: crosshair with azimuth, elevation, location, UTC time | yes | yes |
| Save a frame with the HUD burned in | yes (`Pictures/StarDeck`) | yes (start folder) |
| Image flip, horizontal and vertical | yes | yes |
| GPS lock indicator (accuracy, satellites) | yes | no GPS on a PC |
| Send GPS location and GPS time to the telescope | yes | location by hand, PC clock on connect |
| Shutter (exposure) set in milliseconds, 0.03 to 1000 | yes | yes |
| Timelapse frames: interval in seconds, duration in minutes, 0 = no end | yes | yes |
| Timelapse recorded on the telescope (mp4, no HUD), no end possible | yes | yes |
| JSON output of pointing data | UDP to host:port | `--json` prints lines |
| Slew, open, park, tracking, view modes, focus | yes (no focus) | yes |
| Object search and goto (OpenNGC catalogue) | yes | yes |
| All on/off settings of the telescope, hidden ones included | yes | yes |

PC start options: `--video` starts the video, `--timelapse` starts saving frames, `--json` prints one JSON line for each reading.

## Limits

- The telescope has no GPS receiver and no PPS input. Its firmware starts each exposure. The shutter cannot be locked to GPS PPS. The apps set the telescope clock from GPS time (Android) or the PC clock; the telescope accepts whole seconds only.
- The HUD time is the time when the app draws the frame. It is not the time of the exposure. The network and the video decoder add a delay.
- Azimuth and elevation are the motor step counts of the telescope. They start at zero where the arm opens until the telescope aligns on the sky.
- Android HUD time shows `(GPS)` when it comes from a GPS fix and `(phone clock)` when it does not.
- The S30 wide camera (ports 4804 and 4555) is wired in the PC app but not tested.

## Build

`scripts/build-dist.sh` builds everything into `dist/`. It needs Rust with the `x86_64-pc-windows-gnu` target, `mingw-w64-gcc`, and the Android SDK with JDK 21.

Tests: `cargo test` in `seestar_pc/`, `./gradlew testDebugUnitTest` in `android/`.

`PROTOCOL.md` holds the protocol notes.

## Credits

Object catalogue: OpenNGC by Mattia Verga, CC-BY-SA-4.0, https://github.com/mattiaverga/OpenNGC. The file `seestar_pc/src/catalog.tsv` is derived from it and has the same licence.

Precession: Meeus, J. (1998). Astronomical Algorithms (2nd ed.). Willmann-Bell.
