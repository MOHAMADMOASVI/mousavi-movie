#!/usr/bin/env python3
"""Tiny video web app: plays the videos in ./videos in the browser (TV-remote friendly) and in VLC."""
import hashlib
import html
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote

ROOT = Path(__file__).resolve().parent
VIDEO_DIR = ROOT / "videos"
STATIC_DIR = ROOT / "static"
THUMB_DIR = ROOT / ".cache" / "thumbs"
POSTER_DIR = ROOT / "posters"  # optional tall poster per film, named like the video (see make_posters.py)
PORT = int(os.environ.get("PORT", sys.argv[1] if len(sys.argv) > 1 else 8000))
VIDEO_EXTS = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".ts", ".mp3", ".m4a"}
# Formats browsers can usually play; the rest still work in VLC.
BROWSER_EXTS = {".mp4", ".m4v", ".mov", ".webm", ".mp3", ".m4a"}
MIME = {".mkv": "video/x-matroska", ".ts": "video/mp2t", ".m4v": "video/mp4", ".mov": "video/quicktime"}
CHUNK = 256 * 1024
APP_NAME = "موسوی مووی"
FA_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")

# The mark: a cinema ticket with a play triangle.
LOGO = '<svg class="logo" viewBox="0 0 96 96" aria-hidden="true"><rect width="96" height="96" rx="22" fill="#534ab7"/><path d="M18 34a6 6 0 0 1 6-6h48a6 6 0 0 1 6 6v6a8 8 0 0 0 0 16v6a6 6 0 0 1-6 6H24a6 6 0 0 1-6-6v-6a8 8 0 0 0 0-16z" fill="#eeedfe"/><path d="M43 39v18l14-9z" fill="#534ab7" stroke="#534ab7" stroke-width="3" stroke-linejoin="round"/></svg>'
ICON_PLAY = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13a1 1 0 0 0 1.5.86l11-6.5a1 1 0 0 0 0-1.72l-11-6.5A1 1 0 0 0 8 5.5z"/></svg>'
ICON_BACK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>'


def remote_key(*lit):
    """A small picture of the remote's direction pad with the given parts lit: up, down, left, right, ok."""
    arrows = {
        "up": "M9.2 6.6 12 3.9l2.8 2.7",
        "down": "M9.2 17.4 12 20.1l2.8-2.7",
        "left": "M6.6 9.2 3.9 12l2.7 2.8",
        "right": "M17.4 9.2 20.1 12l-2.7 2.8",
    }
    parts = "".join(f'<path class="{"on" if d in lit else "off"}" d="{path}"/>' for d, path in arrows.items())
    centre = f'<circle class="{"dot on" if "ok" in lit else "dot off"}" cx="12" cy="12" r="3.4"/>'
    return f'<svg class="key" viewBox="0 0 24 24" aria-hidden="true"><circle class="ring" cx="12" cy="12" r="11"/>{parts}{centre}</svg>'


KEY_MOVE = remote_key("up", "down", "left", "right")
KEY_SIDES = remote_key("left", "right")
KEY_DOWN = remote_key("down")
KEY_OK = remote_key("ok")
KEY_BACK = (
    '<svg class="key" viewBox="0 0 24 24" aria-hidden="true"><circle class="ring" cx="12" cy="12" r="11"/>'
    '<path class="on" d="M10.2 7.4 7 10.6l3.2 3.2M7 10.6h6.4a3.3 3.3 0 0 1 0 6.6h-2"/></svg>'
)


def find_tool(name):
    # GUI-launched processes often lack Homebrew on PATH
    return shutil.which(name) or next(
        (p for p in (f"/opt/homebrew/bin/{name}", f"/usr/local/bin/{name}") if os.path.exists(p)), None
    )


FFMPEG, FFPROBE = find_tool("ffmpeg"), find_tool("ffprobe")
_durations = {}
_thumb_lock = threading.Lock()


