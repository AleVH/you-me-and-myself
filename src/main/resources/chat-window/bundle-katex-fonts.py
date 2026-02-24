#!/usr/bin/env python3
"""
Bundle KaTeX fonts into CSS as base64 data URIs.

Usage:
    cd src/main/resources/chat-window/
    python bundle-katex-fonts.py

Reads:  katex.min.css (must exist in current directory)
Output: katex-bundled.css (self-contained, no external font references)

Requirements: Python 3.7+, internet access (downloads fonts from cdnjs on first run)

The script:
1. Reads katex.min.css
2. Finds all url(fonts/...) references
3. Downloads each font file (if not already in fonts/ subdirectory)
4. Base64-encodes each font
5. Replaces the URL with a data: URI
6. Writes katex-bundled.css

After running, you can delete katex.min.css and the fonts/ directory.
Use katex-bundled.css in your project instead.
"""

import re
import os
import base64
import urllib.request

KATEX_VERSION = "0.16.9"
CDN_BASE = f"https://cdnjs.cloudflare.com/ajax/libs/KaTeX/{KATEX_VERSION}/"

INPUT_FILE = "katex.min.css"
OUTPUT_FILE = "katex-bundled.css"
FONTS_DIR = "fonts"


def download_font(relative_path: str) -> bytes:
    """Download a font file from CDN, or read from local cache."""
    local_path = relative_path
    if os.path.exists(local_path):
        print(f"  [cached] {relative_path}")
        with open(local_path, "rb") as f:
            return f.read()

    # Create fonts directory if needed
    os.makedirs(os.path.dirname(local_path), exist_ok=True)

    url = CDN_BASE + relative_path
    print(f"  [download] {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "YMM-KaTeX-Bundler/1.0"})
    with urllib.request.urlopen(req) as resp:
        data = resp.read()

    # Cache locally
    with open(local_path, "wb") as f:
        f.write(data)

    return data


def get_mime_type(path: str) -> str:
    """Determine MIME type from file extension."""
    ext = os.path.splitext(path)[1].lower()
    return {
        ".woff2": "font/woff2",
        ".woff": "font/woff",
        ".ttf": "font/ttf",
        ".eot": "application/vnd.ms-fontembedded-opentype",
    }.get(ext, "application/octet-stream")


def bundle():
    if not os.path.exists(INPUT_FILE):
        print(f"ERROR: {INPUT_FILE} not found in current directory.")
        print(f"Make sure you're running this from src/main/resources/chat-window/")
        return

    print(f"Reading {INPUT_FILE}...")
    with open(INPUT_FILE, "r", encoding="utf-8") as f:
        css = f.read()

    # Find all url() references
    # Matches: url(fonts/KaTeX_Main-Regular.woff2)
    # Also matches: url("fonts/...") and url('fonts/...')
    url_pattern = re.compile(r'url\(["\']?(fonts/[^)"\']+)["\']?\)')
    matches = url_pattern.findall(css)
    unique_fonts = sorted(set(matches))

    print(f"Found {len(unique_fonts)} unique font references.")

    # Download and encode each font
    replacements = {}
    total_size = 0
    for font_path in unique_fonts:
        try:
            font_data = download_font(font_path)
            total_size += len(font_data)
            mime = get_mime_type(font_path)
            b64 = base64.b64encode(font_data).decode("ascii")
            data_uri = f"data:{mime};base64,{b64}"
            replacements[font_path] = data_uri
        except Exception as e:
            print(f"  [ERROR] Failed to process {font_path}: {e}")
            # Leave the original URL in place
            continue

    # Apply replacements
    bundled_css = css
    for font_path, data_uri in replacements.items():
        # Replace all variants: url(fonts/...), url("fonts/..."), url('fonts/...')
        bundled_css = bundled_css.replace(f'url({font_path})', f'url({data_uri})')
        bundled_css = bundled_css.replace(f'url("{font_path}")', f'url("{data_uri}")')
        bundled_css = bundled_css.replace(f"url('{font_path}')", f"url('{data_uri}')")

    # Write output
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write(bundled_css)

    input_size = os.path.getsize(INPUT_FILE)
    output_size = os.path.getsize(OUTPUT_FILE)
    print(f"\nDone!")
    print(f"  Input:  {INPUT_FILE} ({input_size:,} bytes)")
    print(f"  Fonts:  {len(replacements)} encoded ({total_size:,} bytes raw)")
    print(f"  Output: {OUTPUT_FILE} ({output_size:,} bytes)")
    print(f"\nYou can now use {OUTPUT_FILE} instead of {INPUT_FILE}.")
    print(f"Delete {INPUT_FILE} and the fonts/ directory if you don't need them.")


if __name__ == "__main__":
    bundle()