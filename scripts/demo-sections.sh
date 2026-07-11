#!/usr/bin/env bash
#
# demo-sections.sh — slice a 3D-world demo recording into one looping animation
# per narrated beat.
#
# The desktop app writes two paired files to the Desktop when you record a demo
# tour (⇧R to record, ⌥⌘M to run the tour, ⇧R to stop):
#   • Lunamux <stamp>.mp4   — the screen recording (H.264)
#   • Lunamux <stamp>.txt   — a timeline, one line per beat: "M:SS.d<TAB>caption"
#     (written by finalizeRecording → save-demo-timeline; see World3DSpikeChrome.kt)
#
# This script reads the newest recording on the Desktop and its matching timeline,
# then for every beat cuts the video from that beat's timestamp to the next one
# (the last beat runs to the end of the clip) and encodes that slice as a looping
# animation. Output files are numbered and slugged from the caption, e.g.
#   03-move-between-tabs.webp
# and land in a "<recording name> sections/" folder next to the recording. It also
# writes an index.html gallery in that folder showing every animation inline with
# its narration caption above it (open it by double-clicking).
#
# Format: WebP by default — full colour, small, and it animates inline in modern
# browsers and Slack, which GIF (256-colour, bands the glow gradients, large) and
# APNG (huge, patchy inline support) do not. Override with --format for GIF/APNG,
# or --format mp4 for short re-encoded video loops.
#
# Usage:
#   scripts/demo-sections.sh [options]
#
# Options:
#   --format gif|webp|apng|mp4   Output format (default: webp)
#   --width  PX                  Scale width, aspect preserved (default: 1280)
#   --fps    N                   Output frame rate (default: 20; ignored for mp4,
#                                which keeps the source rate)
#   --quality N                  WebP quality 0–100 (default: 90; raise to reduce
#                                blockiness, at the cost of file size)
#   --gif-width PX               Width of the (always-emitted) GIFs (default: 640 —
#                                GIF is 256-colour, so keep it small)
#   --gif-fps N                  Frame rate of the GIFs (default: 15)
#   --limit  N                   Only render the first N sections (fast iteration)
#   --first                      Shorthand for --limit 1 (just the first section)
#   --video  PATH                Use this recording instead of the newest on Desktop
#   --timeline PATH              Use this timeline instead of the matching/newest .txt
#   --outdir PATH                Output folder (default: "<name> sections/" by the video)
#   -h, --help                   Show this help
#
# Requires: ffmpeg + ffprobe (brew install ffmpeg).

set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
FORMAT=""          # empty = auto-pick the best available encoder
WIDTH="1280"
FPS="20"
QUALITY="90"       # WebP quality 0–100; higher = crisper, larger
GIF_WIDTH="640"    # GIFs are always emitted too, but small — 256 colours balloon fast
GIF_FPS="15"
LIMIT="0"          # 0 = all sections; N>0 = only the first N (fast iteration)
VIDEO=""
TIMELINE=""
OUTDIR=""
DESKTOP="$HOME/Desktop"

usage() { sed -n '2,52p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# ── Parse args ──────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --format)   FORMAT="${2:?--format needs a value}"; shift 2 ;;
    --width)    WIDTH="${2:?--width needs a value}"; shift 2 ;;
    --fps)      FPS="${2:?--fps needs a value}"; shift 2 ;;
    --quality)  QUALITY="${2:?--quality needs a value}"; shift 2 ;;
    --gif-width) GIF_WIDTH="${2:?--gif-width needs a value}"; shift 2 ;;
    --gif-fps)  GIF_FPS="${2:?--gif-fps needs a value}"; shift 2 ;;
    --limit)    LIMIT="${2:?--limit needs a number}"; shift 2 ;;
    --first)    LIMIT="1"; shift ;;
    --video)    VIDEO="${2:?--video needs a path}"; shift 2 ;;
    --timeline) TIMELINE="${2:?--timeline needs a path}"; shift 2 ;;
    --outdir)   OUTDIR="${2:?--outdir needs a path}"; shift 2 ;;
    -h|--help)  usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

command -v ffmpeg  >/dev/null || { echo "ffmpeg not found (brew install ffmpeg)" >&2; exit 1; }
command -v ffprobe >/dev/null || { echo "ffprobe not found (brew install ffmpeg)" >&2; exit 1; }

