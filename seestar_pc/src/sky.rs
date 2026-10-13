//! Planet positions and altitude above the horizon.
//! Uses the `astro` crate (Meeus, J. (1998). Astronomical Algorithms, 2nd ed.; VSOP87 planetary theory).

pub use astro::planet::Planet;

pub const PLANETS: [(&str, Planet); 7] = [
    ("Mercury", Planet::Mercury),
    ("Venus", Planet::Venus),
    ("Mars", Planet::Mars),
    ("Jupiter", Planet::Jupiter),
    ("Saturn", Planet::Saturn),
    ("Uranus", Planet::Uranus),
    ("Neptune", Planet::Neptune),
];

fn eq_from_ecl(long: f64, lat: f64, jd: f64) -> (f64, f64) {
    let oblq = astro::ecliptic::mn_oblq_IAU(jd);
    let ra = astro::coords::asc_frm_ecl(long, lat, oblq).to_degrees().rem_euclid(360.0) / 15.0;
    (ra, astro::coords::dec_frm_ecl(long, lat, oblq).to_degrees())
}

/// Apparent geocentric RA (hours) and Dec (degrees) of date.
pub fn planet_ra_dec(planet: &Planet, jd: f64) -> (f64, f64) {
    let (ecl, _) = astro::planet::geocent_apprnt_ecl_coords(planet, jd);
    eq_from_ecl(ecl.long, ecl.lat, jd)
}

pub fn sun_ra_dec(jd: f64) -> (f64, f64) {
    let (ecl, _) = astro::sun::geocent_ecl_pos(jd);
    eq_from_ecl(ecl.long, ecl.lat, jd)
}

pub fn moon_ra_dec(jd: f64) -> (f64, f64) {
    let (ecl, _) = astro::lunar::geocent_ecl_pos(jd);
    eq_from_ecl(ecl.long, ecl.lat, jd)
}

/// Altitude in degrees, no refraction. Longitude is positive east.
pub fn altitude_deg(ra_hours: f64, dec_deg: f64, jd: f64, lat_deg: f64, lon_east_deg: f64) -> f64 {
    let hour_angle = astro::time::mn_sidr(jd) + lon_east_deg.to_radians() - (ra_hours * 15.0).to_radians();
    astro::coords::alt_frm_eq(hour_angle, dec_deg.to_radians(), lat_deg.to_radians()).to_degrees()
}

/// Approximate air pressure in millibars at a height above sea level in metres.
pub fn pressure_from_height_mb(height_m: f64) -> f64 {
    1013.25 * (1.0 - (0.0065 * height_m) / 288.15).powf(5.255)
}

/// Atmospheric refraction in degrees at an apparent altitude in degrees, for a temperature in °C and
/// a pressure in millibars at the observer. Subtract it from the apparent altitude to get the true altitude.
/// Urban, S. E., & Seidelmann, P. K. (Eds.). (2013). Explanatory Supplement to the Astronomical Almanac (3rd ed.).
/// University Science Books, sec. 7.2.7.3, eq. 7.90.
pub fn refraction_deg(apparent_alt_deg: f64, temp_c: f64, pressure_mb: f64) -> f64 {
    let h = apparent_alt_deg.max(-1.0);
    0.28 * pressure_mb / (temp_c + 273.0) * 0.0167 / (h + 7.31 / (h + 4.4)).to_radians().tan()
}

/// RA (hours) and Dec (degrees) of date for a direction given by azimuth and true altitude in degrees.
/// Azimuth is from north through east. Longitude is positive east.
/// Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 13 (azimuth there is from the south).
pub fn eq_from_horizontal(az_deg: f64, alt_deg: f64, jd: f64, lat_deg: f64, lon_east_deg: f64) -> (f64, f64) {
    let (az, alt, lat) = (az_deg.to_radians(), alt_deg.to_radians(), lat_deg.to_radians());
    let dec = (lat.sin() * alt.sin() + lat.cos() * alt.cos() * az.cos()).clamp(-1.0, 1.0).asin();
    let hour_angle = (-az.sin() * alt.cos()).atan2(lat.cos() * alt.sin() - lat.sin() * alt.cos() * az.cos());
    let ra = astro::time::mn_sidr(jd) + lon_east_deg.to_radians() - hour_angle;
    (ra.to_degrees().rem_euclid(360.0) / 15.0, dec.to_degrees())
}

