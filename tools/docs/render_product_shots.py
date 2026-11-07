#!/usr/bin/env python3
"""Lay out store-style product cards around unaltered device screenshots.

Install tools/docs/media-requirements.txt and Cairo, then run from any directory.
Exports 1080 × 1920 PNGs and smaller WebP previews for the README and docs.
"""

import base64
from html import escape
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image

from render_readme import ASSETS, ROOT, lettering

OUTPUT = ASSETS / "product"
WORK = ROOT / "build/product"
CARDS = [
    {
        "name": "01-local-chat", "capture": "chat.png",
        "heading": ("Ideas in.", "Answers out."),
        "subtitle": "Chat with a model on your device.",
        "caption": "LLAMA.CPP · GGUF CHAT",
        "background": ("#061329", "#12356c"),
    },
    {
        "name": "02-model-library", "capture": "models.png",
        "heading": ("Your models.", "Your choice."),
        "subtitle": "GGUF and ONNX text, in one library.",
        "caption": "ONNX RUNTIME + GGUF",
        "background": ("#102751", "#133c82"),
    },
    {
        "name": "03-image-generation", "capture": "diffusion.png",
        "heading": ("Picture it.", "Create it."),
        "subtitle": "Turn a prompt into an image, locally.",
        "caption": "STABLE-DIFFUSION.CPP · SD-TURBO",
        "background": ("#071b3c", "#1b4b91"),
    },
    {
        "name": "04-iphone", "capture": "ios.png",
        "heading": ("Feels at home.", "On iPhone."),
        "subtitle": "Built with Kotlin Multiplatform.",
        "caption": "START SCREEN · IPHONE 13",
        "background": ("#061329", "#10274d"),
    },
]


def render(card):
    capture = ASSETS / "screenshots" / card["capture"]
    with Image.open(capture) as source:
        screen_height = 600 * source.height / source.width
    screen_x, screen_y = 240, 490
    encoded = base64.b64encode(capture.read_bytes()).decode("ascii")
    flow = ET.parse(ASSETS / "flow.svg").getroot()
    avatar = "".join(
        ET.tostring(child, encoding="unicode") for child in flow
        if child.tag.rsplit("}", 1)[-1] not in {"title", "desc"}
    )
    flower = base64.b64encode((ASSETS / "blue-flower-lens.webp").read_bytes()).decode("ascii")
    ink, muted = "#d7edff", "#b7cfee"
    title = escape("CaraML — " + " ".join(card["heading"]))
    text = [
        lettering("CaraML", "bricolage_grotesque_700.ttf", 36, 99, ink, center=569),
        lettering(card["heading"][0], "bricolage_grotesque_800.ttf", 106, 240, ink, center=540, spacing=-2),
        lettering(card["heading"][1], "bricolage_grotesque_800.ttf", 106, 348, ink, center=540, spacing=-2),
        lettering(card["subtitle"], "dm_sans_400.ttf", 32, 409, muted, center=540),
        lettering(card["caption"], "dm_sans_600.ttf", 23, 1890, muted, center=540, spacing=2),
    ]
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1080" height="1920" viewBox="0 0 1080 1920" role="img" aria-labelledby="title desc">
      <title id="title">{title}</title>
      <desc id="desc">Product card containing a real CaraML {escape(card['capture'].removesuffix('.png'))} screenshot.</desc>
      <defs>
        <linearGradient id="background" x2=".6" y2="1"><stop stop-color="{card['background'][0]}"/><stop offset="1" stop-color="{card['background'][1]}"/></linearGradient>
        <linearGradient id="rim" x2="1" y2="1"><stop stop-color="#8197b3"/><stop offset=".45" stop-color="#263c59"/><stop offset="1" stop-color="#587291"/></linearGradient>
        <clipPath id="screen"><rect x="{screen_x}" y="{screen_y}" width="600" height="{screen_height}" rx="39"/></clipPath>
      </defs>
      <rect width="1080" height="1920" fill="url(#background)"/>
      <image x="-1850" y="0" width="3840" height="1920" opacity=".55" xlink:href="data:image/webp;base64,{flower}"/>
      <path d="M100 150V1810M980 150V1810M45 445H1035" fill="none" stroke="#8abfff" stroke-opacity=".16" stroke-dasharray="5 8"/>
      <g transform="translate(433 56) scale(.42)">{avatar}</g>
      {''.join(text)}
      <rect x="211" y="488" width="658" height="{screen_height + 34}" rx="63" fill="#021329" opacity=".09"/>
      <rect x="219" y="479" width="642" height="{screen_height + 34}" rx="59" fill="#021329" opacity=".16"/>
      <rect x="225" y="475" width="630" height="{screen_height + 30}" rx="55" fill="url(#rim)"/>
      <rect x="228" y="478" width="624" height="{screen_height + 24}" rx="52" fill="#0b1524"/>
      <image x="{screen_x}" y="{screen_y}" width="600" height="{screen_height}" clip-path="url(#screen)" xlink:href="data:image/png;base64,{encoded}"/>
    </svg>'''
    # The code-native layout is retained locally for inspecting typography/frames.
    (WORK / f"{card['name']}.svg").write_text(svg, encoding="utf-8")
    target = OUTPUT / f"{card['name']}.png"
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(target))
    with Image.open(target) as rendered:
        rendered.convert("RGB").save(target, optimize=True)
        rendered.resize((540, 960), Image.Resampling.LANCZOS).convert("RGB").save(
            target.with_suffix(".webp"), quality=92, method=6,
        )
    print(f"Rendered {target}")


def main():
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    for card in CARDS:
        render(card)


if __name__ == "__main__":
    main()
