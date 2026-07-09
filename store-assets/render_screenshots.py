#!/usr/bin/env python3
"""
render_screenshots.py — Lunamux store-screenshot generator.

Reads a JSON config describing a set of store screenshots and renders, for each
entry, a compliant image for BOTH the Apple App Store (iPhone 17 Pro Max frame)
and the Google Play Store (Pixel 9 Pro XL frame).

Each JSON entry supplies:
  - tagline / subtagline  : the headline + supporting line (use "\n" for manual breaks)
  - android  (required)   : screenshot rendered inside the Pixel frame  (Google Play)
  - ios      (required)   : screenshot rendered inside the iPhone frame  (App Store)
  - mac      (optional)   : Mac screenshot; when present the layout shows the
                            MacBook + phone together (the "companion" hero look).
                            When absent, the phone frame fills most of the canvas.

Output:
  out/app-store/<NN>-<id>.jpg   1284 x 2778  (iPhone 6.5")
  out/google-play/<NN>-<id>.jpg 2880 x 3840  (3:4, ratio <= 2:1)

Both are flattened RGB JPEGs (no alpha), sized/weighted within each store's limits.

Usage:
  python3 render_screenshots.py [path/to/screenshots.json]

Called by: the project owner when regenerating the store listing assets.
@see screenshots.json for the config schema and README.md for the workflow.
"""

import json
import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

# --- brand palette (from the Lunamux Dark theme in darkness-toolkit) ---
# The signature "deep navy + cyan glow" look, replacing the old green phosphor.
BG = (10, 20, 32)           # --bg #0a1420 : deep navy base
GREEN = (77, 200, 245)      # accent #4dc8f5   : primary cyan (body / phosphor)
GHI = (165, 216, 255)       # number #a5d8ff   : bright accent (headline)
GDIM = (95, 117, 144)       # --text-dim #5f7590: secondary / comments
MONO_CANDIDATES = [
    "/System/Library/Fonts/SFNSMono.ttf",
    "/System/Library/Fonts/Menlo.ttc",
    "/Library/Fonts/JetBrainsMono-Regular.ttf",
]

# --- store output targets ---
STORES = [
    # name,          target_w, target_h, phone_kind, shot_key, supersample
    ("google-play", 2880, 3840, "pixel", "android", 1),
    ("app-store",   1284, 2778, "iphone", "ios",    2),
]


def _mono_path():
    """Return the first available monospaced font path.

    @return absolute font path string; raises if none found.
    """
    for p in MONO_CANDIDATES:
        if os.path.exists(p):
            return p
    raise SystemExit("No monospaced font found; edit MONO_CANDIDATES.")


_MONO = _mono_path()


def font(size, bold=True):
    """Load the brand monospaced font at a pixel size.

    @param size integer pixel size.
    @param bold whether to request the Bold variation (SF Mono is variable).
    @return a PIL ImageFont.
    """
    f = ImageFont.truetype(_MONO, int(size))
    try:
        f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception:
        pass
    return f


def rrmask(size, r):
    """Rounded-rectangle alpha mask.

    @param size (w, h) tuple.
    @param r corner radius in pixels.
    @return an "L" mode Image usable as a paste mask.
    """
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], int(r), fill=255)
    return m


def vgrad(w, h, c1, c2):
    """Vertical top-to-bottom RGB gradient.

    @param w,h size in pixels.
    @param c1,c2 top and bottom colours.
    @return an RGB Image.
    """
    g = Image.new("RGB", (int(w), int(h)))
    dr = ImageDraw.Draw(g)
    h = int(h)
    for y in range(h):
        t = y / max(1, h - 1)
        dr.line([(0, y), (int(w), y)], fill=tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)))
    return g


def _radial(s, power):
    """Small radial falloff map (bright centre -> dark edge), resized by caller.

    @param s side length of the square map.
    @param power falloff exponent (higher = tighter core).
    @return an "L" mode Image.
    """
    g = Image.new("L", (s, s), 0)
    px = g.load()
    c = s / 2
    for y in range(s):
        for x in range(s):
            d = (((x - c) ** 2 + (y - c) ** 2) ** 0.5) / c
            px[x, y] = int(255 * max(0, 1 - d) ** power)
    return g