def fa(text):
    return str(text).translate(FA_DIGITS)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the outgoing interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def list_videos():
    if not VIDEO_DIR.is_dir():
        return []
    return sorted(p.name for p in VIDEO_DIR.iterdir() if p.is_file() and p.suffix.lower() in VIDEO_EXTS)


def mime_for(name):
    ext = Path(name).suffix.lower()
    return MIME.get(ext) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def file_key(name):
    st = (VIDEO_DIR / name).stat()
    return f"{name}|{st.st_mtime_ns}|{st.st_size}"


def duration_of(name):
    """Seconds, or None when ffprobe is missing or can't read the file."""
    key = file_key(name)
    if key not in _durations:
        value = None
        if FFPROBE:
            try:
                out = subprocess.run(
                    [FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(VIDEO_DIR / name)],
                    capture_output=True, text=True, timeout=15,
                ).stdout.strip()
                value = float(out)
            except (ValueError, OSError, subprocess.SubprocessError):
                pass
        _durations[key] = value
    return _durations[key]


# Pictures are served no larger than where they are shown, to keep TV memory use low:
# cards in the wide shelves, cards in the tall shelf, and the home banner.
THUMB_WIDTHS = (480, 320, 640, 960)


def thumb_for(name, width=480):
    """Path to a cached frame from the film at the given width, or None when it can't be made."""
    if not FFMPEG:
        return None
    if width not in THUMB_WIDTHS:
        width = THUMB_WIDTHS[0]
    out = THUMB_DIR / (hashlib.sha1(f"{file_key(name)}|{width}".encode()).hexdigest() + ".jpg")
    if out.exists():
        return out
    with _thumb_lock:
        if out.exists():
            return out
        THUMB_DIR.mkdir(parents=True, exist_ok=True)
        at = min((duration_of(name) or 10) * 0.25, 120)
        try:
            subprocess.run(
                [FFMPEG, "-loglevel", "error", "-y", "-ss", f"{at:.2f}", "-i", str(VIDEO_DIR / name),
                 "-frames:v", "1", "-vf", f"scale={width}:-2", "-q:v", "5", str(out)],
                capture_output=True, timeout=30,
            )
        except (OSError, subprocess.SubprocessError):
            return None
    return out if out.exists() else None


def subs_for(name):
    """A .vtt or .srt file with the same name as the video, if there is one."""
    stem = Path(name).stem
    for candidate in (f"{stem}.vtt", f"{stem}.srt", f"{stem}.fa.vtt", f"{stem}.fa.srt"):
        if (VIDEO_DIR / candidate).is_file():
            return VIDEO_DIR / candidate
    return None


def subs_as_vtt(path):
    raw = path.read_bytes()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        text = raw.decode("utf-16")
    else:
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            text = raw.decode("cp1256", errors="replace")  # the usual encoding of older Persian .srt files
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if path.suffix.lower() == ".srt":
        text = "WEBVTT\n\n" + re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text).strip() + "\n"
    return text


def clock(seconds):
    if seconds is None:
        return ""
    s = int(seconds)
    h, m, s = s // 3600, s % 3600 // 60, s % 60
    return fa(f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}")


def poster_for(name):
    """The film's poster image in ./posters, if it has one."""
    for ext in (".jpg", ".jpeg", ".png", ".webp"):
        candidate = POSTER_DIR / (Path(name).stem + ext)
        if candidate.is_file():
            return candidate
    return None


