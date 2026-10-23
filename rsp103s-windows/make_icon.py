"""Генерирует skyplug.ico (розетка на синем фоне). Запуск: python make_icon.py (нужен Pillow)."""
from PIL import Image, ImageDraw

S = 1024  # рисуем крупно и уменьшаем - получается гладко


def draw() -> Image.Image:
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * 0.22), fill="#1E88E5")
    c, r = S / 2, S * 0.36
    d.ellipse((c - r, c - r, c + r, c + r), fill="#FFFFFF")
    r2 = S * 0.28
    d.ellipse((c - r2, c - r2, c + r2, c + r2), fill="#E3F2FD")
    pin = S * 0.054
    for dx in (-S * 0.088, S * 0.088):
        d.ellipse((c + dx - pin, c - pin, c + dx + pin, c + pin), fill="#1565C0")
    w, h = S * 0.074, S * 0.056
    d.rectangle((c - w, c - r2 + S * 0.01, c + w, c - r2 + S * 0.01 + h * 1.4), fill="#1565C0")
    d.rectangle((c - w, c + r2 - S * 0.01 - h * 1.4, c + w, c + r2 - S * 0.01), fill="#1565C0")
    return im


if __name__ == "__main__":
    big = draw()
    big.resize((256, 256), Image.LANCZOS).save(
        "skyplug.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("skyplug.ico готов")
