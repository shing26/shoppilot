"""把 19 个 ticket 的 Handoff notes 里的三个追问汇总成面试问答清单。

清单是生成的，不是手抄的：手抄会在复制过程中悄悄改掉措辞，
而面试项目里最重要的就是我自己说的话和当时记的话是同一份。

用法: python scripts/collect_interview_questions.py [输出文件]
"""
import re
import sys
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ISSUES = REPO / ".scratch" / "shoppilot-mvp" / "issues"

# 两种记录格式：早期 ticket 用 - *Q：…* A：…*，后续 ticket 用 1. "…" —— …
FORMS = (
    re.compile(r"^-\s+\*Q[：:](.+?)\*\s+A[：:](.+)$"),
    re.compile(r"^\d+\.\s+[「\"](.+?)[」\"]\s+——\s+(.+)$"),
)


def parse(path):
    text = path.read_text(encoding="utf-8")
    marker = "## Handoff notes"
    index = text.find(marker)
    if index < 0:
        return []
    pairs = []
    for line in text[index:].splitlines():
        line = line.strip()
        for form in FORMS:
            matched = form.match(line)
            if matched:
                pairs.append((matched.group(1).strip(), matched.group(2).strip()))
                break
    return pairs


def main():
    raw = sys.argv[1] if len(sys.argv) > 1 else str(REPO / "docs" / "interview-qa.md")
    out = Path(raw).resolve()
    files = sorted(ISSUES.glob("*.md"))
    lines = [
        "# 面试问答清单（由各 ticket 的 Handoff notes 生成）",
        "",
        "生成方式：`python scripts/collect_interview_questions.py`。",
        "每题答案直接取自当时写下的收尾记录，不做事后润色——答不上来的就是当时没想清楚的。",
        "",
        "用法：每条先只看问题，自己答 30 秒，再对答案。答不出细节的题回去读对应 ticket。",
        "",
    ]
    total = 0
    missing = []
    for path in files:
        pairs = parse(path)
        ticket = path.name.split("-")[0]
        title = path.name[len(ticket) + 1:].replace(".md", "").replace("-", " ")
        if not pairs:
            missing.append(ticket)
            continue
        total += len(pairs)
        lines.append("")
        lines.append("## Ticket " + ticket + " — " + title)
        lines.append("")
        for question, answer in pairs:
            lines.append("**Q：" + question + "**")
            lines.append("")
            lines.append("A：" + answer)
            lines.append("")
    lines.insert(5, "")
    lines.insert(5, "共 " + str(total) + " 问，覆盖 " + str(len(files) - len(missing)) + " 个 ticket。")
    if missing:
        lines.append("")
        lines.append("## 尚未记录追问的 ticket")
        lines.append("")
        lines.append("、".join(missing) + "（收尾时补 `## Handoff notes` 后重跑本脚本）")
    # `docs/interview-qa.md` 在 verify_eval_judge 的换行基线里是 CRLF，生成时必须显式守住。
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\r\n")
    shown = out.relative_to(REPO) if out.is_relative_to(REPO) else out
    print(f"写出 {shown}：{total} 问，覆盖 {len(files) - len(missing)}/{len(files)} 个 ticket")
    if missing:
        print("缺收尾记录的 ticket：" + "、".join(missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