def card(name, current=None, tall=False, lite=False):
    """A film card. Tall cards show the poster; the wide frame stays available for the banner."""
    title = html.escape(Path(name).stem)
    version = hashlib.sha1(file_key(name).encode()).hexdigest()[:10]
    frame = f"/thumb/{quote(name)}?v={version}&w="
    poster = poster_for(name) if tall else None
    picture = f"/poster/{quote(name)}?v={poster.stat().st_mtime_ns}" if poster else frame + (("480" if tall else "320") if lite else ("640" if tall else "480"))
    badges = ""
    dur = clock(duration_of(name))
    if dur:
        badges += f'<span class="badge dur">{dur}</span>'
    if Path(name).suffix.lower() not in BROWSER_EXTS:
        badges += '<span class="badge vlc">فقط VLC</span>'
    if name == current:
        badges += '<span class="badge now">در حال پخش</span>'
    badges += '<span class="badge done">دیده شده</span>'
    return (
        f'<a class="card" data-focus data-name="{html.escape(name, quote=True)}" data-dur="{duration_of(name) or ""}"'
        f' data-small="{frame}{"320" if lite else "480"}" data-wide="{frame}{"640" if lite else "960"}" href="/watch/{quote(name)}">'
        f'<span class="thumb">{ICON_PLAY}'
        f'<img src="{picture}" alt="" loading="lazy" decoding="async" onerror="this.parentNode.removeChild(this)">'
        f'{badges}<span class="prog"><i></i></span></span>'
        f'<span class="card-title">{title}</span></a>'
    )


def copy_row(url):
    url = html.escape(url, quote=True)
    return f'<div class="url"><code>{url}</code><button class="btn" data-focus data-copy="{url}">کپی</button></div>'


def document(title, body_class, body, lite=False):
    return f"""<!doctype html>
<html lang="fa" dir="rtl"{' class="lite"' if lite else ""}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0f0f0f">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<link rel="manifest" href="/manifest.json">
<link rel="icon" type="image/svg+xml" href="/static/logo.svg">
<link rel="stylesheet" href="/static/style.css">
<title>{html.escape(title)}</title>
</head>
<body class="{body_class}">
{body}
<script src="/static/app.js"></script>
</body>
</html>
"""


def home_page(videos, base, lite=False):
    if not videos:
        body = """<main class="empty">
%s
<h1>هنوز فیلمی اضافه نشده</h1>
<p>فایل فیلم را داخل پوشهٔ <code dir="ltr">videos</code> کنار <code dir="ltr">server.py</code> کپی کنید و صفحه را دوباره باز کنید.</p>
</main>""" % LOGO
        return document(APP_NAME, "home", body, lite)
    first = videos[0]
    version = hashlib.sha1(file_key(first).encode()).hexdigest()[:10]
    meta = " · ".join(x for x in (clock(duration_of(first)), Path(first).suffix[1:].upper()) if x)
    cards = "\n".join(card(v, tall=True, lite=lite) for v in videos)
    # The page script swaps the banner to the film this device watched last.
    body = f"""<header class="nav">
  <span class="brand">{APP_NAME}</span>
</header>
<section class="hero">
  <img class="hero-bg" id="hero-bg" src="/thumb/{quote(first)}?v={version}&w={640 if lite else 960}" alt="" decoding="async" onerror="this.parentNode.removeChild(this)">
  <div class="hero-shade"></div>
  <div class="hero-body">
    <span class="hero-tag" id="hero-tag">پیشنهاد تماشا</span>
    <h1 id="hero-title">{html.escape(Path(first).stem)}</h1>
    <p class="hero-meta" id="hero-meta">{meta}</p>
    <div class="hero-actions">
      <a class="btn play" data-focus id="hero-play" href="/watch/{quote(first)}">{ICON_PLAY}<span>تماشا</span></a>
      <a class="btn ghost" data-focus id="hero-restart" href="/watch/{quote(first)}?restart=1" hidden>پخش از اول</a>
    </div>
  </div>
</section>
<main class="shelves">
<section class="shelf" id="continue" hidden>
  <h2>ادامهٔ تماشا</h2>
  <div class="track" id="continue-track"></div>
</section>
<section class="shelf posters">
  <h2>همهٔ فیلم‌ها <small>{fa(len(videos))} فیلم</small></h2>
  <div class="track">
{cards}
  </div>
</section>
<section class="vlc-card">
  <div>
    <h2>پخش در VLC</h2>
    <p>در VLC گزینهٔ Open Network Stream را باز کنید و این آدرس را وارد کنید تا همهٔ فیلم‌ها پشت سر هم پخش شوند.</p>
  </div>
  {copy_row(base + "/playlist.m3u")}
</section>
</main>
<footer class="statusbar hints"><span>{KEY_MOVE}حرکت</span><span>{KEY_OK}پخش</span></footer>"""
    return document(APP_NAME, "home", body, lite)


