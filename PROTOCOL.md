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
| Focus | `move_focuser {"step":absolute,"ret_step":false}`, `start_auto_focuse`, `stop_auto_focuse`. Range (live): `get_device_state {"keys":["focuser"]}` gives `{step, max_step, state}`, about 3 ms |
| Factory focus | `reset_factory_focal_pos {"wide_cam":false}`, result `focal_pos` (`ResetFocalPositionParams`). Position in `setting.factory_focal_pos` (live) |
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

## Album

Classes in the official app: `GetAlbumsParams`, `GetAlbumsResult`, `GetAlbumsGroupResult`, `GetAlbumsFileResult` (`com.zwo.seestar.kit.cmd`), `GetAlbumsCmd`, `EnumAlbumCellType`, `AlbumCellBean`, `AlbumService`, `OkHttpAlbumDownloader`, `AlbumFileSocketManager`, `RemotePhotoEntity`, `RemoteBigPhotoEntity`, `AlbumBigCellBean`.

List (4700):

- `get_albums` with no params lists the top folders (live): `{"path":"MyWorks","list":[{"group_name":"Scenery","files":[{"name":"Scenery_video","thn":"Scenery_video/..._thn.jpg","count":9,"type":0}]},{"group_name":"DeepSky",...}]}`.
- `get_albums {"name":"MyWorks/<folder>"}` lists a folder (live). The name must include `MyWorks/`; a bare folder name gives an empty list. Optional `filter`.
- Folder reply (live): `path` = the folder; `list` = groups by date, `group_name` "2026-05-31"; each file has `name`, `thn`, `type`, `date` ("2026-05-31 23:24:40"), `is_favorite`, videos `duration_sec`. Some files have `cant_stack`. No file size.
- File path = `path + "/" + name`; thumbnail = `path + "/" + thn`, for folders too.
- `type` (`EnumAlbumCellType`): 0 folder, 1 single FITS, 2 live-stacked FITS, 3 post-stacked FITS, 4 video-stacked FITS, 5 mp4, 6 mp4 with AVI, 7 time-lapse mp4, 8 time-lapse mp4 with AVI, 9 other, 10 JPG saved from video, 11 metering FITS. Seen live: 0, 2, 5, 7, 10.

Download (HTTP, port 80):

- `http://<ip>/<path>`, each path segment URL-encoded with spaces as `%20` (`AlbumService.encodeUrlPath`). Files and thumbnails (live). The official downloader adds `?task_id=N` and resumes with `Range`.
- `HEAD` gives `Content-Length` (live); `Accept-Ranges: bytes` (live). A missing file gives 404 (live).
- The scope's files sit under `/boot/Image/` (seen in the `ETag`, live).

File port (4801, `AlbumFileSocketManager`):

- No handshake. Send one line `{"id":99,"method":"...","params":{"name":"<path>"}}\r\n`.
- Methods: `get_img_thumbnail` (`RemotePhotoEntity`), `get_img_file_in_jpg` (`RemoteBigPhotoEntity`, `AlbumBigCellBean`): a FITS file rendered as JPEG.
- Reply: the 80-byte frame header of the image channel, then the payload (live). `id` echoes 99; `id` 1 is a heartbeat; a 4-byte payload is skipped by the app.
- Data type 2: the file as is (live: `get_img_thumbnail` of a stacked FITS gave a 148x264 JPEG, `get_img_file_in_jpg` a 1080x1920 JPEG, image type 4). Data type 1: failure, payload is text, e.g. `file not exist` (live). Data type 3: ZIP archive (image channel).

## Observation plan

Classes in the official app: `SetViewPlanCmd`, `ReStartViewPlanCmd`, `ClearViewPlanCmd`, `StopFuncCmd` (`com.zwo.seestar.socket.command`), `ViewPlan`, `PlanEntity`, `PlanTarget`, `PlanMosaic`, `PlanOutputFile`, `PlanFile` (`com.zwo.seestar.kit.data`, from `ViewPlan.kt`), `AstroPlanViewModel.actionPlan`, `DeviceEventPart.processViewPlanEvent`, `PlanViewModel` (auto plan via `com.zwo.plancapture`). Not tried on a scope yet.

