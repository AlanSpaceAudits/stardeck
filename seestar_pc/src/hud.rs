//! Burns the crosshair and data block into frame pixels, and flips frames.

use ab_glyph::{Font, FontRef, ScaleFont};

fn blend(rgba: &mut [u8], w: usize, h: usize, x: i64, y: i64, color: [u8; 3], alpha: f32) {
    if x < 0 || y < 0 || x >= w as i64 || y >= h as i64 {
        return;
    }
    let i = (y as usize * w + x as usize) * 4;
    for c in 0..3 {
        rgba[i + c] = (rgba[i + c] as f32 * (1.0 - alpha) + color[c] as f32 * alpha) as u8;
    }
}

fn fill(rgba: &mut [u8], w: usize, h: usize, x0: i64, y0: i64, x1: i64, y1: i64, color: [u8; 3], alpha: f32) {
    for y in y0..y1 {
        for x in x0..x1 {
            blend(rgba, w, h, x, y, color, alpha);
        }
    }
}

/// Draws a centre crosshair and `lines` of text (bottom left) into an RGBA frame.
pub fn burn(rgba: &mut [u8], w: usize, h: usize, lines: &[String]) {
    let (cx, cy) = (w as i64 / 2, h as i64 / 2);
    let (arm, gap, t) = ((h as i64 / 20).max(10), (h as i64 / 100).max(2), (h as i64 / 500).max(1));
    let red = [255, 60, 60];
    fill(rgba, w, h, cx - arm, cy - t, cx - gap, cy + t, red, 1.0);
    fill(rgba, w, h, cx + gap, cy - t, cx + arm, cy + t, red, 1.0);
    fill(rgba, w, h, cx - t, cy - arm, cx + t, cy - gap, red, 1.0);
    fill(rgba, w, h, cx - t, cy + gap, cx + t, cy + arm, red, 1.0);

    let Ok(font) = FontRef::try_from_slice(epaint_default_fonts::HACK_REGULAR) else { return };
    let font = font.as_scaled((h as f32 / 45.0).max(12.0));
    let (line_h, pad) = (font.height() + font.line_gap(), font.height() / 3.0);
    let text_w = lines.iter().map(|l| l.chars().map(|c| font.h_advance(font.glyph_id(c))).sum::<f32>()).fold(0.0, f32::max);
    let top = h as f32 - pad * 2.0 - line_h * lines.len() as f32;
    fill(rgba, w, h, pad as i64, (top - pad) as i64, (pad * 3.0 + text_w) as i64, (h as f32 - pad) as i64, [0, 0, 0], 0.65);
    for (n, line) in lines.iter().enumerate() {
        let mut x = pad * 2.0;
        let baseline = top + line_h * n as f32 + font.ascent();
        for c in line.chars() {
            let id = font.glyph_id(c);
            let glyph = id.with_scale_and_position(font.scale(), ab_glyph::point(x, baseline));
            x += font.h_advance(id);
            if let Some(outline) = font.outline_glyph(glyph) {
                let b = outline.px_bounds();
                outline.draw(|gx, gy, cov| blend(rgba, w, h, b.min.x as i64 + gx as i64, b.min.y as i64 + gy as i64, [255, 255, 255], cov));
            }
        }
    }
}

pub fn flip(rgba: &mut [u8], w: usize, h: usize, horizontal: bool, vertical: bool) {
    if horizontal {
        for row in rgba.chunks_exact_mut(w * 4) {
            for x in 0..w / 2 {
                for c in 0..4 {
                    row.swap(x * 4 + c, (w - 1 - x) * 4 + c);
                }
            }
        }
    }
    if vertical {
        for y in 0..h / 2 {
            let (a, b) = rgba.split_at_mut((h - 1 - y) * w * 4);
            a[y * w * 4..(y + 1) * w * 4].swap_with_slice(&mut b[..w * 4]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn burn_and_flip() {
        let (w, h) = (300, 300);
        let mut img = vec![0u8; w * h * 4];
        burn(&mut img, w, h, &["Az 12.3456".to_string()]);
        let px = |x: usize, y: usize| [img[(y * w + x) * 4], img[(y * w + x) * 4 + 1], img[(y * w + x) * 4 + 2]];
        assert_eq!(px(w / 2 + 8, h / 2), [255, 60, 60], "crosshair arm");
        assert_eq!(px(w / 2, h / 2), [0, 0, 0], "crosshair centre gap");
        let text_pixels = (h - 30..h).flat_map(|y| (0..150).map(move |x| (x, y))).filter(|&(x, y)| px(x, y)[0] > 200).count();
        assert!(text_pixels > 20, "text drawn: {text_pixels}");

        let mut two = vec![1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4]; // 2x2
        flip(&mut two, 2, 2, true, false);
        assert_eq!(two, [2, 2, 2, 2, 1, 1, 1, 1, 4, 4, 4, 4, 3, 3, 3, 3]);
        flip(&mut two, 2, 2, false, true);
        assert_eq!(two, [4, 4, 4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1]);
    }
}
