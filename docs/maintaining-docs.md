---
description: Build, preview, validate, and publish CaraML documentation with MkDocs. Maintain accurate metadata, screenshots, benchmarks, and GitHub discoverability.
---

# Maintain the documentation

The public overview belongs in the root README. Setup and user/developer guides live here; module READMEs and benchmark reports remain the detailed source references. Avoid repeating dependency versions or performance claims across pages.

## Local preview

From the repository root with Python 3.10 or newer:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r tools/docs/requirements.txt
python -m mkdocs serve
```

On Windows, activate with `.venv\Scripts\Activate.ps1` in PowerShell. Open the local URL printed by MkDocs. No native engine or model download is needed.

## Validate before publishing

```bash
python -m mkdocs build --strict
python tools/docs/check_site.py
git diff --check
```

The build fails on missing Markdown targets and anchors. The site checker also checks generated HTML links/assets, unique titles/descriptions, canonical URLs, social metadata, structured data, sitemap coverage, and the search index. Manually check mobile layout, keyboard navigation, dark mode, and video playback after design or media changes.

The pinned Material for MkDocs release announces maintenance through 5 May 2027. Review its maintained successor before that date; keep the generated-site checks when changing themes or generators. Dependabot tracks the Python tooling as well as Actions.

## GitHub Pages

The docs workflow builds and checks pull requests. Deployment runs only from `main`, and only after the build succeeds. In repository **Settings → Pages**, select **GitHub Actions** as the source. The configured project URL is `https://debanshu777.github.io/CaraML/`.

Do not advertise that URL as live until a deployment succeeds. Update `site_url` and the checker base together if moving to another host. The MkDocs output is static HTML in `build/site`, so it can also be served by another static host.

Pages handles the repository path prefix. The site supplies canonical URLs, unique descriptions, Open Graph/Twitter metadata, a social preview, a sitemap, and a local search index. A project-level `robots.txt` cannot control the domain root, so none is added here. Submit the sitemap through the site owner's search-engine tools if desired; metadata does not guarantee indexing or ranking.

## GitHub discovery

Keep the repository About text specific: local AI, Kotlin/Compose Multiplatform, target platforms, and supported engines. Use relevant topics such as `kotlin-multiplatform`, `compose-multiplatform`, `on-device-ai`, `local-ai`, `gguf`, `llama-cpp`, `onnx-runtime`, and `stable-diffusion`. Do not use `open-source` while the project has a noncommercial license.

Upload `docs/assets/social-preview.png` in **Settings → General → Social preview** for GitHub link cards. GitHub's repository preview is separate from the documentation site's Open Graph image. Set the About website to the docs URL only after deployment is verified.

## Demo and evidence

The cover uses an original blue-flower image processed with [Paper's Lens Distortion shader](https://shaders.paper.design/lens-distortion). Keep the masthead compact: an upright CaraML wordmark and a factual subtitle on the left, with the flower filling the right side. The docs open into standard project documentation with direct setup links. Use the blue palette and flower texture in the product cards and video. Flow remains the small blue wave avatar in `docs/assets/flow.svg`. This artwork applies to the README and docs; the app retains its current branding.

The flower was created with the built-in image-generation tool as marketing artwork, **not generated inside CaraML**. The source image, exact prompt, shader version, and exported frame live in `tools/docs/artwork/`. The image-generation demonstration in the video is a separate, real SD-Turbo app capture.

To change the lens effect, install Node.js and run:

```bash
npm ci --prefix tools/docs/artwork --ignore-scripts
python3 -m http.server 8766 --bind 127.0.0.1 --directory tools/docs/artwork
```

Open `http://127.0.0.1:8766` in a WebGL 2 browser. Adjust the uniforms in `tools/docs/artwork/render.js`, reload, and select **Export 1600 × 800 PNG**. Replace `tools/docs/artwork/blue-flower-lens.png` with that export. Paper Shaders is pinned to 0.0.82; its Apache-2.0 license and notices remain in the installed package. No third-party sample flower is used.

After installing `tools/docs/media-requirements.txt` and system Cairo, compose the cover and social image:

```bash
python tools/docs/render_readme.py
```

The renderer uses the bundled Bricolage Grotesque and DM Sans fonts as outlines and writes the 1600 × 560 `readme-banner.png`, its lighter WebP preview, `blue-flower-lens.webp`, and the 1200 × 630 `social-preview.png`. `grain_pattern()` adds reproducible fine grain beneath the lettering; adjust its opacity and scale to tune the texture without softening text. The intermediate SVG stays in ignored `build/artwork/`. The README loads WebP and links to the full PNG. Published pages use a static shader export: no WebGL runtime, animation, or remote image/font request is needed. Documentation text uses system fonts.

Regenerate the store-style product screenshots with:

```bash
python tools/docs/render_product_shots.py
```

The renderer composes SVG layouts around the original PNG captures, then exports 1080 × 1920 RGB PNGs and 540 × 960 WebP previews into `docs/assets/product/`. Editable layout code lives in `tools/docs/render_product_shots.py`; intermediate SVGs stay in ignored `build/product/`. CairoSVG needs a system Cairo library (`brew install cairo` on macOS or `sudo apt install libcairo2` on Debian/Ubuntu). If macOS cannot locate it, prefix the rendering command with `DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib"`. Review all four cards after editing copy or replacing captures; keep app pixels and feature claims faithful to the source screens.

Keep the [demo transcript and capture notes](demo.md) synchronized with the media. Use actual app captures and public sample prompts. Clearly identify edited pacing; do not use launch-video timing as a throughput claim. Keep large raw recordings and model weights out of Git.

The documentation organization takes inspiration from [FileKit's clear setup and documentation links](https://github.com/vinceglb/FileKit), [XCalendar's platform captures](https://github.com/Debanshu777/XCalendar), and [Brag's short product-video structure](https://github.com/latent-spaces/brag). CaraML's visual composition is defined above; keep future artwork consistent with it.
