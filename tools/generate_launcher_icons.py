"""Export PNG icons from the actual Android vector resources (Pillow + numpy).

The user-provided path data, translucent strokes, and rounded caps/joins are
retained. This deliberately small renderer supports only the vector features
used by this icon and rejects unsupported commands rather than silently drifting.
Android uses the XML vectors directly; these PNGs serve Android 7 and docs.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets/icon"
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SIZE = 1728  # 16x supersampling of the 108dp adaptive canvas.


def attr(element, name):
    return element.attrib[ANDROID + name]


def color(value):
    value = value.lstrip("#")
    if len(value) == 6:
        return tuple(bytes.fromhex(value)) + (255,)
    assert len(value) == 8
    a, r, g, b = bytes.fromhex(value)
    return r, g, b, a


def paths(data):
    """Sample absolute M/L/H/V/Q/T paths, including reflected quadratic controls."""
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)", data)
    index, point, control, stroke = 0, np.zeros(2), None, []
    while index < len(tokens):
        command = tokens[index]
        assert command in {"M", "L", "H", "V", "Q", "T"}, command
        count = {"M": 2, "L": 2, "H": 1, "V": 1, "Q": 4, "T": 2}[command]
        values = list(map(float, tokens[index + 1:index + 1 + count]))
        index += count + 1
        previous = point.copy()
        if command == "M":
            if stroke:
                yield stroke
            point = np.array(values)
            stroke = [point]
        elif command in {"Q", "T"}:
            middle = np.array(values[:2]) if command == "Q" else (
                previous if control is None else 2 * previous - control
            )
            point = np.array(values[-2:])
            for t in np.linspace(0, 1, 65)[1:]:
                stroke.append((1 - t) ** 2 * previous + 2 * (1 - t) * t * middle + t ** 2 * point)
            control = middle
        else:
            if command == "L":
                point = np.array(values)
            elif command == "H":
                point = np.array([values[0], previous[1]])
            else:
                point = np.array([previous[0], values[0]])
            stroke.append(point)
        if command not in {"Q", "T"}:
            control = None
    if stroke:
        yield stroke


def foreground(filename="ic_launcher_foreground.xml", include_shadow=True):
    vector = ET.parse(RES / "drawable" / filename).getroot()
    assert float(attr(vector, "viewportWidth")) == float(attr(vector, "viewportHeight")) == 108
    result = Image.new("RGBA", (SIZE, SIZE))

    def walk(parent, scale=1.0, offset=None):
        nonlocal result
        if offset is None:
            offset = np.zeros(2)
        for element in parent:
            if element.tag == "group":
                if not include_shadow and element.attrib.get(ANDROID + "name") == "arrow_shadow":
                    continue
                number = lambda key, default: float(element.attrib.get(ANDROID + key, default))
                assert number("rotation", 0) == 0
                local_scale = number("scaleX", 1)
                assert local_scale == number("scaleY", 1)
                pivot = np.array([number("pivotX", 0), number("pivotY", 0)])
                translation = np.array([number("translateX", 0), number("translateY", 0)])
                walk(element, scale * local_scale,
                     offset + scale * (pivot * (1 - local_scale) + translation))
                continue
            assert element.tag == "path", element.tag
            assert attr(element, "strokeLineCap") == attr(element, "strokeLineJoin") == "round"
            layer = Image.new("RGBA", result.size)
            draw = ImageDraw.Draw(layer)
            ink = color(attr(element, "strokeColor"))
            width = float(attr(element, "strokeWidth")) * scale * SIZE / 108
            radius = width / 2
            for stroke in paths(attr(element, "pathData")):
                points = [tuple((point * scale + offset) * SIZE / 108) for point in stroke]
                draw.line(points, fill=ink, width=round(width), joint="curve")
                for x, y in points:
                    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=ink)
            result = Image.alpha_composite(result, layer)

    walk(vector)
    return result


def background():
    vector = ET.parse(RES / "drawable/ic_launcher_background.xml").getroot()
    result = Image.new("RGBA", (SIZE, SIZE))
    y, x = np.mgrid[0:SIZE, 0:SIZE] * 108 / SIZE
    for path in vector.findall("path"):
        assert attr(path, "pathData") == "M0,0h108v108h-108z"
        gradient = path.find(".//gradient")
        kind = attr(gradient, "type")
        if kind == "linear":
            start = np.array([float(attr(gradient, "startX")), float(attr(gradient, "startY"))])
            end = np.array([float(attr(gradient, "endX")), float(attr(gradient, "endY"))])
            direction = end - start
            t = ((x - start[0]) * direction[0] + (y - start[1]) * direction[1]) / np.dot(direction, direction)
        else:
            assert kind == "radial", kind
            t = np.hypot(x - float(attr(gradient, "centerX")), y - float(attr(gradient, "centerY")))
            t /= float(attr(gradient, "gradientRadius"))
        stops = gradient.findall("item")
        offsets = [float(attr(stop, "offset")) for stop in stops]
        assert offsets[0] == 0 and offsets[-1] == 1 and offsets == sorted(set(offsets))
        colors = np.array([color(attr(stop, "color")) for stop in stops])
        pixels = np.stack([np.interp(t, offsets, colors[:, channel]) for channel in range(4)], axis=-1)
        result = Image.alpha_composite(result, Image.fromarray(pixels.round().astype("uint8")))
    return result


def png(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def visible_crop(image):
    size = image.width
    return image.crop((size // 6, size // 6, size * 5 // 6, size * 5 // 6))


def shape_mask(size, circular=False):
    mask = Image.new("L", (size, size))
    draw = ImageDraw.Draw(mask)
    if circular:
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=size * 29 / 128, fill=255)
    return mask


def main():
    front = foreground()
    combined = Image.alpha_composite(background(), front)
    visible = visible_crop(combined)
    geometry = foreground(include_shadow=False)
    mono = foreground("ic_launcher_monochrome.xml")
    for vector in (geometry, mono):
        alpha = visible_crop(vector).getchannel("A")
        assert vector.getchannel("A").getbbox() == (SIZE // 6 + alpha.getbbox()[0],
            SIZE // 6 + alpha.getbbox()[1], SIZE // 6 + alpha.getbbox()[2],
            SIZE // 6 + alpha.getbbox()[3]), "Visible adaptive crop cuts off the vector"
        for circular in (False, True):
            outside = np.asarray(shape_mask(visible.width, circular)) == 0
            assert not np.any((np.asarray(alpha) > 0) & outside), "Launcher mask clips the symbol"
        y, x = np.mgrid[0:SIZE, 0:SIZE] * 108 / SIZE
        assert not np.any((np.asarray(vector.getchannel("A")) > 0) &
                          (np.hypot(x - 54, y - 54) > 33)), "Symbol exceeds 66dp safe circle"
        bounds = vector.getchannel("A").getbbox()
        horizontal_center = (bounds[0] + bounds[2]) * 54 / SIZE
        assert abs(horizontal_center - 54) < .1, "Uneven horizontal silhouette margins"
    # Read the shared outer transform so optical offsets also apply to probes.
    color_group = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot().find("group")
    mono_group = ET.parse(RES / "drawable/ic_launcher_monochrome.xml").getroot().find("group")
    for key in ("pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY"):
        assert color_group.attrib.get(ANDROID + key) == mono_group.attrib.get(ANDROID + key), key
    # Verify the webpage-specific negative-space variant, not just a recolor.
    for point in ((35, 58.2), (35, 65.8), (65, 58.2), (65, 65.8), (49, 81)):
        mapped = tuple(int(((value - float(attr(color_group, "pivot" + axis))) *
            float(attr(color_group, "scale" + axis)) + float(attr(color_group, "pivot" + axis)) +
            float(color_group.attrib.get(ANDROID + "translate" + axis, 0))) * SIZE / 108)
            for value, axis in zip(point, ("X", "Y")))
        assert mono.getpixel(mapped)[3] == 0, f"Monochrome gap/wave mismatch at {point}"
        assert geometry.getpixel(mapped)[3] > 0, f"Color geometry unexpectedly missing at {point}"
    alpha = visible_crop(mono).getchannel("A")
    square, circle = visible.copy(), visible.copy()
    square.putalpha(shape_mask(visible.width))
    circle.putalpha(shape_mask(visible.width, True))
    for density, size in DENSITIES.items():
        for suffix, image in (("", square), ("_round", circle)):
            png(image.resize((size, size), Image.Resampling.LANCZOS),
                RES / f"mipmap-{density}" / f"ic_launcher{suffix}.png")
    png(square.resize((512, 512), Image.Resampling.LANCZOS), ROOT / "screenshots/ic_launcher.png")
    png(visible.resize((1024, 1024), Image.Resampling.LANCZOS), ASSETS / "share-relay-icon.png")
    preview = Image.new("RGB", (960, 420), "#edf2f8")
    theme = Image.new("RGBA", visible.size, "#e2ecfc")
    ink = Image.new("RGBA", visible.size, "#1a56db")
    ink.putalpha(alpha)
    theme = Image.alpha_composite(theme, ink)
    theme.putalpha(shape_mask(theme.width, True))
    draw = ImageDraw.Draw(preview)
    for index, (image, label) in enumerate(((square, "Squircle"), (circle, "Circle"), (theme, "Android themed"))):
        icon = image.resize((260, 260), Image.Resampling.LANCZOS)
        preview.paste(icon, (30 + index * 320, 55), icon)
        draw.text((30 + index * 320, 340), label, fill="#18344a")
    png(preview, ASSETS / "launcher-preview.png")
    print("Exported 10 legacy icons, README icon, and preview from the Android vector resources.")
    print("Verified: no artwork is clipped by the adaptive crop, squircle, or circle mask.")


if __name__ == "__main__":
    main()