# The ffmpeg encoder each output format needs, and a check for its presence
# (many Homebrew builds ship without libwebp, so we must probe rather than assume).
encoder_for() { case "$1" in gif) echo gif ;; apng) echo apng ;; mp4) echo libx264 ;; esac; }
have_encoder() { ffmpeg -hide_banner -encoders 2>/dev/null | grep -qE "^ ..... ${1}([[:space:]]|$)"; }

# WebP is available if ffmpeg was built with libwebp OR the standalone `img2webp`
# CLI is present — the latter lets us assemble an animated WebP from ffmpeg-extracted
# PNG frames even when this ffmpeg lacks libwebp (brew install webp).
webp_available() { have_encoder libwebp || command -v img2webp >/dev/null; }

if [ -n "$FORMAT" ]; then
  # Explicit format: validate the name, then require a way to produce it.
  case "$FORMAT" in gif|webp|apng|mp4) ;; *) echo "Bad --format '$FORMAT' (gif|webp|apng|mp4)" >&2; exit 1 ;; esac
  if [ "$FORMAT" = webp ]; then
    webp_available || { echo "WebP needs either ffmpeg built with libwebp or the img2webp CLI (brew install webp)." >&2; exit 1; }
  elif ! have_encoder "$(encoder_for "$FORMAT")"; then
    echo "This ffmpeg has no '$(encoder_for "$FORMAT")' encoder (needed for --format $FORMAT)." >&2
    exit 1
  fi
else
  # Auto: prefer WebP (best quality/size, inline-friendly); fall back to mp4, then gif.
  if   webp_available;       then FORMAT=webp
  elif have_encoder libx264; then FORMAT=mp4; echo "ℹ️  No WebP available — using mp4 (inline via <video>). brew install webp for WebP." >&2
  elif have_encoder gif;     then FORMAT=gif; echo "ℹ️  No WebP/x264 — using gif." >&2
  else echo "No usable encoder (need img2webp, or ffmpeg libx264/gif)." >&2; exit 1
  fi
fi

# ── Resolve the recording (newest Lunamux video on the Desktop) ─────────────
if [ -z "$VIDEO" ]; then
  # Newest by mtime among the recording containers we write.
  VIDEO="$(ls -t "$DESKTOP"/Lunamux*.mp4 "$DESKTOP"/Lunamux*.mov "$DESKTOP"/Lunamux*.webm 2>/dev/null | head -n1 || true)"
fi
[ -n "$VIDEO" ] && [ -f "$VIDEO" ] || { echo "No recording found (looked for Lunamux*.mp4/.mov/.webm on the Desktop). Pass --video." >&2; exit 1; }

# ── Resolve the timeline: prefer the .txt paired by name, else newest .txt ──
if [ -z "$TIMELINE" ]; then
  paired="${VIDEO%.*}.txt"
  if [ -f "$paired" ]; then
    TIMELINE="$paired"
  else
    TIMELINE="$(ls -t "$DESKTOP"/Lunamux*.txt "$DESKTOP"/*.txt 2>/dev/null | head -n1 || true)"
    echo "⚠️  No timeline named like the video; falling back to newest .txt: ${TIMELINE:-<none>}" >&2
  fi
fi
[ -n "$TIMELINE" ] && [ -f "$TIMELINE" ] || { echo "No timeline .txt found. Pass --timeline." >&2; exit 1; }

# ── Output folder ───────────────────────────────────────────────────────────
base="$(basename "${VIDEO%.*}")"
[ -n "$OUTDIR" ] || OUTDIR="$(dirname "$VIDEO")/$base sections"
mkdir -p "$OUTDIR"

DURATION="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$VIDEO")"

echo "Recording : $VIDEO"
echo "Timeline  : $TIMELINE"
echo "Duration  : ${DURATION}s"
echo "Output    : $OUTDIR  (.$FORMAT, ${WIDTH}px, ${FPS}fps)"
echo

# ── Read the timeline into parallel arrays (start seconds + caption) ────────
# Each line is "M:SS.d<TAB>caption"; convert the stamp to seconds with awk.
starts=(); caps=()
while IFS=$'\t' read -r ts cap || [ -n "$ts" ]; do
  [ -z "${ts// }" ] && continue
  sec="$(awk -F: '{ split($2, s, "."); printf "%.3f", ($1 * 60) + s[1] + (s[2] / 10) }' <<<"$ts")"
  starts+=("$sec"); caps+=("$cap")
