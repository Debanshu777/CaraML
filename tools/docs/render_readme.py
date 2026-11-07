#!/usr/bin/env python3
"""Compose the blue-flower cover from a real Paper Lens Distortion export.

Install tools/docs/media-requirements.txt, then run this file from any directory.
Text outlines keep the brand typography consistent without remote font requests.
"""

import base64
from pathlib import Path

import cairosvg
from PIL import Image

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "docs/assets"
FONTS = ROOT / "app/composeApp/src/commonMain/composeResources/font"


def lettering(text, filename, size, baseline, color, center=600, spacing=0, left=None):
    with TTFont(FONTS / filename) as font:
        glyphs = font.getGlyphSet()
        mapping = font.getBestCmap()
        scale = size / font["head"].unitsPerEm
        width = sum(glyphs[mapping[ord(c)]].width * scale for c in text)
        width += spacing * (len(text) - 1)
        cursor = center - width / 2 if left is None else left
        paths = []
        for char in text:
            glyph = glyphs[mapping[ord(char)]]
            pen = SVGPathPen(glyphs)
            glyph.draw(pen)
            if pen.getCommands():
                paths.append(
                    f'<path d="{pen.getCommands()}" transform="translate({cursor:.3f} {baseline}) '
                    f'scale({scale:.6f} {-scale:.6f})"/>'
                )
            cursor += glyph.width * scale + spacing
    return f'<g fill="{color}">' + "".join(paths) + "</g>"


def main():
    work = ROOT / "build/artwork"
    work.mkdir(parents=True, exist_ok=True)
    with Image.open(ROOT / "tools/docs/artwork/blue-flower-lens.png") as source:
        source.convert("RGB").save(ASSETS / "blue-flower-lens.webp", quality=92, method=6)
    background = base64.b64encode((ASSETS / "blue-flower-lens.webp").read_bytes()).decode("ascii")
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1600" height="800" viewBox="0 0 1600 800" role="img" aria-labelledby="title desc">
  <title id="title">CaraML — local AI for Kotlin Multiplatform</title>
  <desc id="desc">Your AI. Right here. An upright CaraML wordmark and local AI introduction beside a circular lens containing a luminous blue flower.</desc>
  <defs>
    <clipPath id="lens"><circle cx="1220" cy="385" r="300"/></clipPath>
  </defs>
  <rect width="1600" height="800" fill="#eef3fc"/>
  {lettering("CaraML", "bricolage_grotesque_800.ttf", 42, 119, "#172e50", left=88, spacing=-1)}
  {lettering("Your AI.", "bricolage_grotesque_600.ttf", 116, 306, "#172e50", left=80, spacing=-4)}
  {lettering("Right here.", "bricolage_grotesque_600.ttf", 116, 423, "#285be6", left=80, spacing=-4)}
  {lettering("Local chat and image generation.", "dm_sans_400.ttf", 29, 502, "#47617f", left=88)}
  {lettering("Built with Kotlin Multiplatform.", "dm_sans_400.ttf", 29, 545, "#47617f", left=88)}
  {lettering("ANDROID  /  iOS  /  DESKTOP", "dm_sans_600.ttf", 19, 688, "#47617f", left=88, spacing=1.5)}
  {lettering("llama.cpp  ·  ONNX Runtime  ·  stable-diffusion.cpp", "dm_sans_400.ttf", 22, 732, "#47617f", left=88)}
  <g clip-path="url(#lens)">
    <rect x="920" y="85" width="600" height="600" fill="#061329"/>
    <image x="260" y="70" width="1280" height="640" xlink:href="data:image/webp;base64,{background}"/>
  </g>
  {lettering("ON YOUR HARDWARE", "dm_sans_500.ttf", 17, 732, "#47617f", center=1220, spacing=2)}
</svg>
'''
    (work / "readme-banner.svg").write_text(svg, encoding="utf-8")
    target = ASSETS / "readme-banner.png"
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(target))
    with Image.open(target) as rendered:
        rendered.save(target, optimize=True)
        rendered.save(target.with_suffix(".webp"), quality=92, method=6)
    # Keep the entire composition in the 1200 × 630 social card; no text cropping.
    social = svg.replace('width="1600" height="800" viewBox="0 0 1600 800"',
                         'width="1200" height="630" viewBox="0 -20 1600 840"', 1)
    social = social.replace('<rect width="1600" height="800" fill="#eef3fc"/>', '<rect y="-20" width="1600" height="840" fill="#eef3fc"/>', 1)
    cairosvg.svg2png(bytestring=social.encode(), write_to=str(ASSETS / "social-preview.png"))
    print(f"Rendered {target}")


if __name__ == "__main__":
    main()
