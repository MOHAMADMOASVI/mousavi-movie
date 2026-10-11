#!/usr/bin/env python3
"""Makes a poster for every film in ./videos that doesn't have one yet, into ./posters.

A poster is a frame from the film, cropped tall, with the title set over it. It is drawn as a
small web page and photographed with headless Chrome, so Persian titles are shaped correctly.
Run it again after adding films; pass --all to redo existing posters. To use your own artwork
instead, put an image named like the video (e.g. "My Film.jpg") in ./posters.
"""
import html
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from server import APP_NAME, FFMPEG, POSTER_DIR, VIDEO_DIR, duration_of, list_videos, poster_for

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
WIDTH, HEIGHT = 600, 900

PAGE = """<!doctype html>
<html lang="fa" dir="rtl">
<meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Alexandria:wght@400;700&display=swap">
<style>
  html, body {{ margin: 0; width: {w}px; height: {h}px; overflow: hidden; background: #0f0f0f; }}
  body {{ position: relative; font-family: "Alexandria", "Vazirmatn", Tahoma, sans-serif; color: #fff; }}
  img {{ position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: cover; filter: saturate(1.15) contrast(1.05); }}
  .shade {{ position: absolute; top: 0; left: 0; right: 0; bottom: 0;
    background: linear-gradient(to top, rgba(8,8,8,.96) 0%, rgba(8,8,8,.7) 28%, rgba(8,8,8,0) 58%),
                linear-gradient(to bottom, rgba(8,8,8,.6) 0%, rgba(8,8,8,0) 22%); }}
  .brand {{ position: absolute; top: 34px; right: 40px; font-size: 22px; font-weight: 700; opacity: .92; }}
  .text {{ position: absolute; right: 40px; left: 40px; bottom: 48px; }}
  .label {{ display: inline-block; padding: 4px 16px; border-radius: 99px; background: #f6b300; color: #151000;
    font-size: 22px; font-weight: 700; }}
  h1 {{ margin: 18px 0 0; font-size: {size}px; font-weight: 700; line-height: 1.3; overflow-wrap: anywhere;
    text-shadow: 0 4px 24px rgba(0,0,0,.7); }}
  .rule {{ width: 72px; height: 6px; margin-top: 22px; border-radius: 99px; background: #f6b300; }}
</style>
<img src="{frame}" alt="">
<div class="shade"></div>
<div class="brand">{brand}</div>
<div class="text">{label}<h1>{title}</h1><div class="rule"></div></div>
</html>
"""


def make(name, work):
    stem = Path(name).stem
    # "Series 01 - Episode name" becomes a small label over a big title
    label, _, title = stem.partition(" - ") if " - " in stem else ("", "", stem)
    size = 84 if len(title) <= 10 else 64 if len(title) <= 18 else 46

    frame = work / "frame.jpg"
    at = min((duration_of(name) or 10) * 0.3, 300)
    subprocess.run(
        [FFMPEG, "-loglevel", "error", "-y", "-ss", f"{at:.2f}", "-i", str(VIDEO_DIR / name),
         "-vf", f"thumbnail=40,scale=-2:{HEIGHT}", "-frames:v", "1", "-q:v", "3", str(frame)],
        check=True, timeout=120,
    )
    page = work / "poster.html"
    page.write_text(PAGE.format(
        w=WIDTH, h=HEIGHT, size=size, frame=frame.as_uri(), brand=html.escape(APP_NAME), title=html.escape(title),
        label=f'<span class="label">{html.escape(label)}</span>' if label else "",
    ), encoding="utf-8")

    shot = work / "poster.png"
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
         f"--user-data-dir={work / 'chrome'}",  # a throwaway profile, so the user's own Chrome is untouched
         f"--window-size={WIDTH},{HEIGHT}", "--virtual-time-budget=5000", f"--screenshot={shot}", page.as_uri()],
        check=True, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    out = POSTER_DIR / f"{stem}.jpg"
    subprocess.run([FFMPEG, "-loglevel", "error", "-y", "-i", str(shot), "-q:v", "3", str(out)], check=True)
    return out


if __name__ == "__main__":
    if not FFMPEG or not Path(CHROME).exists():
        sys.exit("make_posters.py needs ffmpeg and Google Chrome.")
    redo = "--all" in sys.argv
    POSTER_DIR.mkdir(exist_ok=True)
    for video in list_videos():
        if poster_for(video) and not redo:
            print(f"has a poster  {video}")
            continue
        work = Path(tempfile.mkdtemp(prefix="poster-"))
        try:
            print(f"made          {make(video, work).name}")
        except (subprocess.SubprocessError, OSError) as error:
            print(f"FAILED        {video}: {error}")
        finally:
            shutil.rmtree(work, ignore_errors=True)