def build_background(W, H):
    """Compose the branded deep-navy background: base + radial glow + grid + vignette.

    @param W,H canvas size.
    @return an RGB Image.
    """
    bg = Image.new("RGB", (W, H), BG)
    glow = _radial(256, 1.7).resize((int(W * 1.05), int(W * 1.05)))
    tmp = Image.new("L", (W, H), 0)
    tmp.paste(glow, (W // 2 - glow.width // 2, int(H * 0.58) - glow.height // 2))
    contrib = Image.composite(
        Image.new("RGB", (W, H), (26, 104, 145)),
        Image.new("RGB", (W, H), (0, 0, 0)),
        tmp.point(lambda v: int(v * 0.55)),
    )
    bg = ImageChops.add(bg, contrib)
    gd = ImageDraw.Draw(bg)
    step = max(24, int(W * 0.045))
    for x in range(0, W, step):
        gd.line([(x, 0), (x, H)], fill=(14, 28, 44))
    for y in range(0, H, step):
        gd.line([(0, y), (W, y)], fill=(14, 28, 44))
    vg = ImageChops.invert(_radial(256, 1.3).resize((W, H))).point(lambda v: int(v * 0.5))
    bg = Image.composite(Image.new("RGB", (W, H), (4, 8, 14)), bg, vg)
    return bg


def halo(out, box, r, color, blur, alpha):
    """Add a soft additive colour glow behind a device (the phosphor look).

    @param out target RGB Image (returned modified).
    @param box (x0,y0,x1,y1) glow rect.
    @param r corner radius, @param color glow RGB, @param blur px, @param alpha 0-255.
    @return the modified Image.
    """
    W, H = out.size
    s = Image.new("L", (W, H), 0)
    ImageDraw.Draw(s).rounded_rectangle(box, int(r), fill=alpha)
    s = s.filter(ImageFilter.GaussianBlur(blur))
    return ImageChops.add(out, Image.composite(Image.new("RGB", (W, H), color),
                                               Image.new("RGB", (W, H), (0, 0, 0)), s))


def shadow(out, box, r, blur, alpha, off):
    """Cast a blurred drop shadow under a device.

    @param out target Image, @param box device rect, @param r radius.
    @param blur px, @param alpha 0-255, @param off (dx,dy) offset.
    @return the modified Image.
    """
    W, H = out.size
    s = Image.new("L", (W, H), 0)
    ImageDraw.Draw(s).rounded_rectangle(
        [box[0] + off[0], box[1] + off[1], box[2] + off[0], box[3] + off[1]], int(r), fill=alpha)
    s = s.filter(ImageFilter.GaussianBlur(blur))
    return Image.composite(Image.new("RGB", (W, H), (0, 0, 0)), out, s)


def _fit_cover(img, w, h):
    """Resize a screenshot to exactly (w,h) (screenshots already match device aspect).

    @return an RGB Image at (w,h).
    """
    return img.resize((int(w), int(h)), Image.LANCZOS)


def draw_mac(out, img, cx, top, screen_w):
    """Render a MacBook mockup with the screenshot inside.

    @param out target Image, @param img Mac screenshot (RGB).
    @param cx horizontal centre, @param top top y of the screen bezel.
    @param screen_w width of the screen content in px.
    @return (out, bottom_y) — the composited image and base's bottom y.
    """
    aspect = img.width / img.height
    scr_w = int(screen_w)
    scr_h = int(scr_w / aspect)
    bezel = max(2, int(scr_w * 0.011))
    ow, oh = scr_w + 2 * bezel, scr_h + 2 * bezel
    x = int(cx - ow / 2)
    y = int(top)
    out = halo(out, (x - int(ow * 0.03), y - int(ow * 0.03), x + ow + int(ow * 0.03), y + oh + int(ow * 0.03)),
               ow * 0.03, (20, 116, 150), ow * 0.06, 120)
    out = shadow(out, (x, y, x + ow, y + oh), int(ow * 0.02), int(ow * 0.03), 180, (0, int(oh * 0.03)))
    bez = Image.new("RGB", (ow, oh), (12, 12, 14))
    out.paste(bez, (x, y), rrmask((ow, oh), ow * 0.018))
    out.paste(_fit_cover(img, scr_w, scr_h), (x + bezel, y + bezel), rrmask((scr_w, scr_h), scr_w * 0.007))
    d = ImageDraw.Draw(out)
    nw, nh = int(ow * 0.085), int(ow * 0.015)
    d.rounded_rectangle([cx - nw // 2, y + bezel - 2, cx + nw // 2, y + bezel - 2 + nh], nh // 2, fill=(12, 12, 14))
    base_w = int(ow * 1.02)
    bx = int(cx - base_w / 2)
    by = y + oh
    base_h = max(8, int(ow * 0.028))
    out.paste(vgrad(base_w, base_h, (196, 200, 203), (120, 124, 127)), (bx, by), rrmask((base_w, base_h), base_h * 0.28))
    d.rounded_rectangle([cx - base_w * 0.04, by, cx + base_w * 0.04, by + base_h * 0.22], 6, fill=(90, 94, 97))
    d.rectangle([x, by - 3, x + ow, by + 2], fill=(6, 6, 7))
    return out, by + base_h


def draw_phone(out, img, kind, cx, top, screen_w):
    """Render a phone mockup (Pixel 9 Pro XL or iPhone 17 Pro Max) with the screenshot.

    @param out target Image, @param img phone screenshot (RGB).
    @param kind "pixel" or "iphone" — selects frame geometry and camera cutout.
    @param cx horizontal centre, @param top top y of the outer frame.
    @param screen_w width of the screen content in px.
    @return (out, bottom_y).
    """
    aspect = img.width / img.height
    scr_w = int(screen_w)
    scr_h = int(scr_w / aspect)

    if kind == "pixel":
        rail, bez = scr_w * 0.021, scr_w * 0.013
        r_out, r_scr = scr_w * 0.126, scr_w * 0.108
        rail_c1, rail_c2, rim = (70, 74, 76), (34, 37, 38), (120, 124, 126)
    else:  # iphone 17 pro max
        rail, bez = scr_w * 0.020, scr_w * 0.014
        r_out, r_scr = scr_w * 0.165, scr_w * 0.140
        rail_c1, rail_c2, rim = (150, 152, 156), (92, 95, 99), (200, 202, 206)

    frame = int(rail + bez)
    ow, oh = scr_w + 2 * frame, scr_h + 2 * frame
    x, y = int(cx - ow / 2), int(top)

    out = halo(out, (x - int(ow * 0.05), y - int(ow * 0.05), x + ow + int(ow * 0.05), y + oh + int(ow * 0.05)),
               r_out, (24, 126, 162), ow * 0.09, 135)
    out = shadow(out, (x, y, x + ow, y + oh), r_out, int(ow * 0.05), 190, (0, int(oh * 0.02)))
    out.paste(vgrad(ow, oh, rail_c1, rail_c2), (x, y), rrmask((ow, oh), r_out))
    ImageDraw.Draw(out).rounded_rectangle([x, y, x + ow - 1, y + oh - 1], int(r_out), outline=rim, width=2)
    ir = int(rail)
    bz = Image.new("RGB", (scr_w + 2 * int(bez), scr_h + 2 * int(bez)), (8, 8, 9))
    out.paste(bz, (x + ir, y + ir), rrmask(bz.size, r_scr))
    isx, isy = x + frame, y + frame
    out.paste(_fit_cover(img, scr_w, scr_h), (isx, isy), rrmask((scr_w, scr_h), r_scr * 0.9))

    d = ImageDraw.Draw(out)
    if kind == "pixel":
        ph = int(scr_w * 0.022)
        d.ellipse([cx - ph, isy + int(scr_w * 0.052), cx + ph, isy + int(scr_w * 0.052) + 2 * ph], fill=(6, 6, 7))
        brx = x + ow - 2
        d.rounded_rectangle([brx - 4, y + int(oh * 0.22), brx + 8, y + int(oh * 0.30)], 6, fill=(90, 94, 96))
        d.rounded_rectangle([brx - 4, y + int(oh * 0.33), brx + 8, y + int(oh * 0.39)], 6, fill=(90, 94, 96))
    else:  # iphone: Dynamic Island + side buttons
        diw, dih = int(scr_w * 0.30), int(scr_w * 0.088)
        dy = isy + int(scr_w * 0.028)
        d.rounded_rectangle([cx - diw // 2, dy, cx + diw // 2, dy + dih], dih // 2, fill=(4, 4, 5))
        blx = x + 2
        d.rounded_rectangle([blx - 8, y + int(oh * 0.16), blx + 4, y + int(oh * 0.20)], 5, fill=rail_c1)      # action
        d.rounded_rectangle([blx - 8, y + int(oh * 0.24), blx + 4, y + int(oh * 0.31)], 5, fill=rail_c1)      # vol up
        d.rounded_rectangle([blx - 8, y + int(oh * 0.33), blx + 4, y + int(oh * 0.40)], 5, fill=rail_c1)      # vol dn
        brx = x + ow - 2
        d.rounded_rectangle([brx - 4, y + int(oh * 0.20), brx + 8, y + int(oh * 0.30)], 5, fill=rail_c1)      # power
        d.rounded_rectangle([brx - 4, y + int(oh * 0.12), brx + 8, y + int(oh * 0.16)], 5, fill=rail_c1)      # camera
    return out, y + oh


def wrap_lines(d, text, fnt, maxw):
    """Word-wrap text to a pixel width, honouring explicit "\n" breaks.

    @param d an ImageDraw for measuring, @param text the string.
    @param fnt the font, @param maxw max line width in px.
    @return list of line strings.
    """
    lines = []
    for para in text.split("\n"):
        cur = ""
        for w in para.split(" "):
            t = (cur + " " + w).strip()
            if not cur or d.textlength(t, font=fnt) <= maxw:
                cur = t
            else:
                lines.append(cur)
                cur = w
        lines.append(cur)
    return lines


def render(spec, brand, store):
    """Render one screenshot for one store and return the final RGB Image.

    @param spec one JSON screenshot entry (tagline/subtagline/android/ios/mac...).
    @param brand the JSON "brand" block (eyebrow/url/footerNote).
    @param store one STORES tuple.
    @return an RGB Image at the store's exact target size.
    """
    name, tw, th, kind, shot_key, ss = store
    W, H = tw * ss, th * ss
    out = build_background(W, H)
    d = ImageDraw.Draw(out)
    margin = W * 0.05

    def ctext(y, text, fnt, fill):
        w = d.textlength(text, font=fnt)
        d.text(((W - w) / 2, y), text, font=fnt, fill=fill)

    # --- banner text ---
    # Fonts are a fraction of width; on a narrow/tall canvas (App Store 19.5:9)
    # width-relative text looks small next to the 3:4 Play version, so scale it
    # up toward the shorter canvas. ts == 1.0 for 3:4, ~1.28 for the App Store.
    ts = min(1.4, max(1.0, (0.75 / (tw / th)) ** 0.5))
    eb_s, hf_s, sf_s = W * 0.016 * ts, W * 0.049 * ts, W * 0.0185 * ts
    y = H * 0.043
    ctext(y, brand.get("eyebrow", ""), font(eb_s), GDIM)
    y += eb_s * 2.3
    hf = font(hf_s)
    for ln in wrap_lines(d, spec["tagline"], hf, W - 2 * margin):
        ctext(y, ln, hf, GHI)
        y += hf_s * 1.16
    y += hf_s * 0.24
    sf = font(sf_s, bold=False)
    for ln in wrap_lines(d, spec.get("subtagline", ""), sf, W - 2 * margin):
        ctext(y, ln, sf, GREEN)
        y += sf_s * 1.28

    banner_bottom = y
    footer_h = int(H * 0.039)
    ax, aw = margin, W - 2 * margin
    ay = banner_bottom + H * 0.02
    ah = (H - footer_h - H * 0.03) - ay

    # --- devices ---
    mac_path = spec.get("mac")
    phone_img = Image.open(resolve(spec[shot_key])).convert("RGB")
    if mac_path:
        mac_img = Image.open(resolve(mac_path)).convert("RGB")
        m_asp = mac_img.width / mac_img.height
        p_asp = phone_img.width / phone_img.height
        mac_sw = aw * 0.92
        mac_sh = mac_sw / m_asp
        # The phone overlaps the Mac's lower portion, then grows to consume the
        # remaining vertical space. This keeps tall canvases (App Store 19.5:9)
        # from leaving big dead bands while staying identical on 3:4 (Play):
        # the phone naturally ends up larger on taller canvases.
        phone_top_rel = mac_sh * 0.58
        ph_sh = (ah - phone_top_rel) * 0.99
        ph_sw = ph_sh * p_asp
        cap = min(mac_sw * 0.72, aw * 0.60)   # don't let the phone dwarf the Mac
        if ph_sw > cap:
            ph_sw = cap
            ph_sh = ph_sw / p_asp
        total_h = phone_top_rel + ph_sh
        scale = min(1.0, ah / total_h)         # guard against overflow
        mac_sw *= scale; ph_sw *= scale; phone_top_rel *= scale; total_h *= scale
        top = ay + (ah - total_h) / 2
        out, _ = draw_mac(out, mac_img, W / 2, top, mac_sw)
        out, _ = draw_phone(out, phone_img, kind, W / 2, top + phone_top_rel, ph_sw)
    else:
        p_asp = phone_img.width / phone_img.height
        ph_sh = ah * 0.98
        ph_sw = ph_sh * p_asp
        if ph_sw > aw * 0.82:
            ph_sw = aw * 0.82
            ph_sh = ph_sw / p_asp
        top = ay + (ah - ph_sh) / 2
        out, _ = draw_phone(out, phone_img, kind, W / 2, top, ph_sw)

    # --- footer (url + requires-mac note) ---
    d = ImageDraw.Draw(out)
    strip = Image.new("RGBA", (W, footer_h), (6, 9, 7, 235))
    out.paste(strip, (0, H - footer_h), strip)
    bf_s = W * 0.0165 * ts
    bf = font(bf_s)
    url = brand.get("url", "")
    sep = "   ·   "
    note = brand.get("footerNote", "")
    wu = d.textlength(url, font=bf); wsep = d.textlength(sep, font=bf); wn = d.textlength(note, font=bf)
    fx = (W - (wu + wsep + wn)) / 2
    fy = H - footer_h + (footer_h - bf_s * 1.2) / 2
    d.text((fx, fy), url, font=bf, fill=GHI); fx += wu
    d.text((fx, fy), sep, font=bf, fill=GDIM); fx += wsep
    d.text((fx, fy), note, font=bf, fill=GDIM)

    if ss != 1:
        out = out.resize((tw, th), Image.LANCZOS)
    return out


CONFIG_DIR = "."


def resolve(p):
    """Resolve a source path from the config, relative to the config directory.

    @param p path string from the JSON.
    @return absolute path.
    """
    return p if os.path.isabs(p) else os.path.join(CONFIG_DIR, p)


def main():
    """Entry point: load the JSON config and render every screenshot for both stores."""
    global CONFIG_DIR
    cfg_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots.json")
    CONFIG_DIR = os.path.dirname(os.path.abspath(cfg_path))
    with open(cfg_path) as f:
        cfg = json.load(f)
    brand = cfg.get("brand", {})
    shots = cfg["screenshots"]
    for store in STORES:
        name = store[0]
        outdir = os.path.join(CONFIG_DIR, "out", name)
        os.makedirs(outdir, exist_ok=True)
        for i, spec in enumerate(shots, 1):
            img = render(spec, brand, store)
            fn = os.path.join(outdir, f"{i:02d}-{spec.get('id', 'shot')}.jpg")
            img.save(fn, "JPEG", quality=92)
            mb = os.path.getsize(fn) / 1e6
            print(f"  [{name}] {os.path.basename(fn)}  {img.width}x{img.height}  {mb:.2f} MB")
    print("Done.")


if __name__ == "__main__":
    main()
