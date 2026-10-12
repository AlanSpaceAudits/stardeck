mod catalog;
mod hud;
mod proto;
mod stream;

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use eframe::egui;
use serde_json::{json, Value};

#[derive(Default)]
struct Shared {
    status: String,
    connected: bool,
    alt_az: Option<(f64, f64)>,
    ra_dec: Option<(f64, f64)>,
    device: Value,
    log: VecDeque<String>,
    frame: Option<stream::Frame>,
    frame_info: String,
    knobs: stream::Knobs,
    /// UI is holding a slew button; the poller keeps its requests light meanwhile.
    slewing: bool,
}

impl Shared {
    fn log(&mut self, s: String) {
        if self.log.len() >= 500 {
            self.log.pop_front();
        }
        self.log.push_back(s);
    }
}

/// The data block shown on the HUD and burned into saved frames.
fn hud_lines(s: &Shared) -> Vec<String> {
    let here = &s.device["location_lon_lat"];
    vec![
        format!(
            "Az {}  El {}",
            s.alt_az.map_or("--".into(), |(_, az)| format!("{az:.4}°")),
            s.alt_az.map_or("--".into(), |(alt, _)| format!("{alt:.4}°"))
        ),
        format!(
            "Lat {}  Lon {}",
            here[1].as_f64().map_or("--".into(), |v| format!("{v:.5}°")),
            here[0].as_f64().map_or("--".into(), |v| format!("{v:.5}°"))
        ),
        format!("{} UTC", chrono::Utc::now().format("%Y-%m-%d %H:%M:%S%.3f")),
    ]
}

struct Cmd {
    method: String,
    params: Option<Value>,
}

/// Polls pointing and device state on its own connection, so slow status calls
/// (get_device_state takes about a second) never delay commands.
fn poller(host: String, shared: Arc<Mutex<Shared>>, ctx: egui::Context, stop: Arc<AtomicBool>, refresh: Arc<AtomicBool>) {
    let Ok(mut scope) = proto::Scope::connect(&host) else { return };
    let mut last_state = Instant::now() - Duration::from_secs(60);
    let telemetry = std::env::args().any(|a| a == "--json");
    while !stop.load(Ordering::Relaxed) {
        let Ok(state) = scope.call("scope_get_state", None) else { break };
        // the scope answers one request at a time across all connections, so a slow
        // get_device_state here would stall slew commands
        let slewing = shared.lock().unwrap().slewing;
        let device = if !slewing && (refresh.swap(false, Ordering::Relaxed) || last_state.elapsed() > Duration::from_secs(10)) {
            last_state = Instant::now();
            scope.call("get_device_state", None).ok().map(|r| r["result"].clone())
        } else {
            None
        };
        let events: Vec<Value> = scope.events.drain(..).collect();
        if telemetry {
            let r = &state["result"];
            println!("{}", json!({"utc": chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true), "az": r["az"], "alt": r["alt"], "ra": r["ra"], "dec": r["dec"]}));
        }
        {
            let mut s = shared.lock().unwrap();
            let r = &state["result"];
            s.alt_az = r["alt"].as_f64().zip(r["az"].as_f64());
            s.ra_dec = r["ra"].as_f64().zip(r["dec"].as_f64());
            if let Some(d) = device {
                s.device = d;
            }
            for e in &events {
                s.log(format!("* {e}"));
            }
        }
        ctx.request_repaint();
        std::thread::sleep(Duration::from_millis(400));
    }
}

