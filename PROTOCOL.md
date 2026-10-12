# Seestar protocol notes

Read from the decompiled Android app 3.3.0. Items marked (live) were checked on an S50, firmware 8.46.

## Ports

| Port | Use |
|---|---|
| 4700 | JSON-RPC control, one JSON object per line, `\r\n` terminated |
| 4800 / 4804 | Image frames, tele / wide |
| 4554 / 4555 | RTSP video `rtsp://<ip>:<port>/stream`, tele / wide (live: h264 1080x1920 30 fps) |
| 4801 | Album file transfer |
| 4350 | Firmware update |

The scope does not answer the UDP 4720 `scan_iscope` broadcast (live). It announces `SeeStar.local` by mDNS.

## Control channel (4700)

- Request: `{"id":N,"method":"...","params":...}`. Reply carries the same `id`, plus `code` and `result`. Lines without a matching `id` are events (`{"Event":"...",...}`).
- Handshake (live): `get_verify_str` gives `result.str`; reply with `verify_client {"sign": base64(RSA-SHA1 PKCS#1 v1.5 of str), "data": str}`. Code 103 on the first call means old firmware with no handshake. The key is in `libopenssllib.so`.
- Keep-alive: `test_connection`, or any request, at least every few seconds. The scope closes an idle connection (live).
- The scope answers one request at a time across all connections; `get_device_state` takes about 1 s (live).
- Tracking is refused with code 207 while the mount moves (live).
- Codes: 0 ok, 103 method not found, 203 moving, 204 out of limit, 207 failed, 259 stack running, 268 in progress.

| Action | Request |
|---|---|
| Alt/az (live) | `scope_get_horiz_coord {"ret_obj":true}` gives `alt_az:[alt°, az°]` |
| RA/Dec | `scope_get_equ_coord` gives `{ra: hours, dec: degrees}` |
| State (live) | `get_device_state`, optional `{"keys":["mount"]}` |
| View state | `iscope_get_app_state` |
| Open arm (live) | `scope_move_to_horizon` |
| Park | `scope_park` |
| Cancel arm move | `scope_move ["none"]` |
| Tracking | `scope_set_track_state true` |
| Slew (live) | `scope_speed_move {"percent":0..100,"angle":deg,"level":0|1,"dur_sec":3}`, resend every 200 ms, percent 0 stops. Angle 90 = up, 270 = down, 0 = az+, 180 = az- |
| Start view (live) | `iscope_start_view {"mode":"star"|"scenery"|"solar_sys"}` |
| Goto | `iscope_start_view {"mode":"star","selected_cam":"View","target_name":..,"target_ra_dec":[ra_hours, dec_deg],"lp_filter":false}`, JNow |
| Stop view | `iscope_stop_view {}` or `{"stage":"Stack"|"AutoGoto"|...}` |
| Stack | `iscope_start_stack {"restart":true}` |
| Focus | `move_focuser {"step":absolute,"ret_step":false}`, `start_auto_focuse`, `stop_auto_focuse` |
| Exposure | `set_setting {"exp_ms":{"stack_l":10000}}`, `{"exp_ms":{"continuous":500}}`, `{"isp_exp_ms":12.5}`, `{"isp_gain":80.0}` |
| Manual shutter (live) | `set_setting {"manual_exp":true}` then `{"isp_exp_ms":20.0}`; range in `setting.isp_range_exp_us` (30 to 1000000 on the S50) |
| Record / timelapse (live) | `start_record_avi {"raw":false,"timelapse":{"enable":true,"delay_sec":2,"src_dura_min":-1}}`, `-1` = no end; `stop_record_avi`. State in `View.AviRecord` (`saved_frames`, `name`) |
| Time | `pi_set_time [{"year","mon","day","hour","min","sec","time_zone"}]` |
| Location | `set_user_location {"lon","lat","force":false}` |
| Power | `pi_shutdown`, `pi_reboot` |
| Dew heater | `pi_output_set2 {"heater":{"state":bool,"value":90}}` |
| Mount passthrough | `scope_send_cmd` |

Settings the app hides or gates: `expert_mode`, `hdr`, `main_8k`, `frame_calib`, `stack.save_discrete_frame`, `stack.drizzle2x`, `stack_dither`, `auto_master_dark` (Pro models only in the app), `user_stack_sim`. Factory commands (`factory_test_control`, `format_emmc`) are destructive.

## Image channel (4800 / 4804)

No handshake. Commands, fire and forget: `{"method": "begin_streaming", "id": 2}\r\n`, also `stop_streaming`, `get_current_img`, `get_stacked_img`. Heartbeat `{"method": "test_connection", "id": 1}\r\n` every 4 s.

Frame = 80-byte big-endian header + payload (live):

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | magic 0x03C3 |
| 6 | i32 | payload length |
| 12 | u8 | big-endian flag |
| 13 | u8 | image type: 1 preview, 4 JPEG, 5 stacked |
| 14 | u8 | data type: 3 = ZIP archive, first entry is the image |
| 15 | u8 | id: 1 = heartbeat reply |
| 16, 18 | u16 | width, height |

- Preview payload (live): 16-bit little-endian Bayer, `width*height*2` bytes, pattern GRBG on the S50 (`camera.debayer_pattern` = "GR"), 12-bit data shifted left 4.
- With no image available the payload is the text `there is no image` and width/height are 0 (live).
- Star mode uses this channel. Scenery and solar modes use RTSP.
