#!/usr/bin/env python3
"""把 capture-polarity-guard.mjs 抓的两栏帧合成 GIF（极性守卫现场演示）。

两栏对应同一段时间：左栏调试台（http://127.0.0.1:8082/）的真实操作与真实事件时间线，
右栏网关日志文件里的原始行 + /actuator 计数器前后读数。帧全部来自浏览器截图与日志文件，
本脚本只做排版，不生成任何画面内容。

用法:
    python scripts/compose-polarity-gif.py [--scale 1.0] [--out docs/polarity-guard-demo.gif]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CONSOLE_W, LOG_W, PANEL_H = 900, 560, 940
PAD = 10
HEADER_H = 54
CAPTION_H = 36
FOOT_H = 30
COLUMNS = [CONSOLE_W, LOG_W]
LABELS = ["调试台 / · 事件时间线", "网关日志 logs/gateway.log · 原始行"]

TIMELINE = [
    (["console-ready", "log-idle"],
     "① 调试台就绪：买家身份已签发，事件时间线还是空白", 1.4),
    (["console-source", "log-source"],
     "② 源问法「这个能退吗」：L2 未命中，走检索 + 模型，答案写回缓存", 2.0),
    (["console-same", "log-same"],
     "③ 同极性近义「这个能退么」（余弦 0.9980）：命中 L2——这是语义缓存该做的事", 2.2),
    (["console-anti", "log-anti"],
     "④ 反义问法「这个不能退吗」（余弦 0.9799）：同样越过 0.95 阈值，却在复用前被拒", 2.4),
    (["console-anti", "log-guard"],
     "⑤ 网关日志原始行：L2 语义命中被极性守卫拒绝；计数器 +1", 3.2),
]

FOOT = "系统没有「Antonym polarity detected」这行文案；上面是它真实打印的那一行。"

BG = (13, 17, 32)
HEADER_BG = (19, 26, 48)
CAPTION_BG = (24, 33, 61)
INK = (214, 226, 255)
DIM = (95, 114, 156)

FONT_DIR = Path("C:/Windows/Fonts")
FONT = FONT_DIR / "msyh.ttc"
FONT_BOLD = FONT_DIR / "msyhbd.ttc"


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = FONT_BOLD if bold else FONT
    if not path.exists():
        sys.exit(f"缺少字体 {path}（本脚本需要中文字体渲染标题）")
    return ImageFont.truetype(str(path), size)


def compose_frame(panels: list[Image.Image], caption: str, fonts) -> Image.Image:
    width = sum(COLUMNS) + PAD * (len(COLUMNS) + 1)
    height = HEADER_H + CAPTION_H + PANEL_H + FOOT_H + PAD * 2
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)

    draw.rectangle([0, 0, width, HEADER_H], fill=HEADER_BG)
    draw.text((PAD + 4, 16), "极性守卫：同桶反义问法为什么拿不到上一条答案", font=fonts["title"], fill=INK)
    x = PAD
    for label, column_w in zip(LABELS, COLUMNS):
        draw.rectangle([x, HEADER_H - 4, x + column_w, HEADER_H], fill=(36, 48, 86))
        draw.text((x + 8, HEADER_H + 6), label, font=fonts["label"], fill=DIM)
        x += column_w + PAD

    draw.rectangle([0, HEADER_H, width, HEADER_H + CAPTION_H], fill=CAPTION_BG)
    draw.text((PAD + 4, HEADER_H + 8), caption, font=fonts["caption"], fill=INK)

    x = PAD
    for column_w, panel in zip(COLUMNS, panels):
        if panel.size != (column_w, PANEL_H):
            panel = panel.resize((column_w, PANEL_H))
        canvas.paste(panel, (x, HEADER_H + CAPTION_H + PAD))
        x += column_w + PAD

    draw.rectangle([0, height - FOOT_H, width, height], fill=(16, 22, 41))
    draw.text((PAD + 4, height - FOOT_H + 6), FOOT, font=fonts["foot"], fill=DIM)
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames", default="logs/capture-polarity")
    parser.add_argument("--out", default="docs/polarity-guard-demo.gif")
    parser.add_argument("--scale", type=float, default=1.0)
    args = parser.parse_args()

    frame_dir = Path(args.frames)
    fonts = {
        "title": load_font(21, bold=True),
        "label": load_font(15),
        "caption": load_font(16, bold=True),
        "foot": load_font(14),
    }

    gif_frames = []
    durations = []
    for names, caption, seconds in TIMELINE:
        panels = []
        for name in names:
            # 采集脚本给文件名带步骤号（01-console-ready），这里按后缀找，
            # 免得 TIMELINE 里的短名和采集脚本的编号两处维护。
            matches = sorted(frame_dir.glob(f"frame-*{name}.png"))
            if not matches:
                sys.exit(f"缺帧 {frame_dir}/frame-*{name}.png——先跑 scripts/capture-polarity-guard.mjs")
            panels.append(Image.open(matches[-1]).convert("RGB"))
        canvas = compose_frame(panels, caption, fonts)
        if args.scale != 1.0:
            canvas = canvas.resize(
                (round(canvas.width * args.scale), round(canvas.height * args.scale))
            )
        gif_frames.append(canvas.quantize(colors=128))
        durations.append(round(seconds * 1000))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    gif_frames[0].save(
        out,
        save_all=True,
        append_images=gif_frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
    )
    size_kb = out.stat().st_size / 1024
    print(f"{out}  {len(gif_frames)} 帧  {gif_frames[0].width}x{gif_frames[0].height}  {size_kb:.0f} KB")


if __name__ == "__main__":
    main()