fn worker(host: String, rx: mpsc::Receiver<Cmd>, shared: Arc<Mutex<Shared>>, ctx: egui::Context) {
    let set = |f: &dyn Fn(&mut Shared)| {
        f(&mut shared.lock().unwrap());
        ctx.request_repaint();
    };
    set(&|s| s.status = format!("connecting to {host}"));
    let mut scope = match proto::Scope::connect(&host) {
        Ok(s) => s,
        Err(e) => return set(&|s| s.status = format!("connect failed: {e}")),
    };
    set(&|s| {
        s.connected = true;
        s.status = format!("connected to {host}");
    });
    // The scope has no battery-backed clock; goto and tracking need the current time.
    {
        use chrono::{Datelike, Timelike};
        let n = chrono::Local::now();
        let tz = iana_time_zone::get_timezone().unwrap_or_else(|_| "UTC".into());
        let t = json!([{"year": n.year(), "mon": n.month(), "day": n.day(), "hour": n.hour(), "min": n.minute(), "sec": n.second(), "time_zone": tz}]);
        if let Ok(r) = scope.call("pi_set_time", Some(t)) {
            set(&|s| s.log(format!("> pi_set_time\n< {r}")));
        }
    }
    let stop = Arc::new(AtomicBool::new(false));
    let refresh = Arc::new(AtomicBool::new(true));
    {
        let (host, shared, ctx, stop, refresh) = (host.clone(), shared.clone(), ctx.clone(), stop.clone(), refresh.clone());
        std::thread::spawn(move || poller(host, shared, ctx, stop, refresh));
    }

    let err = loop {
        let cmd = match rx.recv_timeout(Duration::from_secs(2)) {
            Ok(cmd) => cmd,
            Err(RecvTimeoutError::Disconnected) => break "disconnected".to_string(),
            Err(RecvTimeoutError::Timeout) => Cmd { method: "test_connection".into(), params: None },
        };
        let quiet = cmd.method == "scope_speed_move" || cmd.method == "test_connection";
        match scope.call(&cmd.method, cmd.params.clone()) {
            Ok(r) if quiet && r["code"] == 0 => {}
            Ok(r) => set(&|s| s.log(format!("> {} {}\n< {r}", cmd.method, cmd.params.clone().unwrap_or(Value::Null)))),
            Err(e) => break e.to_string(),
        }
        if !quiet {
            refresh.store(true, Ordering::Relaxed);
        }
        scope.events.clear(); // the poller's connection logs events
    };
    stop.store(true, Ordering::Relaxed);
    set(&|s| {
        s.connected = false;
        s.status = err.clone();
    });
}

// scope_speed_move angles
const SLEW_UP: i64 = 90;
const SLEW_DOWN: i64 = 270;
const SLEW_LEFT: i64 = 180;
const SLEW_RIGHT: i64 = 0;

struct App {
    host: String,
    shared: Arc<Mutex<Shared>>,
    tx: Option<mpsc::Sender<Cmd>>,
    raw_method: String,
    raw_params: String,
    img_tx: Option<mpsc::Sender<&'static str>>,
    wide: bool,
    texture: Option<egui::TextureHandle>,
    video: Option<std::process::Child>,
    auto_video: bool,
    slew_percent: i64,
    slew_fast: bool,
    slewing: bool,
    last_slew: Instant,
    goto_name: String,
    goto_ra: String,
    goto_dec: String,
    solar_filter_on: bool,
    last_frame: Option<stream::Frame>,
    loc_lat: String,
    loc_lon: String,
    search: String,
    hud: bool,
    flip_h: bool,
    flip_v: bool,
    /// on-screen rectangle of the view, for cropping HUD screenshots
    view_rect: egui::Rect,
    exp_ms: f64,
    lapse_interval_s: f64,
    /// 0 = run until stopped
    lapse_minutes: f64,
    lapse: Option<Timelapse>,
}

/// App-side interval capture: saves a frame (with the HUD burned in when it is on) every interval.
struct Timelapse {
    dir: std::path::PathBuf,
    started: Instant,
    next: Instant,
    saved: u32,
}

impl App {
    fn send(&self, method: &str, params: Option<Value>) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(Cmd { method: method.into(), params });
        }
    }
}