/// Angle between two directions in degrees (RA in hours, Dec in degrees).
pub fn separation_deg(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (ra1, d1, ra2, d2) = ((a.0 * 15.0).to_radians(), a.1.to_radians(), (b.0 * 15.0).to_radians(), b.1.to_radians());
    (d1.sin() * d2.sin() + d1.cos() * d2.cos() * (ra1 - ra2).cos()).clamp(-1.0, 1.0).acos().to_degrees()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Reference: JPL Horizons, apparent RA/Dec, geocentric, 2026-Oct-13 00:00 UT (JD 2461326.5).
    #[test]
    fn planets_match_horizons() {
        for (planet, ra_deg, dec_deg) in [(Planet::Jupiter, 144.21573, 14.89053), (Planet::Mars, 131.29327, 19.31479), (Planet::Saturn, 10.84219, 1.71517)] {
            let (ra, dec) = planet_ra_dec(&planet, 2461326.5);
            assert!((ra * 15.0 - ra_deg).abs() < 0.02, "ra {} vs {ra_deg}", ra * 15.0);
            assert!((dec - dec_deg).abs() < 0.02, "dec {dec} vs {dec_deg}");
        }
    }

    #[test]
    fn separation() {
        assert!((separation_deg((0.0, 0.0), (6.0, 0.0)) - 90.0).abs() < 1e-9);
        assert!((separation_deg((3.0, 10.0), (3.0, -20.0)) - 30.0).abs() < 1e-9);
    }

    #[test]
    fn refraction() {
        assert_eq!(pressure_from_height_mb(0.0), 1013.25);
        assert!((pressure_from_height_mb(1000.0) - 898.76).abs() < 0.01, "{}", pressure_from_height_mb(1000.0));
        // reference values: eq. 7.90 evaluated separately, T = 10 °C, P = 1010 mbar, in arcminutes
        let arcmin = |h: f64| refraction_deg(h, 10.0, 1010.0) * 60.0;
        assert!((arcmin(0.0) - 34.5221).abs() < 1e-3, "{}", arcmin(0.0));
        assert!((arcmin(5.0) - 9.8959).abs() < 1e-3, "{}", arcmin(5.0));
        assert!((arcmin(15.0) - 3.6409).abs() < 1e-3, "{}", arcmin(15.0));
        assert!((arcmin(45.0) - 0.9961).abs() < 1e-3, "{}", arcmin(45.0));
        assert_eq!(refraction_deg(20.0, 10.0, 0.0), 0.0);
    }

    #[test]
    fn horizontal_to_equatorial() {
        let (jd, lat, lon) = (2461326.5, 44.0, -116.0);
        let lst_hours = (astro::time::mn_sidr(jd).to_degrees() + lon).rem_euclid(360.0) / 15.0;
        // north at the altitude of the pole
        assert!((eq_from_horizontal(0.0, lat, jd, lat, lon).1 - 90.0).abs() < 1e-6);
        // due south on the meridian: RA = local sidereal time, Dec = altitude + latitude - 90
        let (ra, dec) = eq_from_horizontal(180.0, 30.0, jd, lat, lon);
        assert!((ra - lst_hours).abs() < 1e-9 && (dec - (30.0 + lat - 90.0)).abs() < 1e-9, "{ra} {dec}");
        // due east on the horizon: 6 h east of the meridian, on the equator
        let (ra, dec) = eq_from_horizontal(90.0, 0.0, jd, lat, lon);
        assert!((ra - (lst_hours + 6.0).rem_euclid(24.0)).abs() < 1e-9 && dec.abs() < 1e-9, "{ra} {dec}");
        // any direction: the altitude computed back from RA/Dec is the altitude put in
        for (az, alt) in [(37.0, 12.0), (250.0, 61.0), (301.5, 80.0)] {
            let (ra, dec) = eq_from_horizontal(az, alt, jd, lat, lon);
            assert!((altitude_deg(ra, dec, jd, lat, lon) - alt).abs() < 1e-6, "az {az}");
        }
    }

    #[test]
    fn altitude_of_pole_is_latitude() {
        assert!((altitude_deg(5.0, 90.0, 2461326.5, 44.0, -116.0) - 44.0).abs() < 1e-6);
        // an object on the meridian at the equator's zenith: RA = local sidereal time, Dec = 0, lat 0
        let lst_hours = (astro::time::mn_sidr(2461326.5).to_degrees() + 10.0).rem_euclid(360.0) / 15.0;
        assert!((altitude_deg(lst_hours, 0.0, 2461326.5, 0.0, 10.0) - 90.0).abs() < 1e-6);
    }
}
