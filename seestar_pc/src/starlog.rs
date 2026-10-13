//! Starlog: the stars that cross the centre of the field while the telescope holds an azimuth and elevation.
//!
//! A star crosses when its right ascension equals the right ascension of the field centre.
//! Its declination offset from the centre at that moment is its closest distance to the centre.
//!
//! Star data: HYG database v4.1 by David Nash, CC-BY-SA-4.0,
//! https://codeberg.org/astronexus/hyg (trimmed by tools/make_stars.py).

use std::collections::VecDeque;
use std::io::Write;
use std::sync::OnceLock;

use crate::{catalog, sky};

/// Sidereal seconds per solar second: the rate term of the mean sidereal time, 360.98564736629° per day.
/// Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 12, eq. 12.4.
const SIDEREAL_RATE: f64 = 360.98564736629 / 360.0;

/// A larger change of azimuth or elevation between two readings is a slew: no crossings are taken from it.
const STILL_DEG: f64 = 0.02;

pub struct Star {
    pub hip: &'static str,
    pub hd: &'static str,
    pub hr: &'static str,
    pub name: &'static str,
    /// Bayer / Flamsteed designation with the constellation
    pub bf: &'static str,
    pub con: &'static str,
    pub ra_j2000: f64,
    pub dec_j2000: f64,
    pub mag: f64,
    pub spect: &'static str,
    /// hours, mean equinox of date, proper motion applied
    pub ra: f64,
    /// degrees, mean equinox of date, proper motion applied
    pub dec: f64,
}

impl Star {
    pub fn label(&self) -> String {
        match (self.name, self.bf, self.hip, self.hd) {
            (n, ..) if !n.is_empty() => n.to_owned(),
            (_, bf, ..) if !bf.is_empty() => bf.to_owned(),
            (_, _, hip, _) if !hip.is_empty() => format!("HIP {hip}"),
            (.., hd) if !hd.is_empty() => format!("HD {hd}"),
            _ => format!("HR {}", self.hr),
        }
    }
}

/// All stars, sorted by declination of date. Coordinates are brought to the date of the first call.
pub fn stars() -> &'static [Star] {
    static ALL: OnceLock<Vec<Star>> = OnceLock::new();
    ALL.get_or_init(|| {
        let jd = catalog::julian_date_now();
        let years = (jd - 2451545.0) / 365.25;
        let mut all: Vec<Star> = include_str!("stars.tsv")
            .lines()
            .filter_map(|line| {
                let f: Vec<&str> = line.split('\t').collect();
                let (ra_j2000, dec_j2000): (f64, f64) = (f.get(6)?.parse().ok()?, f.get(7)?.parse().ok()?);
                let (pm_ra, pm_dec): (f64, f64) = (f.get(8)?.parse().ok()?, f.get(9)?.parse().ok()?);
                // proper motion is in milliarcseconds per year; the RA term is on the sky (includes cos Dec)
                let dec_now = dec_j2000 + pm_dec / 3.6e6 * years;
                let ra_now = ra_j2000 + pm_ra / 3.6e6 * years / dec_j2000.to_radians().cos().max(1e-6) / 15.0;
                let (ra, dec) = catalog::precess_from_j2000(ra_now, dec_now, jd);
                Some(Star {
                    hip: f[0],
                    hd: f[1],
                    hr: f[2],
                    name: f[3],
                    bf: f[4],
                    con: f[5],
                    ra_j2000,
                    dec_j2000,
                    mag: f.get(10)?.parse().ok()?,
                    spect: f.get(11)?,
                    ra,
                    dec,
                })
            })
            .collect();
        all.sort_by(|a, b| a.dec.total_cmp(&b.dec));
        all
    })
}

/// Hours of right ascension from the centre to the star. Positive: the star is east of the centre and will cross.
fn east_of(star_ra: f64, centre_ra: f64) -> f64 {
    (star_ra - centre_ra + 12.0).rem_euclid(24.0) - 12.0
}

/// Stars within `radius_deg` of the centre declination and not fainter than `mag_limit`.
fn band(centre_dec: f64, radius_deg: f64, mag_limit: f64) -> impl Iterator<Item = &'static Star> {
    let all = stars();
    let from = all.partition_point(|s| s.dec < centre_dec - radius_deg);
    all[from..].iter().take_while(move |s| s.dec <= centre_dec + radius_deg).filter(move |s| s.mag <= mag_limit)
}

/// Stars that cross in the next `within_s` seconds if the pointing stays fixed: (star, seconds from now), soonest first.
pub fn upcoming(centre: (f64, f64), radius_deg: f64, mag_limit: f64, within_s: f64) -> Vec<(&'static Star, f64)> {
    let mut list: Vec<_> = band(centre.1, radius_deg, mag_limit)
        .map(|s| (s, east_of(s.ra, centre.0) * 3600.0 / SIDEREAL_RATE))
        .filter(|&(_, t)| t > 0.0 && t <= within_s)
        .collect();
    list.sort_by(|a, b| a.1.total_cmp(&b.1));
    list
}

/// One pointing reading.
struct Reading {
    unix: f64,
    az: f64,
    el: f64,
    /// field centre: RA hours, Dec degrees, of date
    centre: (f64, f64),
}

/// An open star log file.
pub struct Log {
    pub path: std::path::PathBuf,
    file: std::fs::File,
    prev: Option<Reading>,
    pub count: u32,
    /// newest last, for display
    pub recent: VecDeque<String>,
}

