#!/usr/bin/env python3
"""Convert legacy red NewPipe launcher icons to Flow Play charcoal.
Adaptive launcher background is defined separately in values/colors.xml.
Run before Gradle packages app resources.
"""
from pathlib import Path
from PIL import Image

files = sorted(Path("app/src/main/res").glob("mipmap-*/ic_launcher.png"))
if len(files) < 5:
    raise SystemExit(f"Missing legacy launcher variants: found {len(files)}")
for path in files:
    image = Image.open(path).convert("RGBA")
    pixels = image.load()
    recolored = 0
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a and r > 55 and r > max(g, b) + 12:
                # Preserve transparency and the white NewPipe play logo.
                pixels[x, y] = (36, 36, 36, a)
                recolored += 1
    if recolored == 0:
        raise SystemExit(f"No red icon pixels found: {path}")
    image.save(path, optimize=True)
    print(f"{path}: recolored {recolored} launcher pixels")
