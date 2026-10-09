#!/usr/bin/env python3
"""把 capture-refund-hitl.mjs 抓的三栏帧合成 GIF（退款涉资动作人机协同演示）。

三栏对应同一次真实请求：买家端（/buyer/）→ 网关 SSE 事件流（同一笔请求的真实
响应帧）→ 客服工作台（/workspace/）。帧全部来自 scripts/capture-refund-hitl.mjs
的浏览器截图，本脚本只做排版，不生成任何画面内容。

用法:
    python scripts/compose-refund-gif.py [--scale 1.0] [--out docs/refund-hitl-demo.gif]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

BUYER_W, LOG_W, SEAT_W, PANEL_H = 500, 400, 520, 720
PAD = 10
HEADER_H = 54
CAPTION_H = 36
COLUMNS = [BUYER_W, LOG_W, SEAT_W]
LABELS = ["买家端 /buyer/", "网关事件流 SSE", "客服工作台 /workspace/"]

TIMELINE = [
    (["login-buyer", "log-waiting", "login-seat"],
     "① 买家登录买家中心，坐席登录工作台（各自账号登录）", 1.6),
    (["buyer-ready", "log-waiting", "seat-empty-queue"],
     "② 两端就绪：此刻工作台没有待审退款", 1.4),
    (["typing", "log-waiting", "seat-empty-queue"],
     "③ 买家输入退款请求（带订单号与原因）", 1.4),
    (["buyer-answer", "log-full", "seat-empty-queue"],
     "④ 状态机拦截模型输出的 applyRefund → PENDING_APPROVAL，买家被告知等待人工审核", 2.4),
    (["buyer-answer", "log-full", "seat-pending"],
     "⑤ 工作台重载后拉到待审退款：订单号与金额都在", 1.8),
    (["buyer-answer", "log-full", "seat-approved"],
     "⑥ 坐席一键放行：资金动作由已验签账号审批，审计留痕（ADR 0063）", 2.8),
]

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
    height = HEADER_H + CAPTION_H + PANEL_H + PAD * 2
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)

    draw.rectangle([0, 0, width, HEADER_H], fill=HEADER_BG)
    draw.text((PAD + 4, 16), "退款涉资动作的人机协同阻断", font=fonts["title"], fill=INK)
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
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames", default="logs/capture-refund")
    parser.add_argument("--out", default="docs/refund-hitl-demo.gif")
    parser.add_argument("--scale", type=float, default=1.0)
    args = parser.parse_args()

    frame_dir = Path(args.frames)
    fonts = {
        "title": load_font(21, bold=True),
        "label": load_font(15),
        "caption": load_font(16, bold=True),
    }

    gif_frames = []
    durations = []
    for names, caption, seconds in TIMELINE:
        panels = []
        for name in names:
            # 采集脚本给文件名带步骤号（01-login-buyer），这里按后缀找，
            # 免得 TIMELINE 里的短名和采集脚本的编号两处维护。
            matches = sorted(frame_dir.glob(f"frame-*{name}.png"))
            if not matches:
                sys.exit(f"缺帧 {frame_dir}/frame-*{name}.png——先跑 scripts/capture-refund-hitl.mjs")
            path = matches[-1]
            panels.append(Image.open(path).convert("RGB"))
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
