//! Sky-angle reticle: shapes sized in arcseconds, pixel scale from the camera data, crossing time.

/// Arcseconds per radian.
pub const ARCSEC_PER_RAD: f64 = 206264.806;

/// Drift rate of the stars past a fixed pointing at the celestial equator, in arcseconds per second
/// of time: 360° per sidereal day of 86164.0905 s (Urban & Seidelmann, 2013, Explanatory Supplement
/// to the Astronomical Almanac, 3rd ed., sec. 3.6). At declination δ the rate is this times cos δ.
pub const SIDEREAL_ARCSEC_PER_S: f64 = 360.0 * 3600.0 / 86164.0905;

/// Size presets, arcseconds: 30″ to 30′.
pub const PRESETS: [f64; 8] = [30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1200.0, 1800.0];

/// Video zoom steps (`rtsp_roi_index` 0, 1, 2).
pub const ZOOMS: [f64; 3] = [1.0, 2.0, 4.0];

/// Sky angle of one camera pixel in arcseconds: pixel size over focal length, as a small angle.
pub fn arcsec_per_pixel(pixel_um: f64, focal_mm: f64) -> f64 {
    ARCSEC_PER_RAD * (pixel_um / 1000.0) / focal_mm
}

/// Seconds for a star at declination `dec_deg` to drift across `size_arcsec` while the pointing is fixed.
pub fn crossing_seconds(size_arcsec: f64, dec_deg: f64) -> f64 {
    size_arcsec / (SIDEREAL_ARCSEC_PER_S * dec_deg.to_radians().cos().abs())
}

pub fn format_arcsec(a: f64) -> String {
    if a >= 3600.0 {
        format!("{:.2}°", a / 3600.0)
    } else if a >= 60.0 {
        format!("{:.2}′", a / 60.0)
    } else {
        format!("{a:.1}″")
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Shape {
    Off,
    Cross,
    Circle,
    Box,
    CrossCircle,
}

impl Shape {
    pub const ALL: [Shape; 5] = [Shape::Off, Shape::Cross, Shape::Circle, Shape::Box, Shape::CrossCircle];

    pub fn label(self) -> &'static str {
        match self {
            Shape::Off => "Off",
            Shape::Cross => "Cross",
            Shape::Circle => "Circle",
            Shape::Box => "Box",
            Shape::CrossCircle => "Cross + circle",
        }
    }

    pub fn index(self) -> usize {
        Shape::ALL.iter().position(|&s| s == self).unwrap_or(0)
    }

    pub fn from_index(i: usize) -> Shape {
        Shape::ALL.get(i).copied().unwrap_or(Shape::Cross)
    }
}

/// One drawing primitive in picture units: a line (x0, y0, x1, y1), a circle (cx, cy, radius),
/// or a box (cx, cy, half width, half height).
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Prim {
    Line(f32, f32, f32, f32),
    Circle(f32, f32, f32),
    Rect(f32, f32, f32, f32),
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Reticle {
    pub shape: Shape,
    /// diameter (circle) or side (box) in arcseconds
    pub size_arcsec: f64,
    /// two more rings at 2× and 3× the size
    pub rings: bool,
    /// size and crossing time on the HUD
    pub label: bool,
}

impl Default for Reticle {
    fn default() -> Self {
        Reticle { shape: Shape::Cross, size_arcsec: 600.0, rings: false, label: true }
    }
}

impl Reticle {
    /// The shape has a size in sky angle.
    pub fn sized(&self) -> bool {
        matches!(self.shape, Shape::Circle | Shape::Box | Shape::CrossCircle)
    }

    /// Primitives for a reticle centred in a picture of `w` × `h`. `px_per_arcsec` converts sky angle to
    /// picture units; without it (camera data not read yet) the reticle is a plain crosshair.
    pub fn shapes(&self, w: f32, h: f32, px_per_arcsec: Option<f64>) -> Vec<Prim> {
        let mut out = Vec::new();
        if self.shape == Shape::Off {
            return out;
        }
        let (cx, cy) = (w / 2.0, h / 2.0);
        let (arm, gap) = (w.min(h) / 12.0, w.min(h) / 60.0);
        if matches!(self.shape, Shape::Cross | Shape::CrossCircle) || px_per_arcsec.is_none() {
            out.push(Prim::Line(cx - arm, cy, cx - gap, cy));
            out.push(Prim::Line(cx + gap, cy, cx + arm, cy));
            out.push(Prim::Line(cx, cy - arm, cx, cy - gap));
            out.push(Prim::Line(cx, cy + gap, cx, cy + arm));
        }
        let Some(scale) = px_per_arcsec else { return out };
        let size = (self.size_arcsec * scale) as f32;
        let counts: &[f32] = if self.rings { &[1.0, 2.0, 3.0] } else { &[1.0] };
        for &k in counts {
            match self.shape {
                Shape::Circle | Shape::CrossCircle => out.push(Prim::Circle(cx, cy, size * k / 2.0)),
                Shape::Box => out.push(Prim::Rect(cx, cy, size * k / 2.0, size * k / 2.0)),
                _ => {}
            }
        }
        out
    }

    /// HUD line with the size and, when the declination is known, the crossing time.
    pub fn hud_line(&self, dec_deg: Option<f64>) -> Option<String> {
        if !self.label || !self.sized() {
            return None;
        }
        let crossing = dec_deg.map_or(String::new(), |d| format!("  crossing {:.2} s", crossing_seconds(self.size_arcsec, d)));
        Some(format!("Reticle {}{crossing}", format_arcsec(self.size_arcsec)))
    }
}

/// One stopwatch measurement of a crossing.
pub struct Lap {
    pub start_utc_ms: i64,
    pub seconds: f64,
    pub size_arcsec: f64,
    pub expected: Option<f64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scale_and_crossing() {
        // S50: 2.9 µm pixels at 250 mm
        assert!((arcsec_per_pixel(2.9, 250.0) - 2.3927).abs() < 1e-3);
        assert!((SIDEREAL_ARCSEC_PER_S - 15.0411).abs() < 1e-3);
        assert!((crossing_seconds(600.0, 0.0) - 39.891).abs() < 1e-2);
        assert!((crossing_seconds(600.0, 60.0) - 79.78).abs() < 1e-1);
        assert_eq!(format_arcsec(30.0), "30.0″");
        assert_eq!(format_arcsec(600.0), "10.00′");
        assert_eq!(format_arcsec(7200.0), "2.00°");
    }

    #[test]
    fn shapes() {
        let mut r = Reticle { shape: Shape::CrossCircle, size_arcsec: 100.0, rings: true, label: true };
        let p = r.shapes(200.0, 100.0, Some(0.5));
        assert_eq!(p.len(), 7);
        assert_eq!(p[4], Prim::Circle(100.0, 50.0, 25.0));
        assert_eq!(p[6], Prim::Circle(100.0, 50.0, 75.0));
        // no scale yet: crosshair only
        r.shape = Shape::Box;
        assert_eq!(r.shapes(200.0, 100.0, None).len(), 4);
        r.rings = false;
        assert_eq!(r.shapes(200.0, 100.0, Some(1.0)), vec![Prim::Rect(100.0, 50.0, 50.0, 50.0)]);
        assert_eq!(r.hud_line(Some(0.0)).unwrap(), "Reticle 1.67′  crossing 6.65 s");
        r.shape = Shape::Cross;
        assert!(r.hud_line(Some(0.0)).is_none());
        r.shape = Shape::Off;
        assert!(r.shapes(200.0, 100.0, Some(1.0)).is_empty());
        assert_eq!(Shape::from_index(Shape::Box.index()), Shape::Box);
    }
}