done < "$TIMELINE"

n=${#starts[@]}
[ "$n" -gt 0 ] || { echo "Timeline had no beats." >&2; exit 1; }

# ── Filenames: "NN-slugged-caption" (ASCII-safe, from the caption) ──────────
slugify() {
  # Non-alphanumeric bytes (incl. multibyte em-dashes) collapse to single dashes.
  LC_ALL=C printf '%s' "$1" \
    | LC_ALL=C tr -c 'A-Za-z0-9' '-' \
    | tr -s '-' \
    | sed 's/^-//; s/-$//' \
    | tr 'A-Z' 'a-z' \
    | cut -c1-48
}

# Minimal HTML entity-escape for caption text going into the gallery page.
htmlesc() { LC_ALL=C printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'; }

# The inline media element for one section file, chosen by output format: a
# looping <video> for mp4, an <img> for the animated image formats.
media_tag() {
  local f="$1"
  if [ "$FORMAT" = "mp4" ]; then
    printf '<video src="%s" autoplay loop muted playsinline></video>' "$f"
  else
    printf '<img src="%s" alt="" loading="lazy">' "$f"
  fi
}

# ── Encoders (one video slice → one animation), by format ───────────────────
# Accurate, fast seek: -ss + -t BEFORE -i bound the video read to [start, start+dur).
# They MUST precede -i: placed after -i (as an "output" option) they would instead
# attach to the *next* input (e.g. the palette image in the GIF pass), leaving the
# output unbounded — which silently encodes the entire recording.
VF_BASE="fps=${FPS},scale=${WIDTH}:-2:flags=lanczos"

# Best-quality animated GIF via a two-pass per-clip palette (palettegen→paletteuse).
# Also produced alongside the primary format for every section. GIF is a 256-colour
# format, so it uses its own smaller GIF_WIDTH/GIF_FPS — a full-size GIF of this
# content runs to hundreds of MB. diff_mode=rectangle only rewrites changed regions
# per frame, and light Bayer dithering keeps the palette banding down without the
# heavy noise that wrecks LZW compression.
encode_gif() { # start dur out
  local start="$1" dur="$2" out="$3" pal gvf
  gvf="fps=${GIF_FPS},scale=${GIF_WIDTH}:-2:flags=lanczos"
  pal="$(mktemp -t lunamux-pal).png"
  ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" \
    -vf "${gvf},palettegen=stats_mode=diff" "$pal"
  ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" -i "$pal" \
    -filter_complex "[0:v]${gvf}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
    -loop 0 "$out"
  rm -f "$pal"
}

encode() { # start dur out
  local start="$1" dur="$2" out="$3"
  case "$FORMAT" in
    webp)
      if have_encoder libwebp; then
        # ffmpeg can emit animated WebP directly. -sharp_yuv keeps edges/text crisp.
        ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" \
          -vf "$VF_BASE" -c:v libwebp -lossless 0 -compression_level 6 -q:v "$QUALITY" \
          -sharp_yuv 1 -loop 0 -an "$out"
      else
        # No libwebp in ffmpeg: extract PNG frames, then assemble with img2webp.
        local fdir delay
        fdir="$(mktemp -d -t lunamux-frames)"
        ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" \
          -vf "$VF_BASE" "$fdir/f_%05d.png"
        delay="$(awk -v f="$FPS" 'BEGIN { printf "%d", 1000 / f }')"  # per-frame ms
        # -sharp_yuv/-lossy/-q/-m/-d apply to every frame that follows them.
        # -sharp_yuv sharpens the chroma downsample so text and edges don't block up.
        img2webp -loop 0 -sharp_yuv -lossy -q "$QUALITY" -m 4 -d "$delay" \
          "$fdir"/f_*.png -o "$out" >/dev/null 2>&1
        rm -rf "$fdir"
      fi
      ;;
    gif) encode_gif "$start" "$dur" "$out" ;;
    apng)
      ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" \
        -vf "$VF_BASE" -f apng -plays 0 "$out"
      ;;
    mp4)
      # Keep the source frame rate for mp4 loops; just scale.
      ffmpeg -hide_banner -loglevel error -y -ss "$start" -t "$dur" -i "$VIDEO" \
        -vf "scale=${WIDTH}:-2:flags=lanczos" -c:v libx264 -pix_fmt yuv420p -crf 20 \
        -movflags +faststart -an "$out"
      ;;
  esac
}

