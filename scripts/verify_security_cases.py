"""round50：安全黑盒用例集的结构校验（0 token、无网关）。

守的是**用例集本身**：
  ① 规模 ≥30 条，六类各有下限；每条都有可追溯的 title / defect；
  ② 步骤结构合法：方法、路径、鉴权形状、conversation / llm / stream 标记；
  ③ 路径里的 `{var}` 必须由更早步骤的 `save` 提供，不能凭空出现；
  ④ 每个类别都有正对照（cross_tenant / conversation / role），避免“端点根本不存在”造成的假绿；
  ⑤ mock-token 这类环境相关用例必须写 note，跑器才知道 200 要记 SKIP_ENV 而不是 FAIL；
  ⑥ holdout ≥35%，且每个类别在 dev 与 holdout 都有覆盖；
  ⑦ 文本里不得出现 JWT、密钥名或私钥——用例集要能公开提交；
  ⑧ 每一步正对照必绿、注入缺陷必红（`agent_eval_cases.selfcheck_security`）；
  ⑨ 既有判据文件字节级未改。

它不连网关；活体执行是 `scripts/run_security_eval.py`。
"""

import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import agent_eval_cases as shared  # noqa: E402

CASE_KEYS = {"id", "category", "title", "defect", "steps", "priority", "split", "note"}
STEP_KEYS = {"name", "method", "path", "auth", "expect", "body", "body_raw", "headers",
             "conversation", "save", "llm", "control", "stream", "env_skip_if"}
ID = re.compile(r"^SEC-(XT|CONV|ROLE|TOOL|INJ|SSE|IDEM|VAL)-\d{2}$")
ID_CATEGORY = {"XT": "cross_tenant", "CONV": "conversation", "ROLE": "role", "TOOL": "tool_surface",
               "INJ": "injection", "SSE": "sse_idempotency", "IDEM": "sse_idempotency",
               "VAL": "sse_idempotency"}
