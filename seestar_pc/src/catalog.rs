//! Deep-sky object lookup and J2000 to date-of-observation precession.
//!
//! Catalogue data: OpenNGC by Mattia Verga, CC-BY-SA-4.0,
//! https://github.com/mattiaverga/OpenNGC (trimmed by tools/make_catalog.py).

use std::sync::OnceLock;

pub struct Object {
    pub name: &'static str,
    pub other: &'static str,
    pub kind: &'static str,
    pub ra_hours: f64,
    pub dec_deg: f64,
    pub mag: &'static str,
    /// name and other names, lower case, no spaces
    key: String,
}

fn squash(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).flat_map(char::to_lowercase).collect()
}

fn objects() -> &'static [Object] {
    static ALL: OnceLock<Vec<Object>> = OnceLock::new();
    ALL.get_or_init(|| {
        include_str!("catalog.tsv")
            .lines()
            .filter_map(|line| {
                let f: Vec<&str> = line.split('\t').collect();
                Some(Object {
                    name: f.first()?,
                    other: f.get(1)?,
                    kind: f.get(2)?,
                    ra_hours: f.get(3)?.parse().ok()?,
                    dec_deg: f.get(4)?.parse().ok()?,
                    mag: f.get(5)?,
                    key: format!("|{}|{}|", squash(f[0]), squash(f[1])),
                })
            })
            .collect()
    })
}

/// Objects whose name or alias contains the query; exact name matches first, then prefixes.
pub fn search(query: &str, limit: usize) -> Vec<&'static Object> {
    let q = squash(query);
    if q.is_empty() {
        return Vec::new();
    }
    let rank = |o: &Object| {
        if o.key.contains(&format!("|{q}|")) {
            0
        } else if o.key.contains(&format!("|{q}")) {
            1
        } else {
            2
        }
    };
    let mut hits: Vec<&Object> = objects().iter().filter(|o| o.key.contains(&q)).collect();
    hits.sort_by_key(|o| rank(o));
    hits.truncate(limit);
    hits
}

/// The Messier objects.
pub fn messier() -> impl Iterator<Item = &'static Object> {
    objects().iter().filter(|o| o.other.starts_with("M "))
}

pub fn julian_date_now() -> f64 {
    let unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0.0, |d| d.as_secs_f64());
    unix / 86400.0 + 2440587.5
}

/// Precesses J2000.0 mean coordinates to the mean equinox of `jd`.
/// Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 21, eq. 21.2 and 21.4.
pub fn precess_from_j2000(ra_hours: f64, dec_deg: f64, jd: f64) -> (f64, f64) {
    let t = (jd - 2451545.0) / 36525.0;
    let arcsec = |a: f64| (a / 3600.0).to_radians();
    let zeta = arcsec(2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t);
    let z = arcsec(2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t);
    let theta = arcsec(2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t);
    let (ra, dec) = ((ra_hours * 15.0).to_radians(), dec_deg.to_radians());
    let a = dec.cos() * (ra + zeta).sin();
    let b = theta.cos() * dec.cos() * (ra + zeta).cos() - theta.sin() * dec.sin();
    let c = theta.sin() * dec.cos() * (ra + zeta).cos() + theta.cos() * dec.sin();
    let ra_new = (a.atan2(b) + z).to_degrees().rem_euclid(360.0) / 15.0;
    (ra_new, c.asin().to_degrees())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_messier_and_common_names() {
        assert_eq!(search("m31", 5)[0].name, "NGC 224");
        assert_eq!(search("M 3", 5)[0].other.split('|').next(), Some("M 3"));
        assert_eq!(search("orion nebula", 5)[0].name, "NGC 1976");
        assert!(search("", 5).is_empty());
    }

    // Meeus, Astronomical Algorithms, example 21.b (theta Persei, 2028 Nov 13.19 TD).
    #[test]
    fn precession_matches_meeus_example() {
        let ra0 = 2.0 + 44.0 / 60.0 + 12.975 / 3600.0;
        let dec0 = 49.0 + 13.0 / 60.0 + 39.90 / 3600.0;
        let (ra, dec) = precess_from_j2000(ra0, dec0, 2462088.69);
        let ra_expected = 2.0 + 46.0 / 60.0 + 11.331 / 3600.0;
        let dec_expected = 49.0 + 20.0 / 60.0 + 54.54 / 3600.0;
        assert!((ra - ra_expected).abs() * 15.0 * 3600.0 < 0.5, "ra {ra}");
        assert!((dec - dec_expected).abs() * 3600.0 < 0.5, "dec {dec}");
    }
}