impl App {
    /// Saves the newest frame as PNG, flipped as displayed, with the HUD burned in when `lines` is given.
    fn save_png(&self, path: &std::path::Path, lines: Option<&[String]>) -> Result<(), String> {
        let f = self.last_frame.as_ref().ok_or("no frame to save")?;
        let mut rgba = f.rgba.clone();
        hud::flip(&mut rgba, f.width, f.height, self.flip_h, self.flip_v);
        if let Some(lines) = lines {
            hud::burn(&mut rgba, f.width, f.height, lines);
        }
        image::save_buffer(path, &rgba, f.width as u32, f.height as u32, image::ColorType::Rgba8).map_err(|e| e.to_string())
    }

    fn connect(&mut self, ctx: &egui::Context) {
        let (tx, rx) = mpsc::channel();
        self.tx = Some(tx);
        let (host, shared, ctx) = (self.host.clone(), self.shared.clone(), ctx.clone());
        std::thread::spawn(move || worker(host, rx, shared, ctx));
    }

    /// Sends a command on the image channel, opening it on first use.
    fn image_cmd(&mut self, method: &'static str, ctx: &egui::Context) {
        if self.img_tx.is_none() {
            let (shared, ctx) = (self.shared.clone(), ctx.clone());
            let port = if self.wide { stream::WIDE_PORT } else { stream::TELE_PORT };
            let on_frame = move |f: proto::Res<(stream::Header, Vec<u8>)>| {
                let knobs = shared.lock().unwrap().knobs;
                let decoded = f.and_then(|(h, payload)| stream::decode(&h, &payload, knobs));
                let mut s = shared.lock().unwrap();
                match decoded {
                    Ok(frame) => {
                        s.frame_info = frame.info.clone();
                        s.frame = Some(frame);
                    }
                    Err(e) => s.frame_info = format!("image channel: {e}"),
                }
                ctx.request_repaint();
            };
            // ponytail: connects on the UI thread (blocks up to 5 s if the scope is unreachable)
            match stream::spawn(&self.host, port, on_frame) {
                Ok(tx) => self.img_tx = Some(tx),
                Err(e) => return self.shared.lock().unwrap().frame_info = format!("image channel: {e}"),
            }
        }
        if self.img_tx.as_ref().is_some_and(|tx| tx.send(method).is_err()) {
            self.img_tx = None;
        }
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let shared = self.shared.clone();
        let mut s = shared.lock().unwrap();
        if let Some(f) = s.frame.take() {
            let img = egui::ColorImage::from_rgba_unmultiplied([f.width, f.height], &f.rgba);
            self.texture = Some(ctx.load_texture("frame", img, Default::default()));
            self.last_frame = Some(f);
        }
        let mut image_cmd = None;
        let mut save_frame = None;
        let lines = hud_lines(&s);
        let mut out: Vec<(&str, Option<Value>)> = Vec::new();
        let mut held_angle = None;
        let mut toggle_video = std::mem::take(&mut self.auto_video);

        egui::Panel::top("top").show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label("Host");
                ui.text_edit_singleline(&mut self.host);
                if s.connected {
                    if ui.button("Disconnect").clicked() {
                        self.tx = None;
                    }
                } else if ui.button("Connect").clicked() {
                    self.connect(&ctx);
                }
                ui.label(&s.status);
            });
        });

        egui::Panel::left("left").min_size(260.0).show(ui, |ui| {
            ui.heading("Pointing");
            let fmt = |v: Option<(f64, f64)>, a: &str, b: &str| match v {
                Some((x, y)) => format!("{a} {x:9.4}   {b} {y:9.4}"),
                None => format!("{a} --   {b} --"),
            };
            ui.monospace(fmt(s.alt_az.map(|(alt, az)| (az, alt)), "Az", "El"));
            ui.monospace(fmt(s.ra_dec, "RA", "Dec"));
            ui.separator();

            ui.heading("Arm");
            ui.horizontal(|ui| {
                if ui.button("Open").clicked() {
                    out.push(("scope_move_to_horizon", None));
                }
                if ui.button("Park").clicked() {
                    out.push(("scope_park", None));
                }
                if ui.button("Cancel move").clicked() {
                    out.push(("scope_move", Some(json!(["none"]))));
                }
            });
            let mut tracking = s.device["mount"]["tracking"].as_bool().unwrap_or(false);
            if ui.checkbox(&mut tracking, "tracking").changed() {
                out.push(("scope_set_track_state", Some(json!(tracking))));
            }

            ui.heading("View");
            ui.horizontal(|ui| {
                for (label, mode) in [("Star", "star"), ("Scenery", "scenery"), ("Solar", "solar_sys")] {
                    if ui.button(label).clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": mode}))));
                    }
                }
                if ui.button("Stop view").clicked() {
                    out.push(("iscope_stop_view", Some(json!({}))));
                }
            });
            ui.horizontal(|ui| {
                if ui.button("Start stack").clicked() {
                    out.push(("iscope_start_stack", Some(json!({"restart": true}))));
                }
                if ui.button("Stop stack").clicked() {
                    out.push(("iscope_stop_view", Some(json!({"stage": "Stack"}))));
                }
            });
            // Scenery and solar views are served as RTSP video, not on the image port.
            if ui.button(if self.video.is_some() { "Stop video" } else { "Video" }).clicked() {
                toggle_video = true;
            }

            ui.heading("Goto (JNow)");
            ui.add(egui::TextEdit::singleline(&mut self.search).hint_text("search: M 31, NGC 7000, Orion Nebula"));
            ui.small("catalogue: OpenNGC, CC-BY-SA-4.0");
            for o in catalog::search(&self.search, 6) {
                let alias = o.other.split('|').next().unwrap_or("");
                let label = format!("{} {}  {}  mag {}", o.name, alias, o.kind, if o.mag.is_empty() { "?" } else { o.mag });
                if ui.selectable_label(false, label).clicked() {
                    // the scope expects coordinates of date, the catalogue is J2000
                    let (ra, dec) = catalog::precess_from_j2000(o.ra_hours, o.dec_deg, catalog::julian_date_now());
                    self.goto_name = if alias.is_empty() { o.name.to_owned() } else { alias.to_owned() };
                    self.goto_ra = format!("{ra:.5}");
                    self.goto_dec = format!("{dec:.4}");
                    self.search.clear();
                }
            }
            ui.horizontal(|ui| {
                ui.add(egui::TextEdit::singleline(&mut self.goto_name).hint_text("name").desired_width(70.0));
                ui.add(egui::TextEdit::singleline(&mut self.goto_ra).hint_text("RA h").desired_width(55.0));
                ui.add(egui::TextEdit::singleline(&mut self.goto_dec).hint_text("Dec °").desired_width(55.0));
                if ui.button("Go").clicked() {
                    if let (Ok(ra), Ok(dec)) = (self.goto_ra.trim().parse::<f64>(), self.goto_dec.trim().parse::<f64>()) {
                        out.push(("iscope_start_view", Some(json!({
                            "mode": "star", "selected_cam": "View", "target_name": self.goto_name,
                            "target_ra_dec": [ra, dec], "lp_filter": false,
                        }))));
                    }
                }
            });

            ui.heading("Slew (hold)");
            egui::Grid::new("slew").show(ui, |ui| {
                // angle convention measured on an S50: see SLEW_* constants
                let mut dir = |ui: &mut egui::Ui, label: &str, angle: i64| {
                    if ui.add_sized([52.0, 26.0], egui::Button::new(label)).is_pointer_button_down_on() {
                        held_angle = Some(angle);
                    }
                };
                ui.label("");
                dir(ui, "Up", SLEW_UP);
                ui.end_row();
                dir(ui, "Left", SLEW_LEFT);
                ui.label("");
                dir(ui, "Right", SLEW_RIGHT);
                ui.end_row();
                ui.label("");
                dir(ui, "Down", SLEW_DOWN);
                ui.end_row();
            });
            ui.add(egui::Slider::new(&mut self.slew_percent, 1..=100).text("speed %"));
            ui.checkbox(&mut self.slew_fast, "fast");

            ui.heading("Focus");
            let step = s.device["focuser"]["step"].as_i64();
            let max = s.device["focuser"]["max_step"].as_i64().unwrap_or(2600);
            ui.horizontal(|ui| {
                for d in [-50i64, -5, 5, 50] {
                    if ui.button(format!("{d:+}")).clicked() {
                        if let Some(step) = step {
                            let target = (step + d).clamp(0, max);
                            s.device["focuser"]["step"] = json!(target);
                            out.push(("move_focuser", Some(json!({"step": target, "ret_step": false}))));
                        }
                    }
                }
                if ui.button("Auto").clicked() {
                    out.push(("start_auto_focuse", None));
                }
            });
            ui.label(format!("step {} / {max}", step.map_or("--".into(), |v| v.to_string())));

            ui.separator();
            ui.heading("Device");
            egui::ScrollArea::vertical().id_salt("dev").show(ui, |ui| {
                ui.monospace(serde_json::to_string_pretty(&s.device).unwrap_or_default());
            });
        });

        egui::Panel::right("settings").min_size(230.0).show(ui, |ui| {
            egui::ScrollArea::vertical().id_salt("settings").show(ui, |ui| {
                ui.heading("Solar system");
                ui.checkbox(&mut self.solar_filter_on, "solar filter is fitted");
                ui.horizontal(|ui| {
                    if ui.add_enabled(self.solar_filter_on, egui::Button::new("Sun")).clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "solar_sys", "target_type": "sun", "target_name": "Sun"}))));
                    }
                    if ui.button("Moon").clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "solar_sys", "target_type": "moon", "target_name": "Moon"}))));
                    }
                });

                ui.heading("Location");
                let here = &s.device["location_lon_lat"];
                ui.label(match (here[1].as_f64(), here[0].as_f64()) {
                    (Some(lat), Some(lon)) => format!("scope has lat {lat:.4}, lon {lon:.4}"),
                    _ => "scope has no location".into(),
                });
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut self.loc_lat).hint_text("lat °").desired_width(60.0));
                    ui.add(egui::TextEdit::singleline(&mut self.loc_lon).hint_text("lon °").desired_width(60.0));
                    if ui.button("Set").clicked() {
                        if let (Ok(lat), Ok(lon)) = (self.loc_lat.trim().parse::<f64>(), self.loc_lon.trim().parse::<f64>()) {
                            out.push(("set_user_location", Some(json!({"lon": lon, "lat": lat, "force": false}))));
                        }
                    }
                });

                ui.heading("Exposure");
                let stack_ms = s.device["setting"]["exp_ms"]["stack_l"].as_i64();
                ui.horizontal(|ui| {
                    ui.label("stack");
                    for ms in [10000i64, 20000, 30000, 60000] {
                        if ui.selectable_label(stack_ms == Some(ms), format!("{}s", ms / 1000)).clicked() {
                            out.push(("set_setting", Some(json!({"exp_ms": {"stack_l": ms}}))));
                        }
                    }
                });
                if let Some(mut gain) = s.device["setting"]["isp_gain"].as_f64() {
                    let max = s.device["setting"]["isp_range_gain"][1].as_f64().unwrap_or(400.0);
                    let r = ui.add(egui::Slider::new(&mut gain, 0.0..=max).text("gain"));
                    s.device["setting"]["isp_gain"] = json!(gain);
                    if r.drag_stopped() || r.lost_focus() {
                        out.push(("set_setting", Some(json!({"isp_gain": gain}))));
                    }
                }

                ui.horizontal(|ui| {
                    ui.label("shutter");
                    ui.add(egui::DragValue::new(&mut self.exp_ms).range(0.03..=1000.0).speed(0.5).suffix(" ms"));
                    if ui.button("Set").clicked() {
                        out.push(("set_setting", Some(json!({"manual_exp": true}))));
                        out.push(("set_setting", Some(json!({"isp_exp_ms": self.exp_ms}))));
                    }
                    if ui.button("Auto").clicked() {
                        out.push(("set_setting", Some(json!({"manual_exp": false}))));
                    }
                });
                if let Some(now) = s.device["setting"]["isp_exp_ms"].as_f64() {
                    ui.small(format!("scope reports {now} ms"));
                }

                ui.heading("Timelapse");
                ui.horizontal(|ui| {
                    ui.label("every");
                    ui.add(egui::DragValue::new(&mut self.lapse_interval_s).range(0.2..=3600.0).speed(0.1).suffix(" s"));
                    ui.label("for");
                    ui.add(egui::DragValue::new(&mut self.lapse_minutes).range(0.0..=100000.0).speed(1.0).suffix(" min"));
                });
                ui.small("0 min = no end. Frames carry the HUD when it is on.");
                // the scope's own timelapse: an mp4 on its storage, no HUD
                ui.horizontal(|ui| {
                    if ui.button("Record on scope").clicked() {
                        let minutes = if self.lapse_minutes > 0.0 { self.lapse_minutes.round() as i64 } else { -1 };
                        let timelapse = json!({"enable": true, "delay_sec": self.lapse_interval_s.round().max(1.0) as i64, "src_dura_min": minutes});
                        out.push(("start_record_avi", Some(json!({"raw": false, "timelapse": timelapse}))));
                    }
                    if ui.button("Stop").clicked() {
                        out.push(("stop_record_avi", None));
                    }
                });
                match &self.lapse {
                    Some(l) => {
                        ui.label(format!("{} frames in {}", l.saved, l.dir.display()));
                        if ui.button("Stop timelapse").clicked() {
                            self.lapse = None;
                        }
                    }
                    None => {
                        if ui.button("Start timelapse").clicked() {
                            let dir = std::path::PathBuf::from(chrono::Local::now().format("timelapse_%Y%m%d_%H%M%S").to_string());
                            match std::fs::create_dir_all(&dir) {
                                Ok(()) => self.lapse = Some(Timelapse { dir, started: Instant::now(), next: Instant::now(), saved: 0 }),
                                Err(e) => s.frame_info = format!("timelapse: {e}"),
                            }
                        }
                    }
                }

                // Every boolean the scope reports is listed, including ones the official app hides.
                let bools = |v: &Value| -> Vec<(String, bool)> {
                    v.as_object().into_iter().flatten().filter_map(|(k, v)| Some((k.clone(), v.as_bool()?))).collect()
                };
                ui.heading("Settings");
                for (key, mut on) in bools(&s.device["setting"]) {
                    if ui.checkbox(&mut on, &key).changed() {
                        s.device["setting"][&key] = json!(on);
                        let (method, params) = match key.as_str() {
                            "heater_enable" => ("pi_output_set2", json!({"heater": {"state": on, "value": 90}})),
                            _ => ("set_setting", json!({key: on})),
                        };
                        out.push((method, Some(params)));
                    }
                }
                ui.heading("Stack options");
                for (key, mut on) in bools(&s.device["setting"]["stack"]) {
                    if ui.checkbox(&mut on, &key).changed() {
                        s.device["setting"]["stack"][&key] = json!(on);
                        out.push(("set_setting", Some(json!({"stack": {key: on}}))));
                    }
                }
            });
        });

        egui::Panel::bottom("raw").resizable(true).default_size(220.0).show(ui, |ui| {
            ui.heading("Raw command");
            ui.horizontal(|ui| {
                ui.label("method");
                ui.text_edit_singleline(&mut self.raw_method);
            });
            ui.horizontal(|ui| {
                ui.label("params");
                ui.text_edit_singleline(&mut self.raw_params);
                if ui.button("Send").clicked() && !self.raw_method.is_empty() {
                    let params = serde_json::from_str(&self.raw_params).ok();
                    self.send(&self.raw_method.clone(), params);
                }
            });
            ui.separator();
            egui::ScrollArea::vertical().stick_to_bottom(true).show(ui, |ui| {
                for line in &s.log {
                    ui.monospace(line);
                }
            });
        });

        egui::CentralPanel::default().show(ui, |ui| {
            ui.horizontal(|ui| {
                for (label, method) in [("Live", "begin_streaming"), ("Stop", "stop_streaming"), ("Snapshot", "get_current_img"), ("Stacked", "get_stacked_img")] {
                    if ui.button(label).clicked() {
                        image_cmd = Some(method);
                    }
                }
                if ui.button("Save PNG").clicked() {
                    save_frame = Some(false);
                }
                if ui.button("Save with HUD").clicked() {
                    save_frame = Some(true);
                }
                ui.checkbox(&mut self.hud, "HUD");
                ui.checkbox(&mut self.flip_h, "flip H");
                ui.checkbox(&mut self.flip_v, "flip V");
                if ui.checkbox(&mut self.wide, "wide camera").changed() {
                    self.img_tx = None;
                }
                ui.separator();
                egui::ComboBox::from_id_salt("bayer").selected_text(["RGGB", "BGGR", "GRBG", "GBRG"][s.knobs.bayer as usize & 3]).show_ui(ui, |ui| {
                    for (i, name) in ["RGGB", "BGGR", "GRBG", "GBRG"].iter().enumerate() {
                        ui.selectable_value(&mut s.knobs.bayer, i as u8, *name);
                    }
                });
                ui.add(egui::Slider::new(&mut s.knobs.gamma, 0.2..=1.5).text("gamma"));
                ui.checkbox(&mut s.knobs.swap_rb, "swap R/B");
            });
            ui.label(&s.frame_info);
            self.view_rect = match &self.texture {
                Some(t) => {
                    let (u0, u1) = if self.flip_h { (1.0, 0.0) } else { (0.0, 1.0) };
                    let (v0, v1) = if self.flip_v { (1.0, 0.0) } else { (0.0, 1.0) };
                    let uv = egui::Rect::from_min_max(egui::pos2(u0, v0), egui::pos2(u1, v1));
                    ui.add(egui::Image::new(t).uv(uv).shrink_to_fit()).rect
                }
                None => ui.available_rect_before_wrap(),
            };
            if self.hud {
                let r = self.view_rect;
                let p = ui.painter_at(r);
                let (c, arm, gap) = (r.center(), 40.0, 8.0);
                let stroke = egui::Stroke::new(1.5, egui::Color32::from_rgb(255, 60, 60));
                for d in [egui::vec2(1.0, 0.0), egui::vec2(-1.0, 0.0), egui::vec2(0.0, 1.0), egui::vec2(0.0, -1.0)] {
                    p.line_segment([c + d * gap, c + d * arm], stroke);
                }
                let text = lines.join("\n");
                let galley = p.layout_no_wrap(text, egui::FontId::monospace(14.0), egui::Color32::WHITE);
                let pos = egui::pos2(r.left() + 8.0, r.bottom() - galley.size().y - 8.0);
                p.rect_filled(egui::Rect::from_min_size(pos, galley.size()).expand(4.0), 2.0, egui::Color32::from_black_alpha(160));
                p.galley(pos, galley, egui::Color32::WHITE);
                ctx.request_repaint_after(Duration::from_millis(100));
            }
        });
        drop(s);
        if let Some(with_hud) = save_frame {
            let name = chrono::Local::now().format(if with_hud { "seestar_hud_%Y%m%d_%H%M%S.png" } else { "seestar_%Y%m%d_%H%M%S.png" }).to_string();
            let msg = match self.save_png(name.as_ref(), with_hud.then_some(&lines[..])) {
                Ok(()) => format!("saved {name}"),
                Err(e) => format!("save failed: {e}"),
            };
            self.shared.lock().unwrap().frame_info = msg;
        }
        if let Some(l) = &mut self.lapse {
            let over = self.lapse_minutes > 0.0 && l.started.elapsed().as_secs_f64() > self.lapse_minutes * 60.0;
            if over {
                self.lapse = None;
            } else if Instant::now() >= l.next {
                l.next += Duration::from_secs_f64(self.lapse_interval_s.max(0.2));
                let path = l.dir.join(format!("frame_{:06}.png", l.saved + 1));
                match self.save_png(&path, self.hud.then_some(&lines[..])) {
                    Ok(()) => self.lapse.iter_mut().for_each(|l| l.saved += 1),
                    Err(e) => self.shared.lock().unwrap().frame_info = format!("timelapse: {e}"),
                }
            }
            ctx.request_repaint_after(Duration::from_millis(100));
        }
        if toggle_video {
            if let Some(mut child) = self.video.take() {
                let _ = child.kill();
                let _ = child.wait();
            } else {
                let port = if self.wide { 4555 } else { 4554 };
                let (shared, ctx2) = (self.shared.clone(), ctx.clone());
                let on_frame = move |f: proto::Res<stream::Frame>| {
                    let mut s = shared.lock().unwrap();
                    match f {
                        Ok(frame) => {
                            s.frame_info = frame.info.clone();
                            s.frame = Some(frame);
                        }
                        Err(e) => s.frame_info = format!("video ended: {e}"),
                    }
                    ctx2.request_repaint();
                };
                let started = proto::resolve(&self.host, port).and_then(|a| stream::spawn_video(&format!("rtsp://{a}/stream"), on_frame));
                match started {
                    Ok(child) => self.video = Some(child),
                    Err(e) => self.shared.lock().unwrap().frame_info = format!("video: {e}"),
                }
            }
        }
        for (method, params) in out {
            self.send(method, params);
        }
        let level = self.slew_fast as i64;
        self.shared.lock().unwrap().slewing = held_angle.is_some();
        if let Some(angle) = held_angle {
            // the app resends every ~200 ms while held; each command lasts dur_sec
            if !self.slewing || self.last_slew.elapsed() > Duration::from_millis(200) {
                self.send("scope_speed_move", Some(json!({"percent": self.slew_percent, "angle": angle, "level": level, "dur_sec": 3})));
                self.last_slew = Instant::now();
                self.slewing = true;
            }
            ctx.request_repaint_after(Duration::from_millis(50));
        } else if self.slewing {
            self.send("scope_speed_move", Some(json!({"percent": 0, "angle": 0, "level": level, "dur_sec": 3})));
            self.slewing = false;
        }
        if let Some(m) = image_cmd {
            self.image_cmd(m, &ctx);
        }
    }
}