# The TV home screen's app shelf. In the browser these are samples; only the film app really opens.
TV_APPS = [  # name, tile colour, logo drawn on a 24x24 grid ({c} is the tile colour)
    ("یوتیوب", "#c4302b", '<rect x="1.5" y="5" width="21" height="14" rx="4.5"/><path fill="{c}" d="M10 8.8v6.4l5.6-3.2z"/>'),
    ("فیلیمو", "#b8860b", '<rect x="3" y="3" width="18" height="18" rx="5"/><path fill="{c}" d="M9.5 7.5v9l7-4.5z"/>'),
    ("نماوا", "#1f6fd6", '<path fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" d="M6 18.5v-13l12 13v-13"/>'),
    ("آپارات", "#c2185b", '<circle cx="12" cy="12" r="10"/><g fill="{c}"><circle cx="12" cy="6.6" r="2.3"/><circle cx="17.4" cy="12" r="2.3"/>'
                          '<circle cx="12" cy="17.4" r="2.3"/><circle cx="6.6" cy="12" r="2.3"/><circle cx="12" cy="12" r="1.2"/></g>'),
    ("تلوبیون", "#00897b", '<path fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" d="M8 2.5l4 3.5 4-3.5"/>'
                           '<rect x="2.5" y="6" width="19" height="14" rx="3"/><path fill="{c}" d="M10 9.6v6.8l5.8-3.4z"/>'),
    ("مرورگر", "#455a64", '<g fill="none" stroke="#fff" stroke-width="1.8"><circle cx="12" cy="12" r="9.5"/><ellipse cx="12" cy="12" rx="4" ry="9.5"/>'
                          '<path d="M2.5 12h19M4 7.5h16M4 16.5h16"/></g>'),
    ("VLC", "#e06c00", '<path d="M11 3h2l1.3 3.5H9.7zM8.9 9h6.2l1.3 3.5H7.6zM6.8 15h10.4l.8 2H20v3.5H4V17h2z"/>'),
]
_HDMI = "M3 8h18v5l-3 4H6l-3-4z"
_ANTENNA = "M12 12v9M12 12h.01M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8"
# name, kind, line-icon path. In the browser the inputs are samples; the TV app swaps in the TV's own list.
TV_SOURCES = [
    ("DTV", "antenna", _ANTENNA), ("ATV", "antenna", _ANTENNA),
    ("AV", "av", "M4 12a8 8 0 1 0 16 0 8 8 0 1 0-16 0M9.5 12a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0"),
    ("HDMI 1", "hdmi", _HDMI), ("HDMI 2", "hdmi", _HDMI), ("HDMI 3", "hdmi", _HDMI),
    ("وای‌فای", "wifi", "M2.5 9a14 14 0 0 1 19 0M5.8 12.6a9 9 0 0 1 12.4 0M9 16a4.5 4.5 0 0 1 6 0M12 19.5h.01"),
    ("تنظیمات", "settings", "M4 7h10M18 7h2M14 7a2 2 0 1 0 4 0 2 2 0 1 0-4 0M4 17h2M10 17h10M6 17a2 2 0 1 0 4 0 2 2 0 1 0-4 0"),
]


