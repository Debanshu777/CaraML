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


def lettering(text, filename, size, baseline, color, center=600, spacing=0):
    with TTFont(FONTS / filename) as font:
        glyphs = font.getGlyphSet()
        mapping = font.getBestCmap()
        scale = size / font["head"].unitsPerEm
        width = sum(glyphs[mapping[ord(c)]].width * scale for c in text)
        width += spacing * (len(text) - 1)
        cursor = center - width / 2
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
    wordmark = lettering("CaraML", "bricolage_grotesque_800.ttf", 162, 337, "#d7edff", center=800, spacing=-3)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1600" height="800" viewBox="0 0 1600 800" role="img" aria-labelledby="title desc">
  <title id="title">CaraML — local AI for Kotlin Multiplatform</title>
  <desc id="desc">CaraML over a luminous blue flower refracted through a grainy lens. Your models. Your device.</desc>
  <defs>
    <linearGradient id="shade" x2="0" y2="1"><stop stop-color="#061329" stop-opacity=".05"/><stop offset=".48" stop-color="#061329" stop-opacity=".4"/><stop offset="1" stop-color="#061329" stop-opacity=".08"/></linearGradient>
    <clipPath id="canvas"><rect width="1600" height="800" rx="32"/></clipPath>
  </defs>
  <g clip-path="url(#canvas)">
    <image width="1600" height="800" xlink:href="data:image/webp;base64,{background}"/>
    <rect width="1600" height="800" fill="url(#shade)"/>
    <g fill="none" stroke="#8abfff" stroke-opacity=".19" stroke-width="1">
      <path d="M360 96V704M1240 96V704M112 202H1488M112 382H1488M112 548H1488" stroke-dasharray="5 8"/>
      <circle cx="360" cy="202" r="5"/><circle cx="1240" cy="202" r="5"/>
      <circle cx="360" cy="382" r="5"/><circle cx="1240" cy="382" r="5"/>
      <circle cx="360" cy="548" r="5"/><circle cx="1240" cy="548" r="5"/>
    </g>
    <g transform="translate(49.4 0) skewX(-10)">{wordmark}</g>
    {lettering("Your models. Your device.", "dm_sans_500.ttf", 40, 461, "#d3e9ff", center=800)}
    {lettering("Local AI with Kotlin Multiplatform", "dm_sans_400.ttf", 27, 508, "#a8c9ef", center=800)}
  </g>
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
    social = social.replace('<g clip-path="url(#canvas)">', '<rect x="0" y="-20" width="1600" height="840" fill="#061329"/><g clip-path="url(#canvas)">', 1)
    cairosvg.svg2png(bytestring=social.encode(), write_to=str(ASSETS / "social-preview.png"))
    print(f"Rendered {target}")


if __name__ == "__main__":
    main()