# ── Cut every section (accumulating the generated file + caption for the gallery)
gen_files=(); gen_caps=(); gen_gifs=()
sections="$n"
if [ "$LIMIT" -gt 0 ] && [ "$LIMIT" -lt "$n" ]; then
  sections="$LIMIT"
  echo "(--limit $LIMIT: only the first $LIMIT of $n sections)"
  echo
fi
for ((i = 0; i < sections; i++)); do
  start="${starts[i]}"
  if (( i + 1 < n )); then end="${starts[i+1]}"; else end="$DURATION"; fi
  dur="$(awk -v a="$start" -v b="$end" 'BEGIN { d = b - a; printf "%.3f", (d > 0 ? d : 0) }')"
  # Skip zero/negative-length sections (e.g. two beats sharing a stamp).
  if awk -v d="$dur" 'BEGIN { exit (d > 0.05 ? 0 : 1) }'; then :; else
    printf "  – skip %02d (%.1fs) %s\n" "$((i+1))" "$dur" "${caps[i]}"; continue
  fi
  idx="$(printf '%02d' "$((i+1))")"
  slug="$(slugify "${caps[i]}")"; [ -n "$slug" ] || slug="section"
  name="${idx}-${slug}.${FORMAT}"
  printf "  → %02d  %6.1fs–%6.1fs  %s\n" "$((i+1))" "$start" "$end" "${caps[i]}"
  encode "$start" "$dur" "$OUTDIR/$name"
  # Always also emit a GIF of the section (unless the primary format already is GIF).
  gifname=""
  if [ "$FORMAT" != gif ]; then
    gifname="${idx}-${slug}.gif"
    encode_gif "$start" "$dur" "$OUTDIR/$gifname"
  fi
  gen_files+=("$name"); gen_caps+=("${caps[i]}"); gen_gifs+=("$gifname")
done

# ── Gallery HTML: every animation inline, its caption above it ───────────────
HTML="$OUTDIR/index.html"
{
  cat <<'HEAD'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lunamux demo sections</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0b0f16; color:#cdd8ea;
         font:15px/1.5 ui-monospace,Menlo,monospace; padding:40px 16px; }
  h1 { text-align:center; font-size:18px; font-weight:700; color:#eef3fb; margin:0 0 4px; }
  .sub { text-align:center; color:#5f6b80; margin:0 0 32px; font-size:12px; }
  .grid { max-width:900px; margin:0 auto; display:flex; flex-direction:column; gap:36px; }
  .cap { font-weight:700; color:#eef3fb; margin:0 0 10px; font-size:15px; }
  .cap .n { color:#4c8dff; margin-right:8px; }
  .cap .dl { float:right; font-weight:600; font-size:12px; color:#5f6b80;
             text-decoration:none; border:1px solid #2a3242; border-radius:8px;
             padding:2px 8px; }
  .cap .dl:hover { color:#cdd8ea; border-color:#3a4456; }
  img, video { display:block; width:100%; height:auto; border-radius:12px;
               border:1px solid #2a3242; background:#000;
               box-shadow:0 8px 30px rgba(0,0,0,0.55); }
</style>
</head>
<body>
HEAD
  printf '<h1>%s</h1>\n' "$(htmlesc "$base")"
  printf '<p class="sub">%s section(s) · generated from the demo timeline</p>\n' "${#gen_files[@]}"
  printf '<div class="grid">\n'
  for ((k = 0; k < ${#gen_files[@]}; k++)); do
    dl=""
    [ -n "${gen_gifs[k]}" ] && dl="$(printf '<a class="dl" href="%s" download>gif</a>' "${gen_gifs[k]}")"
    printf '  <div class="card"><p class="cap"><span class="n">%02d</span>%s%s</p>%s</div>\n' \
      "$((k+1))" "$(htmlesc "${gen_caps[k]}")" "$dl" "$(media_tag "${gen_files[k]}")"
  done
  printf '</div>\n</body>\n</html>\n'
} > "$HTML"

echo
echo "Done. ${#gen_files[@]} section(s) → $OUTDIR"
echo "Gallery: $HTML"
ls -lh "$OUTDIR" | sed '1d'
