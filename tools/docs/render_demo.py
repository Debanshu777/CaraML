#!/usr/bin/env python3
"""Compose a 32-second product demo from actual app captures with local FFmpeg.

Run from anywhere: python tools/docs/render_demo.py --chat-video build/caraml-android-raw.mp4
The source recording must show only a public sample conversation in CaraML.
"""

import argparse
from pathlib import Path
import subprocess

import imageio_ffmpeg
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "docs/assets"
WORK = ROOT / "build/demo"
FONTS = ROOT / "app/composeApp/src/commonMain/composeResources/font"
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()


def run(args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def text_filter(text, name, x, y, size, color, bold=False):
    path = WORK / f"{name}.txt"
    path.write_text(text)
    font = FONTS / ("bricolage_grotesque_700.ttf" if bold else "dm_sans_400.ttf")
    # Paths are passed within a filter expression, not interpolated into a shell.
    def escape(value):
        return str(value).replace("\\", "/").replace(":", "\\:").replace("'", "'\\''")
    return (f"drawtext=fontfile='{escape(font)}':textfile='{escape(path)}':"
            f"x={x}:y={y}:fontsize={size}:fontcolor={color}:"
            "alpha='min(t/0.25,1)':line_spacing=8")


def scene(number, media, duration, heading, body, footnote, video=False, start=5):
    output = WORK / f"scene-{number}.mp4"
    inputs = ["-f", "lavfi", "-i", f"color=c=0xf8fbff:s=1280x720:r=30:d={duration}"]
    inputs += (["-ss", str(start), "-i", str(media)] if video else ["-loop", "1", "-i", str(media)])
    filters = [
        "[1:v]scale=-2:624,setsar=1,fps=30,tpad=stop_mode=clone:stop_duration=10[phone]",
        "[0:v]drawbox=x=876:y=51:w=292:h=634:color=0x152d4d:t=fill[base]",
        "[base][phone]overlay=x=882:y=46:shortest=1",
    ]
    overlays = [
        text_filter("CaraML", f"{number}-brand", 68, 48, 34, "0x152d4d", True),
        "drawbox=x=68:y=116:w=78:h=7:color=0x245edb:t=fill",
        text_filter(heading, f"{number}-heading", 64, 187, 76, "0x152d4d", True),
        text_filter(body, f"{number}-body", 68, 393, 28, "0x476587"),
        text_filter(footnote, f"{number}-footnote", 68, 568, 20, "0x476587"),
        text_filter("github.com/Debanshu777/CaraML", f"{number}-url", 68, 640, 22, "0x2156b0"),
    ]
    graph = ";".join(filters) + "," + ",".join(overlays) + "[out]"
    run([*inputs, "-filter_complex", graph, "-map", "[out]", "-t", str(duration),
         "-an", "-c:v", "libx264", "-crf", "21", "-preset", "medium", "-pix_fmt", "yuv420p",
         "-r", "30", "-movflags", "+faststart", str(output)])
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chat-video", type=Path, required=True)
    parser.add_argument("--image-video", type=Path, help="Optional real generation recording; progress begins around 8 seconds.")
    args = parser.parse_args()
    video = args.chat_video.resolve()
    if not video.is_file():
        parser.error(f"Missing source recording: {video}")
    image_video = args.image_video.resolve() if args.image_video else None
    if image_video is not None and not image_video.is_file():
        parser.error(f"Missing image recording: {image_video}")
    WORK.mkdir(parents=True, exist_ok=True)
    # Lossless format conversion keeps the original capture pixels while reducing
    # page weight. Keep PNG originals as the source for future video renders.
    for capture in (ASSETS / "screenshots").glob("*.png"):
        with Image.open(capture) as screenshot:
            screenshot.save(capture.with_suffix(".webp"), lossless=True, method=6)
    clips = [
        scene(1, ASSETS / "screenshots/start.png", 4, "Your models.\nYour device.",
              "Local AI, built with\nKotlin Multiplatform.", "Android  /  iOS  /  Desktop (JVM)"),
        scene(2, video, 10, "An idea in.\nAn answer out.",
              "A real conversation with\nQwen2.5 3B on a Pixel 9.", "Actual app recording · edited walkthrough", video=True),
        scene(3, ASSETS / "screenshots/models.png", 5, "Choose your\nengine.",
              "llama.cpp + ONNX Runtime.\nOne local model library.", "Download first. Run inference locally."),
        scene(4, image_video or ASSETS / "screenshots/diffusion-progress.png", 2, "Picture it.\nCreate it.",
              "stable-diffusion.cpp.\nImages generated on your device.", "SD-Turbo on Pixel 9 · edited capture",
              video=image_video is not None, start=8),
        scene(5, ASSETS / "screenshots/diffusion.png", 6, "Picture it.\nCreate it.",
              "stable-diffusion.cpp.\nImages generated on your device.", "SD-Turbo on Pixel 9 · edited capture"),
        scene(6, ASSETS / "screenshots/chat.png", 5, "Make it\nlocal.",
              "Explore the source.\nBuild CaraML on your device.", "Source-available · commercial permission required"),
    ]
    concat = WORK / "clips.txt"
    concat.write_text("".join(f"file '{clip.name}'\n" for clip in clips))
    intermediate = WORK / "walkthrough.mp4"
    run(["-f", "concat", "-safe", "0", "-i", str(concat), "-c", "copy", str(intermediate)])
    poster = ASSETS / "demo-poster.jpg"
    run(["-ss", "29", "-i", str(intermediate), "-frames:v", "1", "-q:v", "2", str(poster)])
    # The selected final scene is also the first frame, for players without poster support.
    poster_clip = WORK / "poster.mp4"
    run(["-loop", "1", "-i", str(poster), "-frames:v", "1", "-r", "30", "-an", "-c:v", "libx264",
         "-pix_fmt", "yuv420p", "-crf", "21", str(poster_clip)])
    concat.write_text("file 'poster.mp4'\nfile 'walkthrough.mp4'\n")
    run(["-f", "concat", "-safe", "0", "-i", str(concat), "-c", "copy", "-movflags", "+faststart",
         str(ASSETS / "caraml-demo.mp4")])
    run(["-ss", "29", "-i", str(intermediate), "-vf", "scale=1200:675,crop=1200:630", "-frames:v", "1",
         str(ASSETS / "social-preview.png")])
    print(f"Rendered {ASSETS / 'caraml-demo.mp4'} and poster/social assets.")


if __name__ == "__main__":
    main()
