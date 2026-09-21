"""Generate launcher icons from tools/icon_source.png.

    python3 tools/gen_icon.py

Writes the legacy mipmap PNGs at every density, plus an adaptive-icon
foreground for Android 8+. Android shrinks a legacy icon into a system mask,
so the glyph is inset rather than run to the edges.
"""

import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, 'app/src/main/res')

BACKGROUND = (255, 255, 255, 255)

# Legacy launcher icon sizes, in px, by density bucket.
DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

# An adaptive icon's foreground is 108dp, of which only the middle 72dp is
# guaranteed visible under any mask, so the glyph gets 60% of the canvas.
ADAPTIVE = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
ADAPTIVE_GLYPH_FRACTION = 0.60
LEGACY_GLYPH_FRACTION = 0.78


def glyph():
    """The artwork, cropped to its ink and with the white page dropped."""
    image = Image.open(os.path.join(HERE, 'icon_source.png')).convert('RGBA')
    pixels = image.load()
    width, height = image.size
    for x in range(width):
        for y in range(height):
            r, g, b, _ = pixels[x, y]
            # Near-white is the page, not the drawing.
            pixels[x, y] = (r, g, b, 0 if r > 240 and g > 240 and b > 240 else 255)
    return image.crop(image.getchannel('A').getbbox())


def square(art, size, fraction, background):
    canvas = Image.new('RGBA', (size, size), background)
    limit = int(size * fraction)
    scaled = art.copy()
    scaled.thumbnail((limit, limit), Image.LANCZOS)
    canvas.paste(scaled,
                 ((size - scaled.width) // 2, (size - scaled.height) // 2),
                 scaled)
    return canvas


def main():
    art = glyph()
    print('artwork cropped to %dx%d' % art.size)

    for density, size in DENSITIES.items():
        folder = os.path.join(RES, 'mipmap-' + density)
        os.makedirs(folder, exist_ok=True)
        icon = square(art, size, LEGACY_GLYPH_FRACTION, BACKGROUND)
        icon.save(os.path.join(folder, 'ic_launcher.png'))
        icon.save(os.path.join(folder, 'ic_launcher_round.png'))
        print('  mipmap-%-8s %dx%d' % (density, size, size))

    for density, size in ADAPTIVE.items():
        folder = os.path.join(RES, 'mipmap-' + density)
        os.makedirs(folder, exist_ok=True)
        # Transparent: the adaptive icon's own background layer sits behind it.
        square(art, size, ADAPTIVE_GLYPH_FRACTION, (0, 0, 0, 0)).save(
            os.path.join(folder, 'ic_launcher_foreground.png'))
        print('  foreground %-8s %dx%d' % (density, size, size))


if __name__ == '__main__':
    main()
