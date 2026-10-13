//! Seestar image channel (TCP 4800 tele, 4804 wide): 80-byte header + payload.

use std::io::{Cursor, Read, Write};
use std::net::TcpStream;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::Duration;

use crate::proto::{resolve, Res};

pub const TELE_PORT: u16 = 4800;
pub const WIDE_PORT: u16 = 4804;
const HEADER_LEN: usize = 80;
const MAGIC: u16 = 0x03C3;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Header {
    pub length: usize,
    pub big_endian: bool,
    pub img_type: u8,
    pub data_type: u8,
    pub id: u8,
    pub width: usize,
    pub height: usize,
}

impl Header {
    pub fn parse(b: &[u8; HEADER_LEN]) -> Res<Self> {
        let u16at = |o: usize| u16::from_be_bytes([b[o], b[o + 1]]);
        if u16at(0) != MAGIC {
            return Err(format!("bad frame magic {:#06x}", u16at(0)).into());
        }
        let length = i32::from_be_bytes([b[6], b[7], b[8], b[9]]);
        Ok(Header {
            length: usize::try_from(length).map_err(|_| "negative frame length")?,
            big_endian: b[12] != 0,
            img_type: b[13],
            data_type: b[14],
            id: b[15],
            width: u16at(16) as usize,
            height: u16at(18) as usize,
        })
    }
}

/// Display settings the UI can change between frames.
#[derive(Clone, Copy)]
pub struct Knobs {
    /// Bayer layout: 0 RGGB, 1 BGGR, 2 GRBG, 3 GBRG.
    pub bayer: u8,
    pub gamma: f32,
    pub swap_rb: bool,
}

impl Default for Knobs {
    fn default() -> Self {
        Knobs { bayer: 2, gamma: 0.5, swap_rb: false }
    }
}

pub struct Frame {
    pub width: usize,
    pub height: usize,
    pub rgba: Vec<u8>,
    pub info: String,
}

/// 16-bit Bayer mosaic to half-resolution RGB (one output pixel per 2x2 cell).
// ponytail: superpixel debayer halves resolution; switch to bilinear if full-res preview matters.
fn debayer(px: &[u16], w: usize, h: usize, bayer: u8) -> (Vec<[u16; 3]>, usize, usize) {
    // index of R within the 2x2 cell (row*2 + col); B is diagonally opposite
    let r = [0usize, 3, 1, 2][bayer as usize & 3];
    let (ow, oh) = (w / 2, h / 2);
    let mut out = Vec::with_capacity(ow * oh);
    for y in 0..oh {
        for x in 0..ow {
            let c = [
                px[2 * y * w + 2 * x],
                px[2 * y * w + 2 * x + 1],
                px[(2 * y + 1) * w + 2 * x],
                px[(2 * y + 1) * w + 2 * x + 1],
            ];
            let g: u32 = (0..4).filter(|&i| i != r && i != 3 - r).map(|i| c[i] as u32).sum();
            out.push([c[r], (g / 2) as u16, c[3 - r]]);
        }
    }
    (out, ow, oh)
}

/// Per-channel percentile stretch plus gamma, to 8-bit RGBA.
fn stretch(rgb: &[[u16; 3]], gamma: f32) -> Vec<u8> {
    let mut lut = vec![[0u8; 65536]; 3];
    for (ch, lut) in lut.iter_mut().enumerate() {
        let mut hist = vec![0u32; 65536];
        for p in rgb {
            hist[p[ch] as usize] += 1;
        }
        let pct = |frac: f64| {
            let target = (rgb.len() as f64 * frac) as u32;
            let mut acc = 0;
            hist.iter().position(|&n| { acc += n; acc > target }).unwrap_or(65535)
        };
        let (lo, hi) = (pct(0.01), pct(0.999));
        // a flat (e.g. saturated) channel has no range to stretch; show it at its true level
        let (lo, hi) = if hi < lo + 256 { (0, 65535) } else { (lo, hi) };
        let span = (hi.max(lo + 1) - lo) as f32;
        for (v, out) in lut.iter_mut().enumerate() {
            let t = ((v as f32 - lo as f32) / span).clamp(0.0, 1.0);
            *out = (t.powf(gamma) * 255.0) as u8;
        }
    }
    let mut out = Vec::with_capacity(rgb.len() * 4);
    for p in rgb {
        out.extend_from_slice(&[lut[0][p[0] as usize], lut[1][p[1] as usize], lut[2][p[2] as usize], 255]);
    }
    out
}

