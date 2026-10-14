//! StarDeck bridge: lets the StarDeck web page control a Seestar.
//!
//! A web page cannot open the scope's TCP control port or its RTSP video, so this program runs on the
//! PC and relays them on one local HTTP port:
//!   /        the web page (the same file GitHub Pages serves)
//!   /ws      WebSocket: {"cid":n,"method":..,"params":..} -> {"cid":n,"reply":{..}}; scope events as {"event":{..}}
//!   /video   the scope's live video as MJPEG (?cam=wide for the second camera)
//!
//! usage: stardeck-bridge [scope host] [--port 4791] [--lan]
//!   --lan listens on all interfaces so a phone or tablet on the same network can open the page.

#[path = "../proto.rs"]
mod proto;

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tungstenite::Message;

const PAGE: &str = include_str!("../../../docs/index.html");
const MESSIER: &str = include_str!("../../../docs/messier.js");

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |name: &str| args.iter().position(|a| a == name);
    let port: u16 = flag("--port").and_then(|i| args.get(i + 1)).and_then(|p| p.parse().ok()).unwrap_or(4791);
    let host = args.iter().enumerate()
        .find(|(i, a)| !a.starts_with("--") && flag("--port").map_or(true, |p| *i != p + 1))
        .map(|(_, a)| a.clone())
        .unwrap_or_else(|| "SeeStar.local".into());
    let bind = if flag("--lan").is_some() { "0.0.0.0" } else { "127.0.0.1" };
    let listener = match TcpListener::bind((bind, port)) {
        Ok(l) => l,
        Err(e) => return eprintln!("cannot listen on {bind}:{port}: {e}"),
    };
    println!("StarDeck bridge for {host}");
    println!("open http://127.0.0.1:{port}/ or the StarDeck page on GitHub");
    for stream in listener.incoming().flatten() {
        let host = host.clone();
        std::thread::spawn(move || {
            if let Err(e) = serve(stream, &host) {
                eprintln!("{e}");
            }
        });
    }
}

fn serve(stream: TcpStream, host: &str) -> proto::Res<()> {
    let mut head = [0u8; 2048];
    let n = stream.peek(&mut head)?;
    let request = String::from_utf8_lossy(&head[..n]).to_string();
    let path = request.split_whitespace().nth(1).unwrap_or("/").to_owned();
    let is_ws = request.to_ascii_lowercase().contains("upgrade: websocket");
    if is_ws && path.starts_with("/ws") {
        return websocket(stream, host);
    }
    let mut stream = stream;
    let mut sink = vec![0u8; n];
    stream.read_exact(&mut sink)?;
    // Chrome asks before a public page may reach a local address
    let cors = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\nAccess-Control-Allow-Headers: *\r\n";
    if request.starts_with("OPTIONS") {
        stream.write_all(format!("HTTP/1.1 204 No Content\r\n{cors}Content-Length: 0\r\n\r\n").as_bytes())?;
    } else if path.starts_with("/video") {
        video(stream, host, path.contains("cam=wide"), cors)?;
    } else if path == "/" || path.starts_with("/index.html") || path.starts_with("/?") {
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n{cors}Content-Length: {}\r\n\r\n", PAGE.len()).as_bytes())?;
        stream.write_all(PAGE.as_bytes())?;
    } else if path.starts_with("/messier.js") {
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: text/javascript; charset=utf-8\r\n{cors}Content-Length: {}\r\n\r\n", MESSIER.len()).as_bytes())?;
        stream.write_all(MESSIER.as_bytes())?;
    } else {
        stream.write_all(format!("HTTP/1.1 404 Not Found\r\n{cors}Content-Length: 0\r\n\r\n").as_bytes())?;
    }
    Ok(())
}

/// One scope connection per WebSocket. Requests run in order; a read timeout sends the keep-alive.
fn websocket(stream: TcpStream, host: &str) -> proto::Res<()> {
    let mut ws = tungstenite::accept(stream.try_clone()?).map_err(|e| e.to_string())?;
    stream.set_read_timeout(Some(Duration::from_millis(500)))?;
    let mut scope = match proto::Scope::connect(host) {
        Ok(s) => s,
        Err(e) => {
            let _ = ws.send(Message::text(json!({"error": format!("cannot reach the Seestar at {host}: {e}")}).to_string()));
            return Ok(());
        }
    };
    let _ = ws.send(Message::text(json!({"connected": host}).to_string()));
    let mut last = Instant::now();
    loop {
        match ws.read() {
            Ok(Message::Text(text)) => {
                let Ok(msg) = serde_json::from_str::<Value>(&text) else { continue };
                let method = msg["method"].as_str().unwrap_or("test_connection").to_owned();
                let params = msg.get("params").cloned().filter(|p| !p.is_null());
                let reply = scope.call(&method, params).unwrap_or_else(|e| json!({"code": -1, "error": e.to_string()}));
                ws.send(Message::text(json!({"cid": msg["cid"], "reply": reply}).to_string()))?;
                last = Instant::now();
            }
            Ok(Message::Close(_)) => return Ok(()),
            Ok(_) => {}
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
            Err(e) => return Err(e.to_string().into()),
        }
        // the scope closes a connection that stays silent for a few seconds
        if last.elapsed() > Duration::from_secs(2) {
            scope.call("test_connection", None)?;
            last = Instant::now();
        }
        for e in scope.events.drain(..) {
            ws.send(Message::text(json!({"event": e}).to_string()))?;
        }
    }
}

/// The scope's RTSP video re-encoded by ffmpeg as MJPEG, which a browser shows in an <img>.
fn video(mut stream: TcpStream, host: &str, wide: bool, cors: &str) -> proto::Res<()> {
    let addr = proto::resolve(host, if wide { 4555 } else { 4554 })?;
    let name = if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" };
    let bundled = std::env::current_exe().ok().and_then(|p| Some(p.parent()?.join(name))).filter(|p| p.exists());
    let mut child = Command::new(bundled.unwrap_or_else(|| name.into()))
        .args(["-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-rtsp_transport", "tcp"])
        .arg("-i").arg(format!("rtsp://{addr}/stream"))
        .args(["-an", "-vf", "scale=iw/2:ih/2", "-q:v", "5", "-f", "mpjpeg", "-"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .spawn()?;
    let mut out = child.stdout.take().ok_or("no ffmpeg output")?;
    stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=ffmpeg\r\nCache-Control: no-cache\r\n{cors}\r\n").as_bytes())?;
    let result = std::io::copy(&mut out, &mut stream);
    let _ = child.kill();
    let _ = child.wait();
    result.map(|_| ()).map_err(Into::into)
}
