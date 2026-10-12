# StarDeck

Control apps for ZWO Seestar telescopes. There is a PC app for Windows and Linux and an Android app. Both talk to the telescope directly over Wi-Fi.

Developed and tested on a Seestar S50 with firmware 8.46. The S30 uses the same protocol, but it is not tested yet.

## Contents

- [Download](#download)
- [Install](#install)
- [First connection](#first-connection)
- [Functions](#functions)
- [HUD and burned-in frames](#hud-and-burned-in-frames)
- [GPS (Android)](#gps-android)
- [Shutter](#shutter)
- [Timelapse](#timelapse)
- [JSON output](#json-output)
- [Limits](#limits)
- [If something does not work](#if-something-does-not-work)
- [Build from source](#build-from-source)
- [Repository layout](#repository-layout)
- [Credits and licences](#credits-and-licences)

## Download

Get the files from the [latest release](../../releases/latest).

| File | System |
|---|---|
| `stardeck-windows-x86_64.zip` | Windows 10 or later, 64-bit |
| `stardeck-linux-x86_64.tar.gz` | Linux, 64-bit, glibc 2.31 or later (Ubuntu 20.04, Debian 11, Fedora 32 and later) |
| `stardeck-android-debug.apk` | Android 10 or later |

The PC bundles include `ffmpeg` for the live video. You do not need to install anything else.

## Install

**Windows**

1. Unzip the folder.
2. Double-click `stardeck.exe`. Keep `ffmpeg.exe` in the same folder.
3. If Windows shows a firewall question, allow access on private networks.

**Linux**

1. Unpack: `tar -xzf stardeck-linux-x86_64.tar.gz`
2. Run `./install-linux.sh` in the folder. It copies the app to `~/.local/share/stardeck` and adds a menu entry. It does not need root.
3. Or run `./stardeck` in the folder without installing.

**Android**

1. Copy the APK to the phone and open it.
2. Allow installs from the file manager or browser when Android asks.
3. The app installs as "StarDeck" next to the official Seestar app. You can keep both.

## First connection

1. Turn the telescope on. Use the official app one time to join the telescope to your Wi-Fi network (station mode). The phone or PC must be on the same network. You can also join the device to the telescope's own hotspot.
2. Start StarDeck. The host field holds `SeeStar.local`.
3. Press **Connect**. The pointing data shows when the connection is good.
4. Press **Open** to raise the arm.
5. Press **Scenery**, then **Video**. For the Sun or Moon use **Solar**. For deep sky use **Star**.

**Do not point the telescope at the Sun without the solar filter.** The PC app keeps the Sun button off until you tick "solar filter is fitted". The slew buttons have no such guard.

The official app and StarDeck can be connected at the same time.

## Functions

| Function | Android | PC |
|---|---|---|
| Azimuth, elevation, RA, Dec readout | yes | yes |
| Live video (scenery and solar views) | yes | yes |
| Raw sensor frames (star view) | no | yes |
| HUD: crosshair, azimuth, elevation, location, UTC time | yes | yes |
| Save a frame with the HUD burned in | yes | yes |
| Image flip, horizontal and vertical | yes | yes |
| GPS lock indicator: accuracy and satellites | yes | no GPS on a PC |
| Send location and time to the telescope | GPS location and GPS time | location by hand, PC clock on connect |
| Shutter (exposure) in milliseconds | yes | yes |
| Timelapse frames saved by the app | yes | yes |
| Timelapse recorded on the telescope | yes | yes |
| JSON output of the pointing data | UDP | text lines |
| Slew pad, open arm, park, tracking | yes | yes |
| Focus steps and autofocus | no | yes |
| Object search and goto | yes | yes |
| Sun and Moon targets | no | yes |
| Stack start and stop, stack exposure, gain | no | yes |
| All on/off settings of the telescope | yes | yes |
| Raw command box with reply log | no | yes |

The settings list shows every on/off setting that the telescope reports. This includes settings that the official app does not show, for example `expert_mode`, `drizzle2x`, `save_discrete_frame`, and `frame_calib`.

The slew buttons work only while you hold them.

## HUD and burned-in frames

The HUD is a red crosshair at the centre of the picture and a data block at the bottom left:

```
Az 356.5136°  El 2.6661°
Lat 40.00000°  Lon -105.00000°  ±4 m
2026-10-12 23:45:48.999 UTC (GPS)
```

- **Save with HUD** (PC) and **Save snapshot** (Android, HUD on) write the crosshair and the data block into the pixels of the saved PNG.
- **Save PNG** (PC), or a snapshot with the HUD off, saves the picture without them.
- The flip settings apply to the saved file as well as the display.
- PC: files go into the folder the app starts in. With the Linux menu entry this is `~/Pictures/StarDeck`.
- Android: files go into `Pictures/StarDeck`.

What the numbers are:

- **Az / El** are the motor step counts of the telescope. They start at zero where the arm opens, until the telescope aligns itself on the sky. They are not a surveyed bearing.
- **Lat / Lon** on Android come from the phone GPS. On the PC they are the location stored in the telescope.
- **Time** is the time when the app draws the frame. It is not the time of the exposure. The network and the video decoder add a delay.

## GPS (Android)

The telescope has no GPS receiver. It uses the location and time that an app sends to it.

- The app shows the phone's GPS state: `LOCKED`, the accuracy in metres, and the satellites used and in view.
- The HUD time shows `(GPS)` when it comes from a GPS fix. It shows `(phone clock)` when there is no fix.
- **Send GPS to scope** writes the GPS location and the GPS time to the telescope. The telescope accepts the time in whole seconds.

## Shutter

Type the exposure in milliseconds and press **Set**. The range on the S50 is 0.03 ms to 1000 ms. **Auto** gives the control back to the telescope.

This setting applies to the scenery and solar views. For the star view, the PC app has the stack exposure buttons (10 s to 60 s).

## Timelapse

There are two types. Both use the same two fields: the interval in seconds and the duration in minutes. A duration of 0 means no end.

| Type | Where the result goes | HUD |
|---|---|---|
| Frames saved by the app | PNG files on the PC or phone, one for each interval | burned in when the HUD is on |
| Recorded on the telescope | one mp4 file in the telescope storage (`MyWorks`) | none |

For the frames saved by the app, the video must be on and the app must stay open. On Android, keep the app on the screen.

## JSON output

**PC:** start the app with `--json`. It prints one line for each reading:

```json
{"alt":2.6661,"az":356.5136,"dec":47.07,"ra":3.3525,"utc":"2026-10-12T23:45:48.999Z"}
```

**Android:** type `host:port` in the JSON output field. The app sends each reading there as one UDP datagram, with the fields `alt`, `az`, `ra`, `dec`, and `utc_ms`.

`ra` is in hours. The other angles are in degrees.

Other PC start options: `--video` starts the video, `--timelapse` starts saving frames. An argument without `--` is the host name or IP address.

## Limits

- **No shutter sync to GPS PPS.** The telescope has no PPS input, and its firmware starts each exposure. The best available is the clock set from GPS time, in whole seconds.
- **S30:** not tested. The wide camera (ports 4804 and 4555) is wired in the PC app only, and it is not tested.
- **Video size:** the PC app shows and saves video frames at half size (540 x 960 on the S50).
- **Goto, tracking, Sun and Moon targets** are built from the official app's commands but are not confirmed on a telescope.
- **Android:** this is a debug build. It is not signed for an app store.

## If something does not work

| Problem | What to do |
|---|---|
| Connect fails with `SeeStar.local` | Type the IP address of the telescope. The official app shows it, and so does your router. |
| Connect fails with the IP address | Make sure the device and the telescope are on the same network. The telescope accepts IPv4 only. |
| No video on the PC | Keep `ffmpeg` in the same folder as the app. Start a view (Scenery or Solar) first. |
| Video is one flat colour | The telescope cannot focus on near objects. Point it at something far away, then use the focus buttons. |
| Tracking does not switch on | The telescope refuses tracking while the mount moves. Wait until it stops. |
| A slew button does nothing | Hold the button. A short tap sends almost no movement. |
| The telescope reports azimuth movement but the body does not turn | This is a fault in the telescope drive, not in the app. |

## Build from source

`scripts/build-dist.sh` builds the two PC bundles and the APK into `dist/`. It needs:

- Docker, for the Linux build in a Debian 11 container
- Rust with the `x86_64-pc-windows-gnu` target, and `mingw-w64-gcc`
- the Android SDK and JDK 21

Tests: `cargo test` in `seestar_pc/`, and `./gradlew testDebugUnitTest` in `android/`.

## Repository layout

| Path | Contents |
|---|---|
| `seestar_pc/` | PC app (Rust, egui) |
| `android/` | Android app (Kotlin, Jetpack Compose) |
| `PROTOCOL.md` | Notes on the telescope's network protocol |
| `azel.py` | Small Python client for quick checks |
| `tools/make_catalog.py` | Makes the object catalogue file from the OpenNGC data |
| `scripts/` | Build script, Linux installer, bundle notes |
| `seestar_key.pem` | Key for the telescope's connection handshake. Keep this repository private. |

## Credits and licences

- Object catalogue: OpenNGC by Mattia Verga, CC-BY-SA-4.0, https://github.com/mattiaverga/OpenNGC. The file `catalog.tsv` is derived from it and has the same licence.
- Precession: Meeus, J. (1998). *Astronomical Algorithms* (2nd ed.). Willmann-Bell.
- The PC bundles include FFmpeg, built by https://github.com/BtbN/FFmpeg-Builds under the LGPL, version 2.1 or later. Source: https://ffmpeg.org