AUTH = re.compile(r"^(none|forged|(buyer|agent|admin):T\d{3}|minted:T\d{3}:C\d{3})$")
PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")
CHAT = {"/api/v1/support/chat", "/api/v1/support/chat/stream"}
SECRET_PATTERNS = [
    (re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"), "JWT"),
    (re.compile(r"SHOPPILOT_JWT_SECRET"), "密钥名"),
    (re.compile(r"BEGIN [A-Z ]*PRIVATE KEY"), "私钥"),
    (re.compile(r"(?<![0-9a-f])[0-9a-f]{40,}(?![0-9a-f])"), "长十六进制串"),
]
CATEGORY_MIN = {"cross_tenant": 3, "conversation": 3, "role": 8, "tool_surface": 4,
                "injection": 4, "sse_idempotency": 3}
CONTROL_CATEGORIES = {"cross_tenant", "conversation", "role"}
ASSERT_KEYS = {"status", "body_present", "body_absent", "body_regex_absent", "errorCode",
               "answer_present", "answer_absent", "answer_regex_absent", "citations_empty",
               "intent", "intentNot", "tool", "tool_used", "slotAsk", "plan_empty", "plan_tools",
               "plan_tools_absent", "plan_status", "plan_status_in", "fallback", "ticket", "context_history_min",
               "context_history_max", "tokens_zero", "json", "notTool"}


def main() -> int:
    errors: list[str] = []

    for relative, wanted in shared.EXISTING_JUDGE_FILES.items():
        path = REPO / relative
        if not path.exists():
            errors.append(f"{relative} 不存在")
        elif shared.sha256(path) != wanted:
            errors.append(f"{relative} 被改动（哈希不符）；既有判据文件只读")

    cases = shared.load_cases("security")
    if len(cases) < 30:
        errors.append(f"安全用例 {len(cases)} 条，少于 30")

    ids = Counter(case.get("id") for case in cases)
    for value, count in ids.items():
        if not value:
            errors.append("存在没有 id 的用例")
        elif count > 1:
            errors.append(f"id 重复 {value} ×{count}")
        elif not ID.match(value):
            errors.append(f"id={value} 不符合 SEC-<类>-<两位序号>")

    per_category = Counter(case.get("category") for case in cases)
    controls = Counter()
    llm_calls = 0
    holdout_categories = Counter()
    dev_categories = Counter()

    for case in cases:
        label = case.get("id", "<无 id>")
        if not set(case) <= CASE_KEYS:
            errors.append(f"{label} 有未知字段 {sorted(set(case) - CASE_KEYS)}")
        for field in ("category", "title", "defect", "steps", "priority", "split"):
            if not case.get(field):
                errors.append(f"{label} 缺字段 {field}")
        category = case.get("category")
        if category not in shared.SECURITY_CATEGORIES:
            errors.append(f"{label} 的 category={category} 不在 {sorted(shared.SECURITY_CATEGORIES)}")
        elif ID.match(label) and ID_CATEGORY[ID.match(label).group(1)] != category:
            errors.append(f"{label} 的 id 前缀与 category={category} 不一致")
        if not str(case.get("title") or "").strip():
            errors.append(f"{label} 缺 title")
        if not str(case.get("defect") or "").strip():
            errors.append(f"{label} 缺 defect（这条防的是哪个缺陷）")
        if case.get("split") not in ("dev", "holdout"):
            errors.append(f"{label} 的 split 必须是 dev / holdout")
        elif case["split"] == "holdout":
            holdout_categories[category] += 1
        else:
            dev_categories[category] += 1

        steps = shared.security_steps(case)
        if not steps:
            errors.append(f"{label} 没有 steps")
            continue
        names = Counter()
        defined: set[str] = set()
        for index, step in enumerate(steps):
            where = f"{label}/steps[{index}]"
            if not isinstance(step, dict):
                errors.append(f"{where} 不是对象")
                continue
            if not set(step) <= STEP_KEYS:
                errors.append(f"{where} 有未知字段 {sorted(set(step) - STEP_KEYS)}")
            for field in ("name", "method", "path", "auth", "expect"):
                if not step.get(field):
                    errors.append(f"{where} 缺字段 {field}")
            name = step.get("name", "")
            names[name] += 1
            if name and not re.match(r"^[a-z][a-z0-9_]*$", name):
                errors.append(f"{where} 的 name={name!r} 只用小写字母/数字/下划线")
            method = step.get("method")
            if method not in ("GET", "POST"):
                errors.append(f"{where} 的 method={method} 只允许 GET / POST")
            path = str(step.get("path") or "")
            if not path.startswith("/"):
                errors.append(f"{where} 的 path 必须以 / 开头")
            for variable in PLACEHOLDER.findall(path):
                if variable not in defined:
                    errors.append(f"{where} 的 path 变量 {{{variable}}} 没有更早的 save 提供")
            auth = str(step.get("auth") or "")
            if not AUTH.match(auth):
                errors.append(f"{where} 的 auth={auth!r} 不是受支持的令牌形状")
            save = step.get("save")
            if save is not None:
                if not isinstance(save, dict) or not save:
                    errors.append(f"{where} 的 save 必须是非空对象")
                else:
                    defined.update(save)
            expect = step.get("expect")
            if not isinstance(expect, dict) or not expect:
                errors.append(f"{where} 的 expect 必须是非空对象")
                continue
            if not set(expect) & ASSERT_KEYS:
                errors.append(f"{where} 的 expect 没有任何断言")
            if "status" not in expect:
                errors.append(f"{where} 的 expect 必须写 status（安全用例首先看状态码）")
            for marker in expect.get("body_present") or []:
                if marker in (expect.get("body_absent") or []):
                    errors.append(f"{where} 同时要求响应体含/不含 {marker!r}")
            for marker in expect.get("answer_present") or []:
                if marker in (expect.get("answer_absent") or []):
                    errors.append(f"{where} 同时要求答案含/不含 {marker!r}")
            for tool, statuses in (expect.get("plan_status_in") or {}).items():
                if tool not in shared.TOOLS:
                    errors.append(f"{where} 的 plan_status_in 含未知工具 {tool}")
                if not isinstance(statuses, list) or not statuses:
                    errors.append(f"{where} 的 plan_status_in[{tool}] 必须是非空数组")
                elif (expect.get("plan_status") or {}).get(tool) in statuses:
                    errors.append(f"{where} 的 plan_status_in[{tool}] 包含了唯一期望值，应直接用 plan_status")

            if step.get("conversation") and path not in CHAT:
                errors.append(f"{where} 标了 conversation，但 path 不是聊天入口")
            if step.get("llm"):
                llm_calls += 1
                if path not in CHAT:
                    errors.append(f"{where} 标了 llm，但 path 不是聊天入口")
                if not step.get("conversation"):
                    errors.append(f"{where} 标了 llm，但没标 conversation（会拿不到会话号）")
            if "stream" in step:
                if step["stream"] != "abort":
                    errors.append(f"{where} 的 stream 只支持 abort")
                if path not in CHAT or not path.endswith("/stream"):
                    errors.append(f"{where} 标了 stream，但 path 不是 SSE 入口")
                if not step.get("llm"):
                    errors.append(f"{where} 标了 stream，但没标 llm")
            if "body_raw" in step and "body" in step:
                errors.append(f"{where} 不能同时给 body 与 body_raw")
            if step.get("control"):
                controls[category] += 1
                statuses = expect.get("status")
                allowed = statuses if isinstance(statuses, list) else [statuses]
                if not any(isinstance(code, int) and 200 <= code < 300 for code in allowed):
                    errors.append(f"{where} 是正对照，但期望状态不是 2xx：{allowed}")
            if "/auth/mock-token" in path and not case.get("note"):
                errors.append(f"{label} 含 mock-token 用例，必须写 note 说明 200 记 SKIP_ENV")
            env_skip = step.get("env_skip_if")
            if env_skip is not None:
                if not isinstance(env_skip, dict) or not set(env_skip) <= {"status", "plan_status", "reason"}:
                    errors.append(f"{where} 的 env_skip_if 只允许 status / plan_status / reason")
                elif not str(env_skip.get("reason") or "").strip():
                    errors.append(f"{where} 的 env_skip_if 必须写 reason（为什么这个环境不可解释）")
                elif not case.get("note"):
                    errors.append(f"{label} 用了 env_skip_if，必须在 case 上写 note 供人复核")
                else:
                    for tool, status in (env_skip.get("plan_status") or {}).items():
                        if (expect.get("plan_status") or {}).get(tool) == status:
                            errors.append(f"{where} 的 env_skip_if 覆盖了 expect 要求的状态 {tool}={status}，"
                                          "等于把断言改成永远跳过")
        for value, count in names.items():
            if count > 1:
                errors.append(f"{label} 的步骤名重复 {value} ×{count}")

        for problem in shared.selfcheck_security(case):
            errors.append(f"自检：{problem}")

    for category, minimum in CATEGORY_MIN.items():
        if per_category[category] < minimum:
            errors.append(f"安全分类 {category} 只有 {per_category[category]} 条，少于 {minimum}")
    for category in CONTROL_CATEGORIES:
        if controls[category] < 1:
            errors.append(f"安全分类 {category} 没有正对照步骤")
    for category in shared.SECURITY_CATEGORIES:
        if per_category[category] and not dev_categories[category]:
            errors.append(f"安全分类 {category} 在 dev 里没有用例")
        if per_category[category] and not holdout_categories[category]:
            errors.append(f"安全分类 {category} 在 holdout 里没有用例")

    if cases:
        holdout = sum(1 for case in cases if case.get("split") == "holdout")
        ratio = holdout / len(cases)
        if ratio < 0.35:
            errors.append(f"安全 holdout 占比 {ratio:.1%} 低于 35%")

    # ⑦ 可公开提交：不许夹带任何令牌或密钥
    for case in cases:
        raw = str(case)
        for pattern, what in SECRET_PATTERNS:
            if pattern.search(raw):
                errors.append(f"{case.get('id')} 文本里出现{what}形状的串，疑似密钥")
    if llm_calls > 30:
        errors.append(f"llm 步骤 {llm_calls} 次，超过安全套件预算 30")

    if errors:
        print("安全用例集校验失败：")
        for error in errors:
            print(f"  - {error}")
        return 1
    holdout = sum(1 for case in cases if case.get("split") == "holdout")
    steps = sum(len(shared.security_steps(case)) for case in cases)
    print(f"安全用例集 OK：{len(cases)} 条 / {steps} 步 / holdout {holdout}（{holdout / len(cases):.1%}）")
    print(f"  分类: {dict(per_category)}")
    print(f"  正对照: {dict(controls)}；llm 步骤: {llm_calls}（需 --include-llm 且受 --llm-budget 限制）")
    print(f"  变异自检: 每一步正对照绿、注入缺陷红")
    return 0


if __name__ == "__main__":
    sys.exit(main())