def time_json():
    """This computer's clock and its offset from UTC in minutes. The TV home screen shows this
    instead of the TV's own clock, which many TVs lose whenever they are switched off."""
    now = time.time()
    return json.dumps({"now": int(now * 1000), "tz": time.localtime(now).tm_gmtoff // 60})


def tv_page(videos, lite=False, offline=False):
    """The TV home screen: the film app's banner and shelves, plus the clock, apps and inputs.
    offline is the copy built into the TV app, shown while this server can't be reached."""
    if videos:
        first = videos[0]
        version = hashlib.sha1(file_key(first).encode()).hexdigest()[:10]
        meta = " · ".join(x for x in (clock(duration_of(first)), Path(first).suffix[1:].upper()) if x)
        hero = f"""<section class="hero">
  <img class="hero-bg" id="hero-bg" src="/thumb/{quote(first)}?v={version}&w={640 if lite else 960}" alt="" decoding="async" onerror="this.parentNode.removeChild(this)">
  <div class="hero-shade"></div>
  <div class="hero-body">
    <span class="hero-tag" id="hero-tag">پیشنهاد تماشا</span>
    <h1 id="hero-title">{html.escape(Path(first).stem)}</h1>
    <p class="hero-meta" id="hero-meta">{meta}</p>
    <div class="hero-actions">
      <a class="btn play" data-focus id="hero-play" href="/watch/{quote(first)}">{ICON_PLAY}<span>تماشا</span></a>
      <a class="btn ghost" data-focus id="hero-restart" href="/watch/{quote(first)}?restart=1" hidden>پخش از اول</a>
    </div>
  </div>
</section>"""
        films = f"""<section class="shelf" id="continue" hidden>
  <h2>ادامهٔ تماشا</h2>
  <div class="track" id="continue-track"></div>
</section>
<section class="shelf">
  <h2>{APP_NAME} <small>{fa(len(videos))} فیلم</small></h2>
  <div class="track">
{chr(10).join(card(v, lite=lite) for v in videos)}
  </div>
</section>"""
    else:
        notice = (
            ("به سرور فیلم وصل نیست", "برای دیدن فیلم‌ها server.py را روی کامپیوتر اجرا کنید؛ به‌محض پیدا شدن سرور، فیلم‌ها همین‌جا می‌آیند.")
            if offline else ("هنوز فیلمی اضافه نشده", "فایل فیلم را داخل پوشهٔ videos کنار server.py کپی کنید.")
        )
        hero = f"""<section class="hero nofilm">
  <div class="hero-body">
    <h1>{notice[0]}</h1>
    <p class="hero-meta">{notice[1]}</p>
  </div>
</section>"""
        films = ""
    apps = f'<a class="app own" data-focus href="/">{LOGO}<span>{APP_NAME}</span></a>' + "".join(
        f'<button class="app" data-focus data-app="{html.escape(name)}" style="background:{color}">'
        f'<svg viewBox="0 0 24 24" aria-hidden="true">{logo.replace("{c}", color)}</svg>{html.escape(name)}</button>'
        for name, color, logo in TV_APPS
    )
    sources = "".join(f'<button class="app source" data-focus data-kind="{kind}" data-app="{html.escape(name)}">'
        f'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="{icon}"/></svg>{html.escape(name)}</button>'
        for name, kind, icon in TV_SOURCES
    )
    body = f"""<header class="nav">
  <span class="brand">{APP_NAME}</span>
  <nav class="tabs">
    <a class="tab on" data-focus href="/tv">خانه</a>
    <a class="tab" data-focus href="/">فیلم‌ها</a>
    <a class="tab" data-focus href="#apps" id="tab-apps">برنامه‌ها</a>
  </nav>
  <span class="clock"><b id="clock" dir="ltr"></b><small id="date"></small></span>
</header>
{hero}
<main class="shelves">
<section class="shelf" id="apps">
  <h2>برنامه‌ها <small>نمونه</small></h2>
  <div class="track">{apps}</div>
</section>
{films}
<section class="shelf">
  <h2>ورودی‌ها و تنظیمات</h2>
  <div class="track">{sources}</div>
</section>
</main>
<div class="toast" id="toast"></div>
<div class="rest" id="rest"><b id="rest-clock" dir="ltr"></b><span id="rest-day"></span><small id="rest-date"></small></div>
<footer class="statusbar hints"><span>{KEY_MOVE}حرکت</span><span>{KEY_OK}باز کردن</span></footer>
<script>var TV_TIME = {"null" if offline else time_json()};</script>
<script src="/static/launcher.js"></script>"""
    return document(f"خانه · {APP_NAME}", "home tv", body, lite)


def offline_tv_page():
    """The home screen that android-tv/build.sh puts inside the app, next to a copy of ./static."""
    return tv_page([], lite=True, offline=True).replace('="/static/', '="static/')


def watch_page(videos, current, base):
    title = Path(current).stem
    src = f"/video/{quote(current)}"
    playable = Path(current).suffix.lower() in BROWSER_EXTS
    i = videos.index(current)
    data = {
        "name": current,
        "playable": playable,
        "prev": f"/watch/{quote(videos[i - 1])}" if i > 0 else None,
        "next": f"/watch/{quote(videos[i + 1])}" if i + 1 < len(videos) else None,
        "nextName": videos[i + 1] if i + 1 < len(videos) else None,
    }
    has_subs = playable and subs_for(current) is not None
    track = f'<track kind="subtitles" srclang="fa" label="زیرنویس" src="/subs/{quote(current)}" default>' if has_subs else ""
    stage = (
        f'<video id="video" src="{src}" playsinline preload="metadata">{track}</video>'
        if playable
        else f"""<div class="unsupported">
  <h2>این فرمت در مرورگر پخش نمی‌شود</h2>
  <p>این آدرس را در Open Network Stream برنامهٔ VLC وارد کنید:</p>
  {copy_row(base + src)}
</div>"""
    )
    options = '<button class="pill" data-focus id="speed-btn">سرعت</button>'
    if has_subs:
        options += '<button class="pill" data-focus id="subs-btn">زیرنویس</button>'
    options += '<button class="pill" data-focus id="restart-btn">از اول</button>'
    options += '<button class="pill" data-focus id="fs-btn" hidden>تمام‌صفحه</button>'
    controls = f"""<div class="bottom">
    <div class="bar" id="bar"><div class="buf" id="buf"></div><div class="fill" id="fill"><i></i></div></div>
    <div class="row">
      <div class="lead">
        <span class="time" id="time" dir="ltr"></span>
        <div class="options" id="options">{options}</div>
      </div>
      <span class="hints"><span>{KEY_SIDES}۱۰ ثانیه</span><span>{KEY_OK}پخش / مکث</span><span>{KEY_DOWN}گزینه‌ها و فیلم‌های دیگر</span><span>{KEY_BACK}بازگشت</span></span>
    </div>
  </div>""" if playable else ""
    body = f"""{stage}
<div class="ui" id="ui">
  <div class="topbar">
    <a class="pill back" data-focus href="/" id="back">{ICON_BACK}<span>بازگشت</span></a>
    <h1>{html.escape(title)}</h1>
  </div>
  <button class="bigplay" id="bigplay" hidden aria-label="پخش">{ICON_PLAY}</button>
  {controls}
</div>
<div class="flash" id="flash"></div>
<section class="rail" id="rail">
  <h2 id="rail-title">فیلم‌های دیگر</h2>
  <div class="track">
{"".join(card(v, current) for v in videos)}
  </div>
</section>
<script type="application/json" id="data">{json.dumps(data, ensure_ascii=False).replace("</", "<\\/")}</script>"""
    return document(f"{title} · {APP_NAME}", "player" if playable else "player novideo", body)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def base_url(self):
        host = self.headers.get("Host") or f"{lan_ip()}:{PORT}"
        # localhost is useless to other devices, so show the LAN address instead
        if host.split(":")[0] in ("localhost", "127.0.0.1", "[::1]"):
            host = f"{lan_ip()}:{PORT}"
        return f"http://{host}"

    def send_body(self, body, ctype, status=200, cache="no-cache"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_POST(self):
        # the TV app's note about itself (static/launcher.js); kept only as the latest one
        length = int(self.headers.get("Content-Length") or 0)
        if self.path != "/tvinfo" or not 0 < length <= 512 * 1024:
            return self.send_body("Not found", "text/plain; charset=utf-8", 404)
        THUMB_DIR.parent.mkdir(parents=True, exist_ok=True)
        (THUMB_DIR.parent / "tv-info.json").write_bytes(self.rfile.read(length))
        self.send_body("ok\n", "text/plain; charset=utf-8")

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path, _, query = self.path.partition("?")
        path = unquote(path)
        videos = list_videos()
        if path == "/":
            # the Android TV app marks its requests; it gets smaller pictures and no animation
            lite = "MousaviTV" in self.headers.get("User-Agent", "")
            return self.send_body(home_page(videos, self.base_url(), lite), "text/html; charset=utf-8")
        if path == "/tv":
            lite = "MousaviTV" in self.headers.get("User-Agent", "")
            return self.send_body(tv_page(videos, lite), "text/html; charset=utf-8")
        # names are only ever matched against directory listings, so no path traversal
        if path.startswith("/watch/") and path[7:] in videos:
            return self.send_body(watch_page(videos, path[7:], self.base_url()), "text/html; charset=utf-8")
        if path.startswith("/video/") and path[7:] in videos:
            return self.stream(VIDEO_DIR / path[7:])
        if path.startswith("/thumb/") and path[7:] in videos:
            width = re.search(r"(?:^|&)w=(\d+)", query)
            thumb = thumb_for(path[7:], int(width.group(1)) if width else 480)
            if thumb:
                return self.send_body(thumb.read_bytes(), "image/jpeg", cache="public, max-age=31536000")
        if path.startswith("/poster/") and path[8:] in videos:
            poster = poster_for(path[8:])
            if poster:
                return self.send_body(poster.read_bytes(), mime_for(poster.name), cache="public, max-age=31536000")
        if path.startswith("/subs/") and path[6:] in videos:
            subs = subs_for(path[6:])
            if subs:
                return self.send_body(subs_as_vtt(subs), "text/vtt; charset=utf-8")
        if path.startswith("/static/"):
            file = (STATIC_DIR / path[8:]).resolve()
            if STATIC_DIR in file.parents and file.is_file():  # nothing outside ./static
                if file.suffix == ".ttf":
                    return self.send_body(file.read_bytes(), "font/ttf", cache="public, max-age=31536000")
                ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
                return self.send_body(file.read_bytes(), f"{ctype}; charset=utf-8")
        if path == "/time":
            return self.send_body(time_json(), "application/json", cache="no-store")
        if path == "/ping":  # lets the Android TV app find this server on the network
            return self.send_body("video-app\n", "text/plain; charset=utf-8")
        if path == "/playlist.m3u":
            base = self.base_url()
            lines = ["#EXTM3U"] + [f"#EXTINF:-1,{Path(v).stem}\n{base}/video/{quote(v)}" for v in videos]
            return self.send_body("\n".join(lines) + "\n", "audio/x-mpegurl; charset=utf-8")
        if path == "/manifest.json":
            return self.send_body(
                '{"name":"موسوی مووی","short_name":"موسوی مووی","start_url":"/","display":"fullscreen",'
                '"background_color":"#0f0f0f","theme_color":"#0f0f0f"}',
                "application/manifest+json; charset=utf-8",
            )
        self.send_body("Not found", "text/plain; charset=utf-8", 404)

    def stream(self, file):
        size = file.stat().st_size
        start, end, status = 0, size - 1, 200
        m = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", "").strip())
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            else:  # suffix range: last N bytes
                start = max(size - int(m.group(2)), 0)
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mime_for(file.name))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with open(file, "rb") as f:
                f.seek(start)
                while length > 0:
                    buf = f.read(min(CHUNK, length))
                    if not buf:
                        break
                    self.wfile.write(buf)
                    length -= len(buf)
        except (BrokenPipeError, ConnectionResetError):
            pass  # player seeked or closed the connection

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    VIDEO_DIR.mkdir(exist_ok=True)
    try:
        server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError:
        sys.exit(f"{APP_NAME} is already running (port {PORT} is in use): http://{lan_ip()}:{PORT}/")
    server.daemon_threads = True
    print(f"{APP_NAME} is running. Keep this window open; press Ctrl+C to stop.")
    print(f"  On this computer:    http://127.0.0.1:{PORT}/")
    print(f"  TV / phone / tablet: http://{lan_ip()}:{PORT}/")
    print(f"  VLC network stream:  http://{lan_ip()}:{PORT}/playlist.m3u")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
