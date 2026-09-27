# -*- coding: utf-8 -*-
"""活体报告的 provenance 表头（ticket 57）。

活体报告是一次性产物：没有 commit 与语料指纹，就无法回答"这份读数还对应当前代码与知识库吗"。
本模块只做两件确定性的事——算当前 commit、算 `knowledge/` 下全部 md 的语料指纹。
其余维度（模型档位、知识纪元）只有连得到网关的脚本才拿得到，因此由调用方传入；拿不到就写 `-`，不猜。
"""
import hashlib
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KNOWLEDGE = REPO / "knowledge"


def commit_sha() -> str:
    """当前 HEAD 的短 sha。不在 git 工作树里时返回 `unknown`，不抛异常。"""
    try:
        done = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, timeout=10)
        return done.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def corpus_sha256() -> str:
    """`knowledge/` 下全部 `*.md` 按文件名排序、逐个拼进摘要。语料一改这个值就变。"""
    digest = hashlib.sha256()
    for path in sorted(KNOWLEDGE.glob("*.md")):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def line(commit: str = None, corpus: str = None, kb_epoch=None, llm_mode: str = None) -> str:
    """一行 provenance。拿不到的维度写 `-`；`kb_epoch` 允许为 0，故用 `is not None` 判。"""
    parts = [f"生成：`{commit or commit_sha()}`",
             f"语料 sha256=`{corpus or corpus_sha256()}`",
             f"档位=`{llm_mode or '-'}`",
             f"kb_epoch=`{'-' if kb_epoch is None else kb_epoch}`"]
    return "｜".join(parts) + "。"
