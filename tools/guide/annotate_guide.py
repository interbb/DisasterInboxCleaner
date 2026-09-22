#!/usr/bin/env python3
"""Draw red highlight boxes and gray masks from boxes.json onto raw PNGs, downscale, save WebP."""
import json, os, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
# Raw captures hold unmasked personal data, so capture_guide.sh keeps them outside the project
# (see lib.sh) and exports GUIDE_RAW. Pass the same value here when re-running annotation alone.
RAW = os.environ.get("GUIDE_RAW") or os.path.join(HERE, "raw")
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")
MAX_LONG = 1080
BOX = (229, 57, 53)
NAMES = ["guide_01_software_info", "guide_02_build_number", "guide_03_dev_wireless_switch",
         "guide_04_wireless_allow", "guide_05_wireless_page", "guide_06_pairing_dialog",
         "guide_07_notification_input", "guide_08_toggle_off"]

def main():
    boxes = json.load(open(os.path.join(HERE, "boxes.json")))
    os.makedirs(OUT, exist_ok=True)
    for name in NAMES:
        src = os.path.join(RAW, name + ".png")
        if not os.path.exists(src):
            print("missing", name); continue
        im = Image.open(src).convert("RGB")
        draw = ImageDraw.Draw(im)
        entry = boxes.get(name, {})
        for l, t, r, b in entry.get("masks", []):
            draw.rectangle([l, t, r, b], fill=(200, 200, 200))
        for l, t, r, b in entry.get("boxes", []):
            pad = 12
            draw.rounded_rectangle([l - pad, t - pad, r + pad, b + pad], radius=12, outline=BOX, width=6)
        crop = entry.get("crop")
        if crop:  # keep only the relevant region (e.g. our notification, not the whole shade)
            im = im.crop(tuple(crop))
        scale = min(1.0, MAX_LONG / max(im.size))
        if scale < 1.0:
            im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
        dst = os.path.join(OUT, name + ".webp")
        im.save(dst, "WEBP", quality=80, method=6)
        print(name, im.size, os.path.getsize(dst) // 1024, "KB")

if __name__ == "__main__":
    main()
