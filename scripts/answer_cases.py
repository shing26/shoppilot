#!/usr/bin/env python3
"""答案级评测的共享层（round48 票 202 / ADR 0083）。

三件事只在这里实现一次，录制器、门禁与案例生成器都从这里拿：

1. **请求怎么拼**——政策题按生产 `composeUserMessage()` 的形状拼 `【政策条款】…【买家问题】`，
   条款正文与 ruleId 直接按 `MarkdownChunker` 的规则从 `knowledge/*.md` 现算；动作题拼
   `system → user → assistant(tool_call) → tool(回执)`，工具 schema 取自已入仓的 201 动作卡。
2. **key 怎么算**——与 Java `CassetteKey` 逐字节一致（对象键排序、数组保序、紧凑 JSON、
   temperature 用回落后的值）。与已入仓的 6 张 Java 卡做过互算校验。
3. **事实怎么判**——见 `answer_facts.py`。

边界：这里不检索、不连模型、不执行工具。政策题给的是**固定条款**，动作题给的是**固定回执**；
检索排序与工具真实执行不在本票范围内。
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES_PATH = REPO / "eval" / "answer-cases.jsonl"
CASSETTE_DIR = REPO / "eval" / "answer-cassettes"
KNOWLEDGE_DIR = REPO / "knowledge"
PROMPT_DIR = REPO / "shoppilot-gateway" / "src" / "main" / "resources" / "prompts" / "agent-system"
TOOL_DESCRIPTORS = REPO / "eval" / "answer-tool-descriptors.json"

MAX_CLAUSE_CHARS = 600
MIN_CHUNK_CHARS = 120
DEFAULT_MODEL = "qwen-plus"
MAX_TOKENS = 1024
TEMPERATURE = 0.2

MODEL_SEGMENT = re.compile(r"[^A-Za-z0-9._-]")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical(value) -> str:
    """与 Java CassetteKey.canonical 相同的文本形态（键排序、紧凑、非 ASCII 不转义）。"""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            json.dumps(key, ensure_ascii=False) + ":" + canonical(value[key]) for key in sorted(value)
        ) + "}"
    raise TypeError(f"无法规范化：{type(value).__name__}")


def cassette_key(model: str, prompt_version: str, request: dict,
                 fallback_temperature: float = TEMPERATURE) -> str:
    temperature = request["temperature"] if request["temperature"] > 0 else fallback_temperature
    root = {"model": model, "promptVersion": prompt_version,
            "maxTokens": request["maxTokens"], "temperature": temperature,
            "messages": request["messages"], "tools": request["tools"]}
    return sha256(canonical(root))


def model_segment(model: str) -> str:
    if not model or not model.strip():
        return "unknown"
    return MODEL_SEGMENT.sub("-", model)


def cassette_path(root: Path, model: str, prompt_version: str, key: str) -> Path:
    return root / model_segment(model) / prompt_version / f"{key}.json"


def load_cases(path: Path = CASES_PATH) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def prompt_version() -> str:
    meta = json.loads((PROMPT_DIR / "meta.json").read_text(encoding="utf-8"))
    return str(meta["current"])


def prompt_text(version: str | None = None) -> str:
    version = version or prompt_version()
    return (PROMPT_DIR / f"{version}.md").read_text(encoding="utf-8")


def load_tool_descriptors() -> list[dict]:
    return json.loads(TOOL_DESCRIPTORS.read_text(encoding="utf-8"))


def parse_front_matter(raw: str) -> tuple[dict, str]:
    lines = raw.split("\n")
    if not lines or lines[0].strip() != "---":
        return {}, raw
    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            meta = {}
            for line in lines[1:index]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    meta[key.strip()] = value.strip()
            return meta, "\n".join(lines[index + 1:])
    return {}, raw


def split_sections(body: str) -> list[tuple[str | None, str]]:
    """与 MarkdownChunker.splitSections 同规则：按二级标题切、一级标题不进正文。"""
    sections: list[tuple[str | None, str]] = []
    heading = None
    buffer: list[str] = []
    for line in body.split("\n"):
        trimmed = line.strip()
        if trimmed.startswith("## "):
            if heading is not None or "".join(buffer).strip():
                sections.append((heading, "\n".join(buffer)))
            heading = trimmed[3:].strip()
            buffer = []
        elif not trimmed.startswith("# "):
            buffer.append(line)
    if heading is not None or "".join(buffer).strip():
        sections.append((heading, "\n".join(buffer)))
    return sections


def clause(doc_id: str, section: str) -> dict:
    """按知识库原文复算一个规则块：scope / ruleId / 正文，与入库切分保持一致。"""
    raw = (KNOWLEDGE_DIR / f"{doc_id}.md").read_text(encoding="utf-8")
    meta, body = parse_front_matter(raw)
    title = meta.get("title", doc_id)
    for heading, text in split_sections(body):
        if heading == section:
            section_text = text.strip()
            if len(section_text) < MIN_CHUNK_CHARS:
                raise ValueError(f"{doc_id} § {section} 短于 {MIN_CHUNK_CHARS} 字，切分会并入上一块")
            return {
                "doc": doc_id,
                "section": section,
                "scope": meta.get("scope", "PLATFORM"),
                "ruleId": sha256(f"{doc_id}|{title} > {section}"),
                "text": section_text,
                "title": title,
            }
    raise KeyError(f"{doc_id} 没有二级标题「{section}」")


def clamp_clause(text: str) -> str:
    return text[:MAX_CLAUSE_CHARS] + "..." if len(text) > MAX_CLAUSE_CHARS else text


def policy_prompt(case: dict) -> str:
    lines = ["【政策条款】"]
    for index, ref in enumerate(case["clauses"], start=1):
        chunk = clause(ref["doc"], ref["section"])
        scope = "本店" if chunk["scope"].upper() == "SHOP" else "平台"
        lines.append(f"{index}. [{scope}] [{chunk['ruleId']}] {clamp_clause(chunk['text'])}")
    return "\n".join(lines) + "\n\n【买家问题】\n" + case["query"]


def message(role: str, content: str | None, tool_call_id: str | None = None,
            tool_calls: list[dict] | None = None) -> dict:
    return {"role": role, "content": content, "toolCallId": tool_call_id,
            "toolCalls": tool_calls or []}


def build_request(case: dict, system_prompt: str) -> dict:
    messages = [message("system", system_prompt)]
    if case["kind"] == "policy":
        messages.append(message("user", policy_prompt(case)))
        tools: list[dict] = []
    elif case["kind"] == "action":
        call = case["tool"]
        messages.append(message("user", case["query"]))
        messages.append(message("assistant", None, tool_calls=[{
            "id": call["id"], "name": call["name"], "arguments": call["arguments"]}]))
        result = json.dumps(case["toolResult"], ensure_ascii=False, separators=(",", ":"))
        messages.append(message("tool", result, tool_call_id=call["id"]))
        tools = load_tool_descriptors()
    else:
        raise ValueError(f"{case['id']}: 未知 kind {case['kind']!r}")
    return {"messages": messages, "tools": tools,
            "temperature": TEMPERATURE, "maxTokens": MAX_TOKENS}


def request_summary(case: dict) -> str:
    if case["kind"] == "policy":
        return "政策：" + "；".join(f"{ref['doc']} § {ref['section']}" for ref in case["clauses"])
    return f"动作：{case['tool']['name']}({json.dumps(case['tool']['arguments'], ensure_ascii=False)})"