- Send and start: `set_view_plan` with the plan as params (`AstroPlanViewModel.actionPlan`, called when the user starts a plan):
  `{"plan_name":"..","update_time_seestar":"yyyy-MM-dd","list":[{"lp_filter":false,"target_id":123456789,"target_ra_dec":[ra_hours,dec_deg],"target_name":"M 31","alias_name":"NGC 224","start_min":1380,"duration_min":60,"mosaic":{"scale":1.5,"angle":30,"star_map_angle":0}}]}`.
  The list is sorted by `start_min`. `mosaic` is left out when scale is 1 and both angles are 0. `stack_total_sec` is in `PlanTarget` but `SetViewPlanCmd` does not send it.
- `start_min`: minutes from local midnight of the plan day; times after midnight get +1440 (`PlanViewModel.toPlanStartMin`). Display is `(start_min/60)%24`.
- `target_ra_dec`: J2000 (`PlanViewModel` converts JNow to J2000 with `getJNowToJ2000` before it builds a target). A goto uses coordinates of date.
- `target_id`: `(currentTimeMillis % 1000000) * 1000 + n` (`PlanService.generateShortTargetId`, `PlanViewModel.generateAutoPlanTargetId`).
- Restart the stored plan: `set_view_plan` with no params (`ReStartViewPlanCmd`). Code 268 = operation in progress.
- Stop: `stop_func {"name":"ViewPlan"}` (`BaseDeviceViewModel`, `AstroPlanViewModel`).
- Delete the stored plan: `clear_view_plan`, no params (`ClearViewPlanCmd`).
- Read back: `iscope_get_app_state` result key `ViewPlan` (`AppStateData`): `{"state":..,"lapse_ms":..,"plan":{"plan_name","update_time_seestar","list":[PlanTarget]}}`. Each `PlanTarget` can also carry `state`, `code`, `error`, `output_file {"path","files":[{"name","thn","date","type"}]}`, `stack_total_sec`.
- Events: `ViewPlan` with the same object as above; `Target` with `target_id`, `state`, `code`, `output_file`, `stack_total_sec` for one plan target (`DeviceEventPart.processPlanTargetEvent`).
- States (`DeviceEventPart.switchState`, `PlanUtil.getPlanStatus`): `idle`, `working`, `complete`, `cancel`, `fail`.
- Park after the plan: `set_setting {"viewplan_gohome": true}` (`SetSettingParams`, `SeestarSetting`).
- Constants with no call site in this build: `get_view_plan`, `set_plan`, `get_plan`, `list_plan`, `import_plan`, `get_enabled_plan`, `reset_plan`, `delete_plan`, `clear_plan` (`MainCameraConstants`).

## Mosaic

Classes: `StartViewParams`, `StartGotoMosaicParams` (`com.zwo.seestar.kit.cmd`), `SeestarSettingMosaic`, `MosaicData`, `SkyViewModel`, `SkyViewDelegate.clickGoto`, `StarMosaicControlPop`, `GetSettingCmd`.

- Goto with mosaic: `iscope_start_view {"mode":"star","selected_cam":"View","target_name":..,"target_ra_dec":[ra_hours,dec_deg],"lp_filter":false,"mosaic":{"scale":1.5,"angle":30,"star_map_angle":12.3}}` (`SkyViewDelegate.clickGoto` to `BaseDeviceViewModel.startStarMode`). Coordinates of date. The `mosaic` object is sent only when scale is not 1 or angle is not 0 (`MosaicData.isMosaic`). `selected_cam` is `View` (telescope) or `SecondView` (wide).
- Ranges (`StarMosaicControlPop`): scale 1.0 to 2.0 in steps of 0.1; angle -90 to 90° in steps of 1° for the telescope camera; 0 or 90° for the wide camera.
- `star_map_angle` comes from the sky-map renderer (`GetSeestarTargetObjAngle`, native). Its meaning is not confirmed; StarDeck sends 0.
- The scope reports the active mosaic in `get_device_state` `setting.mosaic` (`second_camera` setting for the wide camera): `scale`, `angle`, `star_map_angle`, `star_map_ratio`, `estimated_hours` (`GetSettingCmd`).

## Network, calibration, versions

From the decompiled app 3.3.0; not yet checked live. Class names are the evidence.

