"""Карточка репозитория 1280×640 (social preview) в стиле карточки stm32-gdbtest.

Запуск: python3 art/make_badge.py <папка со шрифтами JetBrains Mono в TTF>
Шрифт: JetBrains Mono (OFL 1.1), файлы JBM-Regular.ttf, JBM-Medium.ttf, JBM-Bold.ttf.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = sys.argv[1] if len(sys.argv) > 1 else HERE

BG = (14, 26, 43)
BLUE = (74, 168, 255)
WHITE = (230, 237, 243)
GREY = (159, 179, 200)
DIM = (93, 117, 144)
ORANGE = (242, 165, 65)
CHIP_FILL = (19, 36, 58)
CHIP_LINE = (46, 74, 107)
FADE_FROM, FADE_TO = 535, 640

TAG = "MCU CI/CD & HIL"
TITLE = "hwlivetest-android"
LINES = [
    "Работающая прошивка на плате —",
    "прямо с телефона через ST-Link.",
    "Без ПК, OpenOCD и GDB.",
]
CHIPS = ["ST-Link", "SWD", "OTG", "MCAP", "Kotlin", "HIL"]
LINKS = [("github.com/ViacheslavMezentsev", DIM), ("t.me/mcu_cicd_hil", BLUE)]


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, f"JBM-{name}.ttf"), size)


def main():
    img = Image.new("RGB", (1280, 640), BG)
    art = Image.open(os.path.join(HERE, "icon-1024.png")).convert("RGB").resize((638, 638), Image.LANCZOS)
    img.paste(art, (2, 1))
    # Правый край картинки плавно уходит в фон (как у карточки stm32-gdbtest): линейное затухание
    # от FADE_FROM до FADE_TO по горизонтали, иначе граница с текстом слишком резкая.
    fade = Image.new("L", (1280, 640), 255)
    fd = ImageDraw.Draw(fade)
    for xx in range(FADE_FROM, 1280):
        k = max(0.0, 1.0 - (xx - FADE_FROM) / (FADE_TO - FADE_FROM))
        fd.line((xx, 0, xx, 639), fill=int(255 * k))
    img = Image.composite(img, Image.new("RGB", (1280, 640), BG), fade)
    d = ImageDraw.Draw(img)
    x = 640
    d.text((x, 141), TAG, font=font("Medium", 28), fill=BLUE, anchor="ls")
    d.text((x, 213), TITLE, font=font("Bold", 56), fill=WHITE, anchor="ls")
    d.rectangle((x, 238, x + 90, 244), fill=ORANGE)
    body = font("Regular", 26)
    for i, line in enumerate(LINES):
        d.text((x, 300 + 40 * i), line, font=body, fill=GREY, anchor="ls")
    cf = font("Regular", 22)
    cx = x
    for c in CHIPS:
        w = d.textlength(c, font=cf)
        d.rounded_rectangle((cx, 410, cx + w + 28, 448), radius=19, fill=CHIP_FILL, outline=CHIP_LINE, width=2)
        d.text((cx + 14, 429), c, font=cf, fill=WHITE, anchor="lm")
        cx += w + 28 + 10
    lf = font("Regular", 22)
    for i, (t, col) in enumerate(LINKS):
        d.text((x, 524 + 34 * i), t, font=lf, fill=col, anchor="ls")
    out = os.path.join(HERE, "social-preview.png")
    img.save(out, optimize=True)
    print(out, "chips end at", int(cx))


if __name__ == "__main__":
    main()