fn main() -> eframe::Result {
    let host = std::env::args().skip(1).find(|a| !a.starts_with("--")).unwrap_or_else(|| "SeeStar.local".into());
    eframe::run_native(
        "Seestar PC",
        eframe::NativeOptions::default(),
        Box::new(|cc| {
            let mut app = App {
                host,
                shared: Default::default(),
                tx: None,
                raw_method: String::new(),
                raw_params: String::new(),
                img_tx: None,
                wide: false,
                texture: None,
                video: None,
                auto_video: false,
                slew_percent: 40,
                slew_fast: true,
                slewing: false,
                last_slew: Instant::now(),
                goto_name: String::new(),
                goto_ra: String::new(),
                goto_dec: String::new(),
                solar_filter_on: false,
                last_frame: None,
                loc_lat: String::new(),
                loc_lon: String::new(),
                search: String::new(),
                hud: true,
                flip_h: false,
                flip_v: false,
                view_rect: egui::Rect::NOTHING,
                exp_ms: 10.0,
                lapse_interval_s: 5.0,
                lapse_minutes: 5.0,
                lapse: None,
            };
            app.connect(&cc.egui_ctx);
            if std::env::args().any(|a| a == "--live") {
                app.image_cmd("begin_streaming", &cc.egui_ctx);
            }
            app.auto_video = std::env::args().any(|a| a == "--video");
            if std::env::args().any(|a| a == "--timelapse") {
                let dir = std::path::PathBuf::from(chrono::Local::now().format("timelapse_%Y%m%d_%H%M%S").to_string());
                if std::fs::create_dir_all(&dir).is_ok() {
                    app.lapse = Some(Timelapse { dir, started: Instant::now(), next: Instant::now(), saved: 0 });
                }
            }
            Ok(Box::new(app))
        }),
    )
}
