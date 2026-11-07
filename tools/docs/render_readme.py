#!/usr/bin/env python3
"""Compose the blue-flower cover from a real Paper Lens Distortion export.

Install tools/docs/media-requirements.txt, then run this file from any directory.
Text outlines keep the brand typography consistent without remote font requests.
"""

import base64
from pathlib import Path
import random

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


def grain_pattern():
    """Seeded fine grain, rendered below lettering so text stays sharp."""
    randomizer = random.Random(777)
    tones = {}
    for y in range(128):
        for x in range(128):
            tone = max(-10, min(10, round(randomizer.gauss(0, 3))))
            if tone:
                tones.setdefault(tone, []).append(f"M{x} {y}h1v1h-1z")
    paths = "".join(
        f'<path fill="{"#fff" if tone > 0 else "#000"}" opacity="{abs(tone) * .018:.3f}" d="{"".join(commands)}"/>'
        for tone, commands in sorted(tones.items())
    )
    return '<pattern id="grain" width="128" height="128" patternUnits="userSpaceOnUse" patternTransform="scale(1.25)">' + paths + '</pattern>'


def main():
    work = ROOT / "build/artwork"
    work.mkdir(parents=True, exist_ok=True)
    with Image.open(ROOT / "tools/docs/artwork/blue-flower-lens.png") as source:
        source.convert("RGB").save(ASSETS / "blue-flower-lens.webp", quality=92, method=6)
    background = base64.b64encode((ASSETS / "blue-flower-lens.webp").read_bytes()).decode("ascii")
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1600" height="560" viewBox="0 0 1600 560" role="img" aria-labelledby="title desc">
  <title id="title">CaraML — local AI for Kotlin Multiplatform</title>
  <desc id="desc">CaraML. On-device AI with Kotlin Multiplatform. An upright wordmark on the left, with a blue flower across the right of a panoramic masthead.</desc>
  <defs>
    <linearGradient id="shade"><stop stop-color="#061329" stop-opacity=".25"/><stop offset=".55" stop-color="#061329" stop-opacity=".1"/><stop offset="1" stop-color="#061329" stop-opacity="0"/></linearGradient>
    {grain_pattern()}
  </defs>
  <rect width="1600" height="560" fill="#061329"/>
  <image y="-130" width="1600" height="800" xlink:href="data:image/webp;base64,{background}"/>
  <rect width="1600" height="560" fill="url(#shade)"/>
  <rect width="1600" height="560" fill="url(#grain)"/>
  {lettering("CaraML", "bricolage_grotesque_600.ttf", 134, 274, "#e7f2ff", left=88, spacing=-3)}
  {lettering("On-device AI with Kotlin Multiplatform", "dm_sans_400.ttf", 28, 338, "#b9d0eb", left=96)}
</svg>
'''
    (work / "readme-banner.svg").write_text(svg, encoding="utf-8")
    target = ASSETS / "readme-banner.png"
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(target))
    with Image.open(target) as rendered:
        rendered.save(target, optimize=True)
        rendered.save(target.with_suffix(".webp"), quality=92, method=6)
    # Extend the photographic canvas for the 1200 × 630 social card.
    social = svg.replace('width="1600" height="560" viewBox="0 0 1600 560"',
                         'width="1200" height="630" viewBox="0 -140 1600 840"', 1)
    social = social.replace('<rect width="1600" height="560"', '<rect y="-140" width="1600" height="840"')
    social = social.replace('<image y="-130" width="1600" height="800"', '<image y="-140" width="1680" height="840"')
    cairosvg.svg2png(bytestring=social.encode(), write_to=str(ASSETS / "social-preview.png"))
    print(f"Rendered {target}")


if __name__ == "__main__":
    main()
