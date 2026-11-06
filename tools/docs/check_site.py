#!/usr/bin/env python3
"""Check generated docs for broken local links and basic search/social metadata."""

from html.parser import HTMLParser
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urljoin, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "build/site"
BASE = "https://debanshu777.github.io/CaraML/"


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.meta = {}
        self.canonical = None
        self.title = ""
        self.in_title = False
        self.in_schema = False
        self.schemas = []
        self.feed(path.read_text(encoding="utf-8"))

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get("id"):
            self.ids.add(attrs["id"])
        for key in ("href", "src", "poster"):
            if attrs.get(key):
                self.links.append(attrs[key])
        if tag == "meta":
            self.meta[attrs.get("name", attrs.get("property", ""))] = attrs.get("content", "")
        if tag == "link" and attrs.get("rel") == "canonical":
            self.canonical = attrs.get("href")
        self.in_title |= tag == "title"
        if tag == "script" and attrs.get("type") == "application/ld+json":
            self.in_schema = True
            self.schemas.append("")

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        if tag == "script":
            self.in_schema = False

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        if self.in_schema:
            self.schemas[-1] += data


def main():
    if not (SITE / "index.html").is_file():
        sys.exit("Build first: python -m mkdocs build --strict")
    pages = {path: Page(path) for path in SITE.rglob("*.html")}
    errors = []
    titles = set()
    descriptions = set()
    for path, page in pages.items():
        rel = path.relative_to(SITE).as_posix()
        url = BASE + (rel[:-10] if rel.endswith("index.html") else rel)
        if rel != "404.html":
            for field in ("description", "og:title", "og:description", "og:image", "twitter:card"):
                if not page.meta.get(field):
                    errors.append(f"{rel}: missing {field}")
            if page.canonical != url:
                errors.append(f"{rel}: expected canonical {url}, got {page.canonical}")
            if not page.title or page.title in titles:
                errors.append(f"{rel}: missing or duplicate title")
            if page.meta.get("description") in descriptions:
                errors.append(f"{rel}: duplicate description")
            titles.add(page.title)
            descriptions.add(page.meta.get("description"))
        for link in page.links + [page.meta.get("og:image", "")]:
            target = urlsplit(urljoin(url, link))
            if target.scheme not in ("http", "https") or target.netloc != urlsplit(BASE).netloc:
                continue
            if not target.path.startswith("/CaraML/"):
                errors.append(f"{rel}: link escapes project base: {link}")
                continue
            local = SITE / unquote(target.path.removeprefix("/CaraML/"))
            if local.is_dir():
                local /= "index.html"
            if not local.is_file():
                errors.append(f"{rel}: missing local target {link}")
            elif target.fragment and local in pages and unquote(target.fragment) not in pages[local].ids:
                errors.append(f"{rel}: missing anchor {link}")
    schema = [json.loads(s) for s in pages[SITE / "index.html"].schemas]
    if not any(s.get("@type") == "SoftwareSourceCode" for s in schema):
        errors.append("Homepage is missing SoftwareSourceCode structured data")
    sitemap = SITE / "sitemap.xml"
    if not sitemap.is_file():
        errors.append("Missing sitemap.xml")
    else:
        urls = {e.text for e in ET.parse(sitemap).iter() if e.tag.endswith("}loc")}
        expected = {page.canonical for path, page in pages.items() if path.name != "404.html"}
        if urls != expected:
            errors.append(f"Sitemap/canonical mismatch: {urls ^ expected}")
    if not (SITE / "search/search_index.json").is_file():
        errors.append("Missing documentation search index")
    if errors:
        sys.exit("\n".join(sorted(set(errors))))
    print(f"Checked {len(pages)} HTML pages: local links, anchors, unique metadata, schema, sitemap, and search OK.")


if __name__ == "__main__":
    main()