| Action | Request | Evidence |
|---|---|---|
| Station state | `pi_station_state`, result `{server, ssid, ip, gateway, netmask, freq, sig_lev, key_mgmt}`. Joined when `server` and `gateway` is set | `PiStationStateCmd`, `PiStationStateSelectedBean`, `StaLoopAction`, `BaseDeviceViewModel.refreshStation` |
| Scan | `pi_station_scan`, result list of `{ssid, freq, sig_lev, flags, ip, gateway, netmask}`. Open network when `flags` is exactly `[ESS]` | `GetStationScanCmd`, `GetWifiInfosData`, `WifiInfoData`, `StaConnectedEntity` |
| Saved networks | `pi_station_list`, result list of SSID strings or `{ssid}` objects | `GetSavedStationListCmd.parseStationArray` |
| Join new | `pi_station_set {"ssid","psk","hidden":false,"timeout_sec":25}`, `psk` left out for an open network; `hidden:true` for a network typed in by hand | `PiStationSetParams`, `PiStationSetCmd`, `BaseDeviceViewModel.setStationNetwork`, `DeviceStaFragment.handleAddNetwork` |
| Join saved | `pi_station_select {"ssid"}` | `PiStationSelectCmd`, `DeviceManageAcViewModel` |
| Forget | `pi_station_remove {"ssid"}` | `PiStationRemoveCmd`, `StaRemoveAction` |
| Station on / off | `pi_station_open`, `pi_station_close` (no caller found in 3.3.0) | `PiStationOpenCmd`, `PiStationCloseCmd`, `PiStationSetStateAction` |
| Hotspot | `get_device_state` key `ap`: `{ssid, passwd, is_5g}`. Change: `pi_set_ap [ssid, passwd]`; read: `pi_get_ap` | `SeestarAp`, `BaseDeviceViewModel.updateAP`, `MainCameraConstants.CMD_GET_AP_INFO` |
| Wi-Fi country | `set_wifi_country {"country":"US"}`, ISO 3166 two-letter code, `""` = default. Current value `setting.wifi_country`. Code list: `assets/wifi_country.txt` (`lang,code,name`) | `SetWifiCountryParams`, `SettingViewModel.setWifiCountryCode`, `SeestarSetting` |
| Dark library | `start_create_dark`, no params; `{"wide_cam":true}` for the wide camera. Stop: `iscope_stop_view {"stage":"DarkLibrary"}`. Progress `DarkLibrary {state, percent, route}` in `View` or `View.Initialise` | `StartCreateDarkParams`, `BaseDeviceViewModel.setStartCreateDark`, `stopDarkLibrary`, `ViewDarkLibrary` |
| 3PPA | Runs in star-mode initialise when `set_setting {"auto_3ppa_calib":true}`. Stop: `iscope_stop_view {"stage":"3PPA"}`. Progress `3PPA {state, percent, calib_fail_autogoto, route}` in `View` or `View.Initialise`; event also has `has_offset` | `SetSettingParams`, `BaseDeviceViewModel.setAuto3ppa`, `stop3PPA`, `View3PPA`, `DeviceEventPart.process3ppaEvent` |
| EQ polar alignment | `start_polar_align {"restart":true,"dec_pos_index":1..5}` (default 3), later `{"restart":false}` to measure again; `stop_polar_align`; `clear_polar_align`. Result in event `EqModePA {state, code, x_arcsec, y_arcsec, x, y}`; the app shows aligned when abs(x) and abs(y) stay at most 1 for 5 s | `StartPolarAlignResetCmd`, `EQModeChangeViewModel`, `PitchAngleEqBottomPopView`, `StopPolarAlignCmd`, `PolarAlignPart`, `EQModeChangeViewModel$property$2$1` |
| EQ mode switch | `scope_park {"equ_mode":bool}`, after `stop_polar_align`. Mode in `get_device_state` `mount.equ_mode` | `ParkMountAndSwitchEqCmd`, `SeestarMount` |
| Polar check | `check_pa_alt`, result `{is_valid}`; asked before a goto in EQ mode only | `CheckPaAltResult`, `GotoViewModel.checkPaAlt` |
| Compass | `start_compass_calibration`, `stop_compass_calibration` (kit sends no params; the older `CompassCalibrationStopCmd` sends `{"force":0}`), `get_compass_state` result `{number}` | `BaseDeviceViewModel.startCompassSensor`, `stopCompassSensor`, `CompassResult` |
| Level sensor | `start_gsensor_calibration` | `BaseDeviceViewModel.startSensorCalibration` |
| Versions | `get_device_state` key `device`: `firmware_ver_int`, `firmware_ver_string`, `svr_ver_int`, `svr_ver_string`, `ios_ver`, `firmware_platform`. Update prompt when `firmware_ver_int < 2846` ("8.46"), forced below 2626; only for the master client (`client.is_master` not false) | `SeestarDevice`, `DeviceStateData.getNeedFwUpgrade`, `getNeedForceFwUpgrade`, `getFwNeedUpdateVersion`, `ZConfig.firmwareAuthenticationChange` |
