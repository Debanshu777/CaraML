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

Flow is CaraML's blue wave avatar. Its vector source is `docs/assets/flow.svg`; the README banner uses the bundled Bricolage Grotesque and DM Sans fonts. After installing `tools/docs/media-requirements.txt`, run `python tools/docs/render_readme.py` to regenerate the banner. Keep the README focused on the product, first run, and links into these guides.

Regenerate the store-style product screenshots with:

```bash
python tools/docs/render_product_shots.py
```

The renderer composes SVG layouts around the original PNG captures, then exports 1080 × 1920 RGB PNGs and 540 × 960 WebP previews into `docs/assets/product/`. Editable layout code lives in `tools/docs/render_product_shots.py`; intermediate SVGs stay in ignored `build/product/`. CairoSVG needs a system Cairo library (`brew install cairo` on macOS or `sudo apt install libcairo2` on Debian/Ubuntu). If macOS cannot locate it, prefix the rendering command with `DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib"`. Review all four cards after editing copy or replacing captures; keep app pixels and feature claims faithful to the source screens.

Keep the [demo transcript and capture notes](demo.md) synchronized with the media. Use actual app captures and public sample prompts. Clearly identify edited pacing; do not use launch-video timing as a throughput claim. Keep large raw recordings and model weights out of Git.

The presentation takes inspiration from [FileKit's quickstart and documentation links](https://github.com/vinceglb/FileKit), [XCalendar's platform captures](https://github.com/Debanshu777/XCalendar), and [Brag's short product-video structure](https://github.com/latent-spaces/brag). The implementation and assets remain specific to CaraML.