pub fn decode(h: &Header, payload: &[u8], k: Knobs) -> Res<Frame> {
    let mut unzipped = Vec::new();
    let data: &[u8] = if h.data_type == 3 && h.id != 4 {
        zip::ZipArchive::new(Cursor::new(payload))?.by_index(0)?.read_to_end(&mut unzipped)?;
        &unzipped
    } else {
        payload
    };
    let tag = format!("type {} {}x{} {} bytes", h.img_type, h.width, h.height, data.len());

    if h.img_type == 4 || data.starts_with(&[0xFF, 0xD8]) {
        let img = image::load_from_memory(data)?.to_rgba8();
        return Ok(Frame { width: img.width() as usize, height: img.height() as usize, rgba: img.into_raw(), info: format!("jpeg {tag}") });
    }

    let n = h.width * h.height;
    if n == 0 {
        // the scope answers with plain text when it has nothing to send
        return Err(String::from_utf8_lossy(data).into_owned().into());
    }
    let words = |d: &[u8]| -> Vec<u16> {
        d.chunks_exact(2)
            .map(|c| if h.big_endian { u16::from_be_bytes([c[0], c[1]]) } else { u16::from_le_bytes([c[0], c[1]]) })
            .collect()
    };
    let (mut rgb, w, hh, kind) = if n > 0 && data.len() == n * 2 {
        let (rgb, w, hh) = debayer(&words(data), h.width, h.height, k.bayer);
        (rgb, w, hh, "bayer16")
    } else if n > 0 && data.len() == n * 6 {
        (words(data).chunks_exact(3).map(|c| [c[0], c[1], c[2]]).collect(), h.width, h.height, "rgb48")
    } else {
        return Err(format!("unknown pixel layout: {tag}").into());
    };
    if k.swap_rb {
        rgb.iter_mut().for_each(|p| p.swap(0, 2));
    }
    Ok(Frame { width: w, height: hh, rgba: stretch(&rgb, k.gamma), info: format!("{kind} {tag}") })
}

/// Connects to an image port. `on_frame` gets every image frame (header + raw payload).
/// Returns a sender for commands ("begin_streaming", "stop_streaming",
/// "get_current_img", "get_stacked_img"); dropping it closes the connection.
pub fn spawn(
    host: &str,
    port: u16,
    mut on_frame: impl FnMut(Res<(Header, Vec<u8>)>) + Send + 'static,
) -> Res<mpsc::Sender<&'static str>> {
    let mut r = TcpStream::connect_timeout(&resolve(host, port)?, Duration::from_secs(5))?;
    r.set_nodelay(true)?;
    let mut w = r.try_clone()?;
    let (tx, rx) = mpsc::channel::<&'static str>();

    std::thread::spawn(move || loop {
        let line = match rx.recv_timeout(Duration::from_secs(4)) {
            Ok(method) => format!("{{\"method\": \"{method}\", \"id\": 2}}\r\n"),
            Err(RecvTimeoutError::Timeout) => "{\"method\": \"test_connection\", \"id\": 1}\r\n".to_owned(),
            Err(RecvTimeoutError::Disconnected) => break w.shutdown(std::net::Shutdown::Both).unwrap_or(()),
        };
        if w.write_all(line.as_bytes()).is_err() {
            break;
        }
    });

    std::thread::spawn(move || loop {
        let mut read = || -> Res<Option<(Header, Vec<u8>)>> {
            let mut hb = [0u8; HEADER_LEN];
            r.read_exact(&mut hb)?;
            let h = Header::parse(&hb)?;
            let mut payload = vec![0u8; h.length];
            r.read_exact(&mut payload)?;
            // id 1 = heartbeat reply; 4-byte payloads are status words, not images
            Ok((h.id != 1 && h.length > 4).then_some((h, payload)))
        };
        match read() {
            Ok(Some(f)) => on_frame(Ok(f)),
            Ok(None) => {}
            Err(e) => break on_frame(Err(e)),
        }
    });
    Ok(tx)
}

/// Reads one binary PPM (P6, 8-bit) image.
fn ppm_frame(r: &mut impl Read) -> Res<Frame> {
    let mut token = || -> Res<String> {
        let mut s = String::new();
        loop {
            let mut b = [0u8];
            r.read_exact(&mut b)?;
            match (b[0].is_ascii_whitespace(), s.is_empty()) {
                (true, true) => {}
                (true, false) => return Ok(s),
                (false, _) => s.push(b[0] as char),
            }
        }
    };
    if token()? != "P6" {
        return Err("not a PPM frame".into());
    }
    let (width, height, _max): (usize, usize, usize) = (token()?.parse()?, token()?.parse()?, token()?.parse()?);
    let mut rgb = vec![0u8; width * height * 3];
    r.read_exact(&mut rgb)?;
    let rgba = rgb.chunks_exact(3).flat_map(|p| [p[0], p[1], p[2], 255]).collect();
    Ok(Frame { width, height, rgba, info: format!("video {width}x{height}") })
}

