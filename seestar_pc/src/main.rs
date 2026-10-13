mod catalog;
mod hud;
mod proto;
mod sky;
mod starlog;
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
    /// the level display is open and wants fresh sensor readings
    level_open: bool,
    /// HUD data block, crosshair, flip H, flip V as the UI has them; read by the video thread
    overlay: (bool, bool, bool, bool),
    /// file for a HUD recording that starts with the next video frame
    hud_rec_path: Option<std::path::PathBuf>,
    hud_rec: Option<stream::HudRecorder>,
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
    let telemetry = std::env::args().any(|a| a == "--json");
    // Reconnect for as long as the control connection lives; the scope drops idle or stalled connections.
    while !stop.load(Ordering::Relaxed) {
        let Ok(mut scope) = proto::Scope::connect(&host) else {
            std::thread::sleep(Duration::from_secs(2));
            continue;
        };
        let mut last_state = Instant::now() - Duration::from_secs(60);
        while !stop.load(Ordering::Relaxed) {
            let Ok(state) = scope.call("scope_get_state", None) else { break };
            // the scope answers one request at a time across all connections, so a slow
            // get_device_state here would stall slew commands
            let (slewing, level_open) = {
                let s = shared.lock().unwrap();
                (s.slewing, s.level_open)
            };
            let device = if !slewing && (refresh.swap(false, Ordering::Relaxed) || last_state.elapsed() > Duration::from_secs(10)) {
                last_state = Instant::now();
                scope.call("get_device_state", None).ok().map(|r| r["result"].clone())
            } else {
                None
            };
            // the level display needs fresh sensor readings; this subset answers in a few ms
            let sensors = if level_open && !slewing {
                scope.call("get_device_state", Some(json!({"keys": ["balance_sensor", "compass_sensor"]}))).ok().map(|r| r["result"].clone())
            } else {
                None
            };
            let events: Vec<Value> = scope.events.drain(..).collect();
            let r = &state["result"];
            if telemetry && r.is_object() {
                println!("{}", json!({"utc": chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true), "az": r["az"], "alt": r["alt"], "ra": r["ra"], "dec": r["dec"]}));
            }
            {
                let mut s = shared.lock().unwrap();
                // The scope answers some requests with an error while it is busy (for example during a goto).
                // Keep the last good values then.
                if let Some(v) = r["alt"].as_f64().zip(r["az"].as_f64()) {
                    s.alt_az = Some(v);
                }
                if let Some(v) = r["ra"].as_f64().zip(r["dec"].as_f64()) {
                    s.ra_dec = Some(v);
                }
                if let Some(d) = device.filter(Value::is_object) {
                    s.device = d;
                }
                if let Some(v) = sensors.filter(Value::is_object) {
                    for key in ["balance_sensor", "compass_sensor"] {
                        if v[key].is_object() && s.device.is_object() {
                            s.device[key] = v[key].clone();
                        }
                    }
                }
                for e in &events {
                    s.log(format!("* {e}"));
                }
            }
            ctx.request_repaint();
            std::thread::sleep(Duration::from_millis(if level_open { 200 } else { 400 }));
        }
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

    let mut pending: VecDeque<Cmd> = VecDeque::new();
    let (trace, started) = (std::env::args().any(|a| a == "--trace"), Instant::now());
    let err = loop {
        if pending.is_empty() {
            pending.push_back(match rx.recv_timeout(Duration::from_secs(2)) {
                Ok(cmd) => cmd,
                Err(RecvTimeoutError::Disconnected) => break "disconnected".to_string(),
                Err(RecvTimeoutError::Timeout) => Cmd { method: "test_connection".into(), params: None },
            });
        }
        pending.extend(rx.try_iter());
        // A slew command replaces any older one still waiting; a backlog would keep the mount
        // moving after the button is released.
        let newest_slew = pending.iter().rposition(|c| c.method == "scope_speed_move");
        let mut index = 0;
        pending.retain(|c| {
            index += 1;
            c.method != "scope_speed_move" || Some(index - 1) == newest_slew
        });
        let Some(cmd) = pending.pop_front() else { continue };
        let quiet = cmd.method == "scope_speed_move" || cmd.method == "test_connection";
        if trace {
            eprintln!("{:?} {} {}", started.elapsed(), cmd.method, cmd.params.clone().unwrap_or(Value::Null));
        }
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

/// Level display: tilt at the outer ring, and the tilt shown as good.
const LEVEL_FULL_SCALE_DEG: f64 = 5.0;
const LEVEL_OK_DEG: f64 = 0.5;

/// Solar-system targets closer to the Sun than this need the solar filter tick.
const SUN_GUARD_DEG: f64 = 20.0;

/// Width of each side panel. Both are the same, so the view sits in the middle of the window.
const SIDE_PANEL_WIDTH: f32 = 300.0;

/// The interface is drawn at 100 % on a monitor of this height and larger on taller monitors.
const BASE_MONITOR_HEIGHT: f32 = 1080.0;

const ACCENT: egui::Color32 = egui::Color32::from_rgb(110, 170, 255);
const GREEN: egui::Color32 = egui::Color32::from_rgb(90, 200, 110);
const RED: egui::Color32 = egui::Color32::from_rgb(235, 80, 80);

fn section(ui: &mut egui::Ui, title: &str) {
    ui.add_space(10.0);
    ui.label(egui::RichText::new(title.to_uppercase()).small().strong().color(ACCENT));
    ui.separator();
}

fn home_dir() -> Option<std::path::PathBuf> {
    std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(Into::into)
}

fn settings_path() -> Option<std::path::PathBuf> {
    let base = std::env::var_os("XDG_CONFIG_HOME").or_else(|| std::env::var_os("APPDATA")).map(Into::into).or_else(|| Some(home_dir()?.join(".config")))?;
    Some(base.join("stardeck").join("settings.json"))
}

fn open_folder(dir: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let opener = if cfg!(windows) { "explorer" } else if cfg!(target_os = "macos") { "open" } else { "xdg-open" };
    std::process::Command::new(opener).arg(dir).spawn().map(drop)
}

/// Wait between a scenery or solar view command and the video start.
const VIDEO_START_DELAY: Duration = Duration::from_secs(3);

/// Starlog default: about half the short side of the S50 telephoto field.
const STARLOG_RADIUS_DEG: f64 = 0.35;

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
    /// file and start time of the recording in progress
    recording: Option<(std::path::PathBuf, Instant)>,
    /// folder for recordings, pictures, timelapse frames and star logs
    save_dir: String,
    /// interface size chosen by the user, on top of the monitor scaling
    ui_scale: f32,
    /// interface size for the monitor the app opened on
    monitor_scale: Option<f32>,
    show_console: bool,
    /// settings as last written to disk
    saved_settings: Value,
    auto_video: bool,
    auto_record: bool,
    /// start the video at this time: set when a scenery or solar view starts
    video_due: Option<Instant>,
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
    show_level: bool,
    hud: bool,
    crosshair: bool,
    flip_h: bool,
    flip_v: bool,
    /// on-screen rectangle of the view, for cropping HUD screenshots
    view_rect: egui::Rect,
    exp_ms: f64,
    lapse_interval_s: f64,
    /// 0 = run until stopped
    lapse_minutes: f64,
    lapse: Option<Timelapse>,
    starlog: Option<starlog::Log>,
    /// a star is logged when it passes this close to the field centre, degrees
    starlog_radius: f64,
    starlog_mag: f64,
    /// air at the telescope, for refraction: temperature °C and pressure mbar
    starlog_temp_c: f64,
    starlog_pressure_mb: f64,
    /// height of the telescope above sea level, metres
    starlog_height_m: f64,
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
            hud::burn(&mut rgba, f.width, f.height, lines, self.crosshair);
        }
        image::save_buffer(path, &rgba, f.width as u32, f.height as u32, image::ColorType::Rgba8).map_err(|e| e.to_string())
    }

    /// Path for a new file in the save folder; makes the folder.
    fn out_path(&self, name: &str) -> std::io::Result<std::path::PathBuf> {
        let dir = std::path::PathBuf::from(self.save_dir.trim());
        std::fs::create_dir_all(&dir)?;
        Ok(dir.join(name))
    }

    fn settings(&self) -> Value {
        json!({
            "save_dir": self.save_dir, "ui_scale": self.ui_scale, "hud": self.hud, "crosshair": self.crosshair,
            "flip_h": self.flip_h, "flip_v": self.flip_v, "starlog_radius": self.starlog_radius, "starlog_mag": self.starlog_mag,
            "temp_c": self.starlog_temp_c, "pressure_mb": self.starlog_pressure_mb, "height_m": self.starlog_height_m,
        })
    }

    /// Stops the video and closes the recording file, if any.
    fn stop_video(&mut self) {
        self.recording = None;
        let hud_rec = {
            let mut s = self.shared.lock().unwrap();
            s.hud_rec_path = None;
            s.hud_rec.take()
        };
        drop(hud_rec); // closes the HUD recording file
        let Some(mut child) = self.video.take() else { return };
        // "q" lets ffmpeg finish the file; kill it if it does not leave
        if let Some(mut stdin) = child.stdin.take() {
            let _ = std::io::Write::write_all(&mut stdin, b"q");
        }
        let asked = Instant::now();
        while asked.elapsed() < Duration::from_secs(2) && !matches!(child.try_wait(), Ok(Some(_))) {
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = child.kill();
        let _ = child.wait();
    }

    /// Starts the video; with `record`, also writes it to a new mp4 file in the save folder.
    fn start_video(&mut self, ctx: &egui::Context, record: bool) {
        self.stop_video();
        let port = if self.wide { 4555 } else { 4554 };
        let (shared, ctx) = (self.shared.clone(), ctx.clone());
        let on_frame = move |f: proto::Res<stream::Frame>| {
            let mut s = shared.lock().unwrap();
            match f {
                Ok(frame) => {
                    // HUD recording: the frame as displayed (flips, data block, crosshair) goes to the file
                    if let Some(path) = s.hud_rec_path.take() {
                        match stream::HudRecorder::start(&path, frame.width, frame.height) {
                            Ok(r) => s.hud_rec = Some(r),
                            Err(e) => s.frame_info = format!("record: {e}"),
                        }
                    }
                    if s.hud_rec.is_some() {
                        let (hud_on, crosshair, flip_h, flip_v) = s.overlay;
                        let mut rgba = frame.rgba.clone();
                        hud::flip(&mut rgba, frame.width, frame.height, flip_h, flip_v);
                        if hud_on {
                            hud::burn(&mut rgba, frame.width, frame.height, &hud_lines(&s), crosshair);
                        }
                        if !s.hud_rec.as_mut().is_some_and(|r| r.write(&rgba)) {
                            s.hud_rec = None;
                        }
                    }
                    s.frame_info = frame.info.clone();
                    s.frame = Some(frame);
                }
                Err(e) => s.frame_info = format!("video ended: {e}"),
            }
            ctx.request_repaint();
        };
        // With the HUD on, the recording is made from the displayed frames so the HUD is in the picture.
        // With it off, ffmpeg copies the scope's stream unchanged (full size).
        let hud_recording = record && self.hud;
        let started = (|| -> proto::Res<_> {
            let file = if record { Some(self.out_path(&chrono::Local::now().format("stardeck_%Y%m%d_%H%M%S.mp4").to_string())?) } else { None };
            let addr = proto::resolve(&self.host, port)?;
            let copy_to = if hud_recording { None } else { file.as_deref() };
            let child = stream::spawn_video(&format!("rtsp://{addr}/stream"), copy_to, on_frame)?;
            if hud_recording {
                self.shared.lock().unwrap().hud_rec_path = file.clone();
            }
            Ok((child, file))
        })();
        match started {
            Ok((child, file)) => {
                self.video = Some(child);
                self.recording = file.map(|f| (f, Instant::now()));
            }
            Err(e) => self.shared.lock().unwrap().frame_info = format!("video: {e}"),
        }
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
        s.overlay = (self.hud, self.crosshair, self.flip_h, self.flip_v);
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
        let mut toggle_record = std::mem::take(&mut self.auto_record);
        // ffmpeg left by itself (no stream in star mode, connection lost)
        if self.video.as_mut().is_some_and(|c| matches!(c.try_wait(), Ok(Some(_)))) {
            self.video = None;
            self.recording = None;
        }
        // Read the monitor height once, before any zoom is set: the reported size is in points at the zoom of
        // an earlier pass, so reading it again after a zoom change makes the two chase each other.
        if self.monitor_scale.is_none() {
            self.monitor_scale = ctx.input(|i| i.viewport().monitor_size).map(|m| (m.y / BASE_MONITOR_HEIGHT * 20.0).round() / 20.0).map(|v| v.clamp(1.0, 3.0));
        }
        if let Some(monitor_scale) = self.monitor_scale {
            ctx.set_zoom_factor(monitor_scale * self.ui_scale);
        }
        if self.video_due.is_some_and(|t| Instant::now() >= t) {
            self.video_due = None;
            toggle_video |= self.video.is_none();
        }
        let site = s.device["location_lon_lat"][1].as_f64().zip(s.device["location_lon_lat"][0].as_f64()); // (lat, lon east)
        let unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0.0, |d| d.as_secs_f64());
        let pointing = s.alt_az.zip(site).filter(|_| s.connected);
        if let (Some(log), Some(((alt, az), (lat, lon)))) = (&mut self.starlog, pointing) {
            if let Err(e) = log.step(unix, az, alt, lat, lon, (self.starlog_temp_c, self.starlog_pressure_mb), self.starlog_radius, self.starlog_mag) {
                s.frame_info = format!("starlog: {e}");
                self.starlog = None;
            }
        }

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
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("+").on_hover_text("Larger interface").clicked() {
                        self.ui_scale = (self.ui_scale + 0.1).min(2.5);
                    }
                    ui.label(format!("{:.0}%", self.ui_scale * 100.0));
                    if ui.button("−").on_hover_text("Smaller interface").clicked() {
                        self.ui_scale = (self.ui_scale - 0.1).max(0.5);
                    }
                    ui.label("Size");
                    ui.separator();
                    ui.toggle_value(&mut self.show_console, "Console").on_hover_text("Raw commands and the reply log");
                });
            });
        });

        egui::Panel::left("left").exact_size(SIDE_PANEL_WIDTH).resizable(false).show(ui, |ui| {
            egui::ScrollArea::vertical().id_salt("left").show(ui, |ui| {
            section(ui, "Pointing");
            let fmt = |v: Option<(f64, f64)>, a: &str, b: &str| match v {
                Some((x, y)) => format!("{a} {x:9.4}   {b} {y:9.4}"),
                None => format!("{a} --   {b} --"),
            };
            ui.monospace(fmt(s.alt_az.map(|(alt, az)| (az, alt)), "Az", "El"));
            ui.monospace(fmt(s.ra_dec, "RA", "Dec"));

            section(ui, "Arm");
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

            section(ui, "View");
            ui.horizontal(|ui| {
                let jd = catalog::julian_date_now();
                let here = &s.device["location_lon_lat"];
                let site = here[1].as_f64().zip(here[0].as_f64()); // (lat, lon east)
                let alt = |ra: f64, dec: f64| site.map(|(lat, lon)| sky::altitude_deg(ra, dec, jd, lat, lon));
                let alt_text = |a: Option<f64>| a.map_or(String::new(), |a| format!("  alt {a:.0}°"));

                ui.menu_button("Star", |ui| {
                    if ui.button("Star view at the current position").clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "star"}))));
                        ui.close();
                    }
                    ui.separator();
                    ui.label("Messier objects, highest now first");
                    let mut up: Vec<(&catalog::Object, f64, f64, Option<f64>)> = catalog::messier()
                        .map(|o| {
                            let (ra, dec) = catalog::precess_from_j2000(o.ra_hours, o.dec_deg, jd);
                            (o, ra, dec, alt(ra, dec))
                        })
                        .filter(|t| t.3.is_none_or(|a| a > 15.0))
                        .collect();
                    up.sort_by(|a, b| b.3.partial_cmp(&a.3).unwrap_or(std::cmp::Ordering::Equal));
                    egui::ScrollArea::vertical().max_height(420.0).show(ui, |ui| {
                        for (o, ra, dec, a) in up {
                            let mut names = o.other.split('|');
                            let m = names.next().unwrap_or(o.name);
                            let label = format!("{m}  {}  {}  mag {}{}", names.next().unwrap_or(o.name), o.kind, if o.mag.is_empty() { "?" } else { o.mag }, alt_text(a));
                            if ui.button(label).clicked() {
                                out.push(("iscope_start_view", Some(json!({
                                    "mode": "star", "selected_cam": "View", "target_name": m,
                                    "target_ra_dec": [ra, dec], "lp_filter": false,
                                }))));
                                ui.close();
                            }
                        }
                    });
                });
                if ui.button("Scenery").clicked() {
                    out.push(("iscope_start_view", Some(json!({"mode": "scenery"}))));
                }
                ui.menu_button("Solar", |ui| {
                    if ui.button("Solar view at the current position").clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "solar_sys"}))));
                        ui.close();
                    }
                    ui.separator();
                    ui.checkbox(&mut self.solar_filter_on, "solar filter is fitted");
                    let (sun_ra, sun_dec) = sky::sun_ra_dec(jd);
                    if ui.add_enabled(self.solar_filter_on, egui::Button::new(format!("Sun{}", alt_text(alt(sun_ra, sun_dec))))).clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "solar_sys", "target_type": "sun", "target_name": "Sun"}))));
                        ui.close();
                    }
                    // A target close to the Sun puts the Sun in or near the field: same rule as the Sun itself.
                    let near_sun = |ra: f64, dec: f64| sky::separation_deg((ra, dec), (sun_ra, sun_dec)) < SUN_GUARD_DEG;
                    let allowed = |ra: f64, dec: f64| self.solar_filter_on || !near_sun(ra, dec);
                    let warn = |ra: f64, dec: f64| if near_sun(ra, dec) { "  (near the Sun)" } else { "" };
                    let (moon_ra, moon_dec) = sky::moon_ra_dec(jd);
                    if ui.add_enabled(allowed(moon_ra, moon_dec), egui::Button::new(format!("Moon{}{}", alt_text(alt(moon_ra, moon_dec)), warn(moon_ra, moon_dec)))).clicked() {
                        out.push(("iscope_start_view", Some(json!({"mode": "solar_sys", "target_type": "moon", "target_name": "Moon"}))));
                        ui.close();
                    }
                    ui.separator();
                    for (name, planet) in &sky::PLANETS {
                        let (ra, dec) = sky::planet_ra_dec(planet, jd);
                        if ui.add_enabled(allowed(ra, dec), egui::Button::new(format!("{name}{}{}", alt_text(alt(ra, dec)), warn(ra, dec)))).clicked() {
                            out.push(("iscope_start_view", Some(json!({
                                "mode": "solar_sys", "target_type": "planet", "target_name": name, "target_ra_dec": [ra, dec],
                            }))));
                            ui.close();
                        }
                    }
                });
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
            ui.horizontal(|ui| {
                if ui.button(if self.video.is_some() { "Stop video" } else { "Video" }).clicked() {
                    toggle_video = true;
                }
                let on = self.starlog.is_some();
                let outline = if on { egui::Stroke::new(1.5, GREEN) } else { egui::Stroke::NONE };
                let button = egui::Button::new("Starlog").selected(on).stroke(outline);
                if ui.add(button).on_hover_text("Log the stars that cross the centre of the field to a CSV file.").clicked() {
                    self.starlog = match self.starlog.take() {
                        Some(_) => None,
                        None => self
                            .out_path(&chrono::Local::now().format("starlog_%Y%m%d_%H%M%S.csv").to_string())
                            .and_then(starlog::Log::create)
                            .map_err(|e| s.frame_info = format!("starlog: {e}"))
                            .ok(),
                    };
                }
            });

            section(ui, "Goto (JNow)");
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

            section(ui, "Slew (hold)");
            egui::Grid::new("slew").show(ui, |ui| {
                // angle convention measured on an S50: see SLEW_* constants
                let mut dir = |ui: &mut egui::Ui, label: &str, angle: i64| {
                    // a click-only button stops reporting "down" once the press is too long to be a click,
                    // so the button also senses drags and a long hold stays active
                    let r = ui.add_sized([52.0, 26.0], egui::Button::new(label).sense(egui::Sense::click_and_drag()));
                    if r.is_pointer_button_down_on() || r.dragged() {
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

            section(ui, "Focus");
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

            ui.add_space(10.0);
            egui::CollapsingHeader::new("Device info").show(ui, |ui| {
                ui.monospace(serde_json::to_string_pretty(&s.device).unwrap_or_default());
            });
            });
        });

        egui::Panel::right("settings").exact_size(SIDE_PANEL_WIDTH).resizable(false).show(ui, |ui| {
            egui::ScrollArea::vertical().id_salt("settings").show(ui, |ui| {
                section(ui, "Capture");
                ui.horizontal(|ui| {
                    ui.label("folder");
                    ui.add(egui::TextEdit::singleline(&mut self.save_dir).desired_width(170.0)).on_hover_text("Recordings, pictures, timelapse frames and star logs are saved here.");
                    if ui.button("Open").clicked() {
                        if let Err(e) = open_folder(self.save_dir.trim().as_ref()) {
                            s.frame_info = format!("folder: {e}");
                        }
                    }
                });
                ui.horizontal(|ui| {
                    let label = match &self.recording {
                        Some((_, since)) => format!("Stop  {:02}:{:02}", since.elapsed().as_secs() / 60, since.elapsed().as_secs() % 60),
                        None => "Record".into(),
                    };
                    let outline = if self.recording.is_some() { egui::Stroke::new(1.5, RED) } else { egui::Stroke::NONE };
                    let button = egui::Button::new(egui::RichText::new(label).color(RED)).selected(self.recording.is_some()).stroke(outline);
                    if ui.add(button).on_hover_text("Records the scenery or solar video to an mp4 file in the folder.").clicked() {
                        toggle_record = true;
                    }
                    if ui.button("Picture").on_hover_text("Saves the view as a PNG file.").clicked() {
                        save_frame = Some(false);
                    }
                    if ui.button("with HUD").on_hover_text("Saves the view as a PNG file with the data block burned in.").clicked() {
                        save_frame = Some(true);
                    }
                });
                if let Some((file, _)) = &self.recording {
                    ui.small(file.display().to_string());
                    ctx.request_repaint_after(Duration::from_secs(1));
                }

                section(ui, "Location");
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

                section(ui, "Exposure");
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

                section(ui, "Timelapse");
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
                            let dir = self.out_path(&chrono::Local::now().format("timelapse_%Y%m%d_%H%M%S").to_string());
                            match dir.and_then(|dir| std::fs::create_dir_all(&dir).map(|()| dir)) {
                                Ok(dir) => self.lapse = Some(Timelapse { dir, started: Instant::now(), next: Instant::now(), saved: 0 }),
                                Err(e) => s.frame_info = format!("timelapse: {e}"),
                            }
                        }
                    }
                }

                // Every boolean the scope reports is listed, including ones the official app hides.
                let bools = |v: &Value| -> Vec<(String, bool)> {
                    v.as_object().into_iter().flatten().filter_map(|(k, v)| Some((k.clone(), v.as_bool()?))).collect()
                };
                // the on/off lists open as dropdowns and stay open while boxes are ticked
                let dropdown = |ui: &mut egui::Ui, name: &str, add: &mut dyn FnMut(&mut egui::Ui)| {
                    egui::ComboBox::from_id_salt(name).selected_text(name).close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside).show_ui(ui, |ui| add(ui));
                };
                section(ui, "Telescope settings");
                dropdown(ui, "Settings", &mut |ui| {
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
                });
                dropdown(ui, "Stack options", &mut |ui| {
                    for (key, mut on) in bools(&s.device["setting"]["stack"]) {
                        if ui.checkbox(&mut on, &key).changed() {
                            s.device["setting"]["stack"][&key] = json!(on);
                            out.push(("set_setting", Some(json!({"stack": {key: on}}))));
                        }
                    }
                });

                section(ui, "Starlog");
                ui.horizontal(|ui| {
                    ui.label("within");
                    ui.add(egui::DragValue::new(&mut self.starlog_radius).range(0.01..=5.0).speed(0.01).suffix("°"));
                    ui.label("to mag");
                    ui.add(egui::DragValue::new(&mut self.starlog_mag).range(-2.0..=15.0).speed(0.1));
                });
                ui.horizontal(|ui| {
                    ui.label("air");
                    ui.add(egui::DragValue::new(&mut self.starlog_temp_c).range(-60.0..=60.0).speed(0.1).suffix(" °C"));
                    ui.add(egui::DragValue::new(&mut self.starlog_pressure_mb).range(0.0..=1100.0).speed(1.0).suffix(" mbar"))
                        .on_hover_text("Pressure at the telescope, not corrected to sea level. 0 = no refraction.");
                });
                ui.horizontal(|ui| {
                    ui.label("height");
                    ui.add(egui::DragValue::new(&mut self.starlog_height_m).range(-500.0..=9000.0).speed(1.0).suffix(" m"));
                    if ui.button("Set pressure").on_hover_text("Sets the pressure from the height above sea level.").clicked() {
                        self.starlog_pressure_mb = (sky::pressure_from_height_mb(self.starlog_height_m) * 10.0).round() / 10.0;
                    }
                });
                match pointing {
                    None => {
                        ui.label("Needs a connected telescope with a location.");
                    }
                    Some(((alt, az), (lat, lon))) => {
                        let refraction = sky::refraction_deg(alt, self.starlog_temp_c, self.starlog_pressure_mb);
                        let centre = sky::eq_from_horizontal(az, alt - refraction, unix / 86400.0 + 2440587.5, lat, lon);
                        ui.monospace(format!("centre RA {:.4} h  Dec {:+.3}°\nrefraction {:.2}′", centre.0, centre.1, refraction * 60.0));
                        if s.device["mount"]["tracking"].as_bool() == Some(true) {
                            ui.small("Tracking is on: the field follows the stars, so none cross.");
                        }
                        ui.label("Next to cross");
                        let next = starlog::upcoming(centre, self.starlog_radius, self.starlog_mag, 900.0);
                        if next.is_empty() {
                            ui.small("none in the next 15 min");
                        }
                        for (star, t) in next.iter().take(8) {
                            ui.monospace(format!("{:02}:{:02}  {}  mag {}  {:+.3}°", *t as u32 / 60, *t as u32 % 60, star.label(), star.mag, star.dec - centre.1));
                        }
                        ctx.request_repaint_after(Duration::from_secs(1));
                    }
                }
                match &self.starlog {
                    Some(log) => {
                        ui.label(format!("Logged {} to {}", log.count, log.path.display()));
                        for line in log.recent.iter().rev().take(8) {
                            ui.monospace(line);
                        }
                    }
                    None => {
                        ui.small("Press Starlog in View to write crossings to a CSV file.");
                    }
                }

                section(ui, "Level");
                ui.checkbox(&mut self.show_level, "Level");
                s.level_open = self.show_level;
                if self.show_level {
                    let b = &s.device["balance_sensor"]["data"];
                    let (x, y, tilt) = (b["x"].as_f64().unwrap_or(0.0), b["y"].as_f64().unwrap_or(0.0), b["angle"].as_f64());
                    // bubble: full radius = LEVEL_FULL_SCALE_DEG of tilt; x and y are gravity components (sine of tilt)
                    let (rect, _) = ui.allocate_exact_size(egui::vec2(200.0, 200.0), egui::Sense::hover());
                    let p = ui.painter_at(rect);
                    let (c, radius) = (rect.center(), 95.0);
                    let grey = egui::Stroke::new(1.0, egui::Color32::GRAY);
                    p.circle_stroke(c, radius, grey);
                    p.circle_stroke(c, radius * LEVEL_OK_DEG as f32 / LEVEL_FULL_SCALE_DEG as f32, egui::Stroke::new(1.0, egui::Color32::from_rgb(90, 200, 110)));
                    p.line_segment([c - egui::vec2(radius, 0.0), c + egui::vec2(radius, 0.0)], grey);
                    p.line_segment([c - egui::vec2(0.0, radius), c + egui::vec2(0.0, radius)], grey);
                    let scale = radius as f64 / LEVEL_FULL_SCALE_DEG.to_radians().sin();
                    let mut d = egui::vec2((x * scale) as f32, (-y * scale) as f32);
                    if d.length() > radius {
                        d = d.normalized() * radius;
                    }
                    let good = tilt.is_some_and(|t| t <= LEVEL_OK_DEG);
                    p.circle_filled(c + d, 7.0, if good { egui::Color32::from_rgb(90, 200, 110) } else { egui::Color32::from_rgb(255, 170, 60) });
                    ui.monospace(format!(
                        "tilt {}\nx {:+.2}°  y {:+.2}°",
                        tilt.map_or("--".into(), |t| format!("{t:.2}°")),
                        x.clamp(-1.0, 1.0).asin().to_degrees(),
                        y.clamp(-1.0, 1.0).asin().to_degrees()
                    ));
                    let compass = &s.device["compass_sensor"]["data"];
                    ui.monospace(format!(
                        "compass {}  ({})",
                        compass["direction"].as_f64().map_or("--".into(), |v| format!("{v:.1}°")),
                        if compass["cali"].as_i64() == Some(0) { "not calibrated" } else { "calibrated" }
                    ));
                    ui.small(format!("Green ring = {LEVEL_OK_DEG}°, outer ring = {LEVEL_FULL_SCALE_DEG}°. Adjust the tripod until the dot is in the green ring."));
                    ui.horizontal(|ui| {
                        if ui.button("Calibrate compass").clicked() {
                            out.push(("start_compass_calibration", None));
                        }
                        if ui.button("Stop").clicked() {
                            out.push(("stop_compass_calibration", Some(json!({"force": false}))));
                        }
                    });
                    if ui.button("Calibrate level sensor").on_hover_text("Sets the sensor zero. Do this only when the telescope is level.").clicked() {
                        out.push(("start_gsensor_calibration", None));
                    }
                }
            });
        });

        let mut console_open = self.show_console;
        egui::Panel::bottom("raw").resizable(true).default_size(220.0).show_collapsible(ui, &mut console_open, |ui| {
            section(ui, "Raw command");
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
            ui.horizontal_wrapped(|ui| {
                for (label, method, hint) in [
                    ("Live", "begin_streaming", "Star view: starts the live frames."),
                    ("Stop", "stop_streaming", "Star view: stops the live frames."),
                    ("Snapshot", "get_current_img", "Star view: fetches one frame."),
                    ("Stacked", "get_stacked_img", "Star view: fetches the stacked picture."),
                ] {
                    if ui.button(label).on_hover_text(hint).clicked() {
                        image_cmd = Some(method);
                    }
                }
                egui::ComboBox::from_id_salt("display").selected_text("Display").close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside).show_ui(ui, |ui| {
                    ui.checkbox(&mut self.hud, "HUD data block");
                    ui.checkbox(&mut self.crosshair, "crosshair");
                    ui.checkbox(&mut self.flip_h, "flip horizontal");
                    ui.checkbox(&mut self.flip_v, "flip vertical");
                    if ui.checkbox(&mut self.wide, "wide camera").changed() {
                        self.img_tx = None;
                    }
                    ui.separator();
                    ui.label("Star view colour");
                    ui.horizontal(|ui| {
                        for (i, name) in ["RGGB", "BGGR", "GRBG", "GBRG"].iter().enumerate() {
                            ui.selectable_value(&mut s.knobs.bayer, i as u8, *name);
                        }
                    });
                    ui.add(egui::Slider::new(&mut s.knobs.gamma, 0.2..=1.5).text("gamma"));
                    ui.checkbox(&mut s.knobs.swap_rb, "swap R/B");
                });
            });
            ui.label(egui::RichText::new(&s.frame_info).weak());
            // The picture keeps its proportions and sits in the middle of the space that is left.
            // Only the visible part counts: a row that is too wide stretches the panel under the right panel.
            let margin = ui.max_rect().left() - ui.clip_rect().left();
            let area = ui.available_rect_before_wrap().intersect(ui.clip_rect().shrink(margin));
            ui.allocate_rect(area, egui::Sense::hover());
            self.view_rect = match &self.texture {
                Some(t) => {
                    let size = t.size_vec2() * (area.width() / t.size_vec2().x).min(area.height() / t.size_vec2().y);
                    let rect = egui::Rect::from_center_size(area.center(), size);
                    let (u0, u1) = if self.flip_h { (1.0, 0.0) } else { (0.0, 1.0) };
                    let (v0, v1) = if self.flip_v { (1.0, 0.0) } else { (0.0, 1.0) };
                    let uv = egui::Rect::from_min_max(egui::pos2(u0, v0), egui::pos2(u1, v1));
                    ui.painter().image(t.id(), rect, uv, egui::Color32::WHITE);
                    rect
                }
                None => area,
            };
            if self.hud || self.crosshair {
                let r = self.view_rect;
                let p = ui.painter_at(r);
                let (c, arm, gap) = (r.center(), 40.0, 8.0);
                let stroke = egui::Stroke::new(1.5, egui::Color32::from_rgb(255, 60, 60));
                for d in [egui::vec2(1.0, 0.0), egui::vec2(-1.0, 0.0), egui::vec2(0.0, 1.0), egui::vec2(0.0, -1.0)] {
                    if self.crosshair {
                        p.line_segment([c + d * gap, c + d * arm], stroke);
                    }
                }
            }
            if self.hud {
                let r = self.view_rect;
                let p = ui.painter_at(r);
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
            let saved = self.out_path(&name).map_err(|e| e.to_string()).and_then(|path| self.save_png(&path, with_hud.then_some(&lines[..])).map(|()| path));
            let msg = match saved {
                Ok(path) => format!("saved {}", path.display()),
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
        if toggle_record {
            self.start_video(&ctx, self.recording.is_none());
        } else if toggle_video {
            if self.video.is_some() {
                self.stop_video();
            } else {
                self.start_video(&ctx, false);
            }
        }
        let settings = self.settings();
        if settings != self.saved_settings {
            if let Some(path) = settings_path() {
                let _ = path.parent().map(std::fs::create_dir_all);
                let _ = std::fs::write(path, settings.to_string());
            }
            self.saved_settings = settings;
        }
        for (method, params) in out {
            // Scenery and solar views show nothing until the video runs; the scope needs a moment to start its stream.
            if method == "iscope_start_view" && params.as_ref().is_some_and(|p| p["mode"] != "star") && self.video.is_none() {
                self.video_due = Some(Instant::now() + VIDEO_START_DELAY);
                ctx.request_repaint_after(VIDEO_START_DELAY);
            }
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
            let saved: Value = settings_path().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
            let (num, flag) = (|key: &str, default: f64| saved[key].as_f64().unwrap_or(default), |key: &str, default: bool| saved[key].as_bool().unwrap_or(default));
            cc.egui_ctx.options_mut(|o| o.zoom_with_keyboard = false); // the Size buttons own the zoom
            cc.egui_ctx.all_styles_mut(|style| {
                style.spacing.item_spacing = egui::vec2(8.0, 6.0);
                style.spacing.button_padding = egui::vec2(9.0, 4.0);
                let w = &mut style.visuals.widgets;
                for v in [&mut w.noninteractive, &mut w.inactive, &mut w.hovered, &mut w.active, &mut w.open] {
                    v.corner_radius = egui::CornerRadius::same(6);
                }
                style.visuals.selection.bg_fill = egui::Color32::from_rgb(40, 80, 140);
            });
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
                recording: None,
                save_dir: saved["save_dir"].as_str().map(str::to_owned).or_else(|| Some(home_dir()?.join("StarDeck").to_string_lossy().into_owned())).unwrap_or_default(),
                ui_scale: num("ui_scale", 1.0) as f32,
                monitor_scale: None,
                show_console: false,
                saved_settings: saved.clone(),
                auto_video: false,
                auto_record: false,
                video_due: None,
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
                show_level: std::env::args().any(|a| a == "--level"),
                hud: flag("hud", true),
                crosshair: flag("crosshair", true),
                flip_h: flag("flip_h", false),
                flip_v: flag("flip_v", false),
                view_rect: egui::Rect::NOTHING,
                exp_ms: 10.0,
                lapse_interval_s: 5.0,
                lapse_minutes: 5.0,
                lapse: None,
                starlog: None,
                starlog_radius: num("starlog_radius", STARLOG_RADIUS_DEG),
                starlog_mag: num("starlog_mag", 10.0),
                starlog_temp_c: num("temp_c", 10.0),
                starlog_pressure_mb: num("pressure_mb", 910.82),
                starlog_height_m: num("height_m", 0.0),
            };
            app.connect(&cc.egui_ctx);
            if std::env::args().any(|a| a == "--live") {
                app.image_cmd("begin_streaming", &cc.egui_ctx);
            }
            app.auto_video = std::env::args().any(|a| a == "--video");
            app.auto_record = std::env::args().any(|a| a == "--record");
            if std::env::args().any(|a| a == "--timelapse") {
                let dir = app.out_path(&chrono::Local::now().format("timelapse_%Y%m%d_%H%M%S").to_string()).unwrap_or_default();
                if std::fs::create_dir_all(&dir).is_ok() {
                    app.lapse = Some(Timelapse { dir, started: Instant::now(), next: Instant::now(), saved: 0 });
                }
            }
            Ok(Box::new(app))
        }),
    )
}
