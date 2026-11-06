#!/usr/bin/env python3
"""Render the README banner from CaraML's bundled font and the Flow SVG.

Install tools/docs/media-requirements.txt, then run this file from any directory.
Text outlines keep the brand typography consistent without remote font requests.
"""

from pathlib import Path
import xml.etree.ElementTree as ET

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
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    flow = ET.parse(ASSETS / "flow.svg").getroot()
    avatar = "".join(ET.tostring(e, encoding="unicode") for e in flow if e.tag.rsplit("}", 1)[-1] not in {"title", "desc"})
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="440" viewBox="0 0 1200 440" role="img" aria-labelledby="title desc">
  <title id="title">CaraML — local AI for Kotlin Multiplatform</title>
  <desc id="desc">Flow, a curling blue wave, beside the CaraML wordmark. Your models. Your device.</desc>
  <defs>
    <linearGradient id="paper" x2="1" y2="1"><stop stop-color="#f8fbff"/><stop offset="1" stop-color="#edf5ff"/></linearGradient>
    <radialGradient id="sky"><stop stop-color="#61b5ff" stop-opacity=".27"/><stop offset="1" stop-color="#61b5ff" stop-opacity="0"/></radialGradient>
    <radialGradient id="blue"><stop stop-color="#5b85f5" stop-opacity=".18"/><stop offset="1" stop-color="#5b85f5" stop-opacity="0"/></radialGradient>
    <clipPath id="canvas"><rect width="1200" height="440" rx="28"/></clipPath>
  </defs>
  <g clip-path="url(#canvas)">
    <rect width="1200" height="440" fill="url(#paper)"/>
    <ellipse cx="1120" cy="40" rx="510" ry="330" fill="url(#sky)"/>
    <ellipse cx="65" cy="420" rx="510" ry="290" fill="url(#blue)"/>
    <g fill="none" stroke="#75a9ed" stroke-opacity=".13" stroke-width="1.5">
      <path d="M-130 373C86 151 221 508 419 370S748 219 963 327S1190 441 1327 229"/>
      <path d="M-130 398C86 176 221 533 419 395S748 244 963 352S1190 466 1327 254"/>
      <path d="M-130 423C86 201 221 558 419 420S748 269 963 377S1190 491 1327 279"/>
    </g>
    {lettering("KOTLIN MULTIPLATFORM", "dm_sans_600.ttf", 14, 90, "#476587", spacing=3)}
    <g transform="translate(310 131) scale(.88)">{avatar}</g>
    {lettering("CaraML", "bricolage_grotesque_800.ttf", 114, 237, "#112b50", center=662, spacing=-2)}
    {lettering("Your models. Your device.", "dm_sans_400.ttf", 29, 303, "#476587")}
  </g>
</svg>
'''
    target = ASSETS / "readme-banner.svg"
    target.write_text(svg, encoding="utf-8")
    print(f"Rendered {target}")


if __name__ == "__main__":
    main()