impl Log {
    pub fn create(path: std::path::PathBuf) -> std::io::Result<Log> {
        let mut file = std::fs::File::create(&path)?;
        writeln!(
            file,
            "utc,lat_deg,lon_deg,az_deg,el_deg,temp_c,pressure_mb,refraction_deg,centre_ra_h,centre_dec_deg,hip,hd,hr,name,bayer_flamsteed,constellation,mag,spectral_type,\
             star_ra_h,star_dec_deg,star_ra_j2000_h,star_dec_j2000_deg,dec_offset_deg"
        )?;
        Ok(Log { path, file, prev: None, count: 0, recent: VecDeque::new() })
    }

    /// Takes a pointing reading and writes the stars that crossed the centre since the reading before it.
    /// Azimuth is from north through east. Elevation is apparent; `air` is (temperature °C, pressure mbar) for refraction.
    /// Longitude is positive east.
    #[allow(clippy::too_many_arguments)]
    pub fn step(&mut self, unix: f64, az: f64, el: f64, lat: f64, lon: f64, air: (f64, f64), radius_deg: f64, mag_limit: f64) -> std::io::Result<()> {
        let refraction = sky::refraction_deg(el, air.0, air.1);
        let centre = sky::eq_from_horizontal(az, el - refraction, unix / 86400.0 + 2440587.5, lat, lon);
        let now = Reading { unix, az, el, centre };
        let Some(prev) = self.prev.replace(Reading { ..now }) else { return Ok(()) };
        let az_step = ((az - prev.az + 180.0).rem_euclid(360.0) - 180.0) * el.to_radians().cos();
        if az_step.abs() > STILL_DEG || (el - prev.el).abs() > STILL_DEG {
            return Ok(());
        }
        for s in band(centre.1, radius_deg, mag_limit) {
            let (before, after) = (east_of(s.ra, prev.centre.0), east_of(s.ra, centre.0));
            if !(before > 0.0 && after <= 0.0 && before - after < 1.0) {
                continue;
            }
            let t = prev.unix + (unix - prev.unix) * before / (before - after);
            let utc = chrono::DateTime::from_timestamp(t.floor() as i64, (t.fract() * 1e9) as u32).unwrap_or_default();
            let offset = s.dec - centre.1;
            writeln!(
                self.file,
                "{},{lat:.6},{lon:.6},{az:.4},{el:.4},{},{},{refraction:.5},{:.6},{:.5},{},{},{},{},{},{},{},{},{:.6},{:.5},{:.6},{:.5},{offset:+.5}",
                utc.format("%Y-%m-%dT%H:%M:%S%.3fZ"),
                air.0, air.1, centre.0, centre.1, s.hip, s.hd, s.hr, s.name, s.bf, s.con, s.mag, s.spect, s.ra, s.dec, s.ra_j2000, s.dec_j2000,
            )?;
            self.count += 1;
            if self.recent.len() >= 50 {
                self.recent.pop_front();
            }
            self.recent.push_back(format!("{}  {}  mag {}  {offset:+.3}°", utc.format("%H:%M:%S%.3f"), s.label(), s.mag));
        }
        self.file.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_loads_sorted() {
        let all = stars();
        assert!(all.len() > 119_000, "{}", all.len());
        assert!(all.windows(2).all(|w| w[0].dec <= w[1].dec));
        let sirius = all.iter().find(|s| s.name == "Sirius").unwrap();
        assert_eq!((sirius.hip, sirius.label().as_str()), ("32349", "Sirius"));
        // of-date position is within a degree of J2000
        assert!(sky::separation_deg((sirius.ra, sirius.dec), (sirius.ra_j2000, sirius.dec_j2000)) < 1.0);
    }

    #[test]
    fn logs_vega_when_it_crosses_the_centre() {
        let vega = stars().iter().find(|s| s.name == "Vega").unwrap();
        // no air (pressure 0), so the elevation is the true altitude
        // due south at the elevation of Vega's meridian passage, latitude 40° N, longitude 0°
        let (az, el, lat, lon) = (180.0, 90.0 - 40.0 + vega.dec, 40.0, 0.0);
        let centre_at = |unix: f64| sky::eq_from_horizontal(az, el, unix / 86400.0 + 2440587.5, lat, lon);
        let mut t0 = catalog::julian_date_now().mul_add(86400.0, -2440587.5 * 86400.0);
        for _ in 0..3 {
            t0 += east_of(vega.ra, centre_at(t0).0) * 3600.0 / SIDEREAL_RATE;
        }
        assert!((centre_at(t0).1 - vega.dec).abs() < 1e-6);
        assert_eq!(upcoming(centre_at(t0 - 30.0), 0.35, 6.0, 600.0)[0].0.name, "Vega");

        let path = std::env::temp_dir().join(format!("starlog_test_{}.csv", std::process::id()));
        let mut log = Log::create(path.clone()).unwrap();
        log.step(t0 - 5.0, az, el, lat, lon, (10.0, 0.0), 0.35, 6.0).unwrap();
        log.step(t0 + 3.0, az, el, lat, lon, (10.0, 0.0), 0.35, 6.0).unwrap();
        // a slew to another azimuth sweeps past stars; none of them is a crossing
        log.step(t0 + 4.0, az + 30.0, el, lat, lon, (10.0, 0.0), 0.35, 6.0).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        let _ = std::fs::remove_file(&path);
        let rows: Vec<&str> = text.lines().skip(1).collect();
        assert_eq!(rows.len(), 1, "{text}");
        assert!(rows[0].contains(",Vega,"), "{text}");
        let logged = chrono::DateTime::parse_from_rfc3339(rows[0].split(',').next().unwrap()).unwrap();
        let logged = logged.timestamp_millis() as f64 / 1000.0;
        assert!((logged - t0).abs() < 0.01, "logged {logged} expected {t0}");
        assert_eq!(text.lines().next().unwrap().split(',').count(), rows[0].split(',').count());
    }
}