/// Plays the scope's RTSP video (scenery / solar views) through ffmpeg, half size.
/// Kill the returned child to stop.
/// Starts ffmpeg on the RTSP stream. Frames go to `on_frame`; with `record`, the stream is also written
/// unchanged to that mp4 file. Send "q" on stdin to stop and close the file.
pub fn spawn_video(url: &str, record: Option<&std::path::Path>, mut on_frame: impl FnMut(Res<Frame>) + Send + 'static) -> Res<std::process::Child> {
    use std::process::{Command, Stdio};
    // a copy shipped next to the app wins over one on the PATH
    let name = if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" };
    let bundled = std::env::current_exe().ok().and_then(|p| Some(p.parent()?.join(name))).filter(|p| p.exists());
    let mut command = Command::new(bundled.unwrap_or_else(|| name.into()));
    command
        .args(["-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-rtsp_transport", "tcp", "-i", url])
        .args(["-vf", "scale=iw/2:ih/2", "-f", "image2pipe", "-c:v", "ppm", "-"]);
    if let Some(path) = record {
        // fragmented mp4: the file stays playable if ffmpeg is killed
        command.args(["-an", "-c:v", "copy", "-movflags", "+frag_keyframe+empty_moov", "-f", "mp4"]).arg(path);
    }
    let mut child = command.stdin(Stdio::piped()).stdout(Stdio::piped()).spawn()?;
    let mut out = std::io::BufReader::new(child.stdout.take().ok_or("no ffmpeg stdout")?);
    std::thread::spawn(move || loop {
        let f = ppm_frame(&mut out);
        let done = f.is_err();
        on_frame(f);
        if done {
            break;
        }
    });
    Ok(child)
}

/// Writes RGBA frames to an mp4 file through ffmpeg. Used for recordings with the HUD burned in;
/// frame times are the times the frames arrive.
pub struct HudRecorder {
    child: std::process::Child,
    stdin: Option<std::process::ChildStdin>,
    frame_len: usize,
}

impl HudRecorder {
    pub fn start(path: &std::path::Path, width: usize, height: usize) -> Res<Self> {
        use std::process::{Command, Stdio};
        let name = if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" };
        let bundled = std::env::current_exe().ok().and_then(|p| Some(p.parent()?.join(name))).filter(|p| p.exists());
        let mut child = Command::new(bundled.unwrap_or_else(|| name.into()))
            .args(["-loglevel", "error", "-use_wallclock_as_timestamps", "1", "-f", "rawvideo", "-pix_fmt", "rgba"])
            .args(["-s", &format!("{width}x{height}"), "-i", "-"])
            // mpeg4 is in every ffmpeg build; fragmented mp4 stays playable if ffmpeg is killed
            .args(["-an", "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p", "-fps_mode", "cfr", "-r", "30"])
            .args(["-movflags", "+frag_keyframe+empty_moov", "-f", "mp4", "-y"])
            .arg(path)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .spawn()?;
        let stdin = child.stdin.take();
        Ok(HudRecorder { child, stdin, frame_len: width * height * 4 })
    }

    /// False when the frame could not be written (wrong size or ffmpeg gone).
    pub fn write(&mut self, rgba: &[u8]) -> bool {
        rgba.len() == self.frame_len && self.stdin.as_mut().is_some_and(|s| s.write_all(rgba).is_ok())
    }
}

impl Drop for HudRecorder {
    fn drop(&mut self) {
        drop(self.stdin.take()); // end of input lets ffmpeg close the file
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_and_bayer_decode() {
        let mut b = [0u8; HEADER_LEN];
        b[0..2].copy_from_slice(&MAGIC.to_be_bytes());
        b[6..10].copy_from_slice(&8i32.to_be_bytes());
        b[13] = 1;
        b[16..18].copy_from_slice(&2u16.to_be_bytes());
        b[18..20].copy_from_slice(&2u16.to_be_bytes());
        let h = Header::parse(&b).unwrap();
        assert_eq!((h.length, h.img_type, h.width, h.height), (8, 1, 2, 2));

        // GRBG cell: G=10 R=20 / B=30 G=50
        let (rgb, w, hh) = debayer(&[10, 20, 30, 50], 2, 2, 2);
        assert_eq!((rgb, w, hh), (vec![[20, 30, 30]], 1, 1));

        b[0] = 0;
        assert!(Header::parse(&b).is_err());

        let f = ppm_frame(&mut &b"P6\n1 1\n255\n\x01\x02\x03"[..]).unwrap();
        assert_eq!((f.width, f.height, f.rgba), (1, 1, vec![1, 2, 3, 255]));
    }
}
