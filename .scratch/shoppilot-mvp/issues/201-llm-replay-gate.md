# 票 201：LLM 录放回归门

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 200（已收口）

## What to build

1. **回放模式**：新增 `shoppilot.llm.mode=replay`（环境变量 `SHOPPILOT_LLM_MODE=replay`）。
   生产默认值仍然是 `local`，`replay` 只用于门禁与本地离线复跑。
2. **cassette 契约**（`eval/llm-cassettes/`，入库）：
   - 路径：`<model>/<promptVersion>/<sha256>.json`；
   - 内容：请求摘要（messages / tools / temperature / maxTokens）、响应体、模型名、
     `promptVersion`、录制时间、来源（`dev` / `local`）；
   - key = `sha256(model + promptVersion + 规范化 messages + 工具 schema + 采样参数)`；
   - **缺 cassette 即 fail-closed**：不回退真实模型、不回退 Mock，直接报错并指出缺失的 key。
3. **录制器**：新增 `scripts/record_llm_cassettes.py` 或等价的 Java 装配层录制器；
   录制时包住现有 `LlmClient`，生产代码路径不改，只在装配层换实现。
4. **离线门禁**：新增 `scripts/verify_llm_replay.py`：
   - 读 cassette + 请求夹具，跑既有 `judge()` / `eval_suites.py` / `eval_task.py` 的同一套判据；
   - 与入仓基线逐条比对；差异必须显式更新基线，不能自动放行；
   - 0 token、不连模型、不连检索/业务服务；
   - 缺 cassette、响应形状不合法、判据差异三类都返回非 0。
5. **CI 步**：`.github/workflows/ci-subset.yml` 新增一步「LLM replay gate (0-token)」，
   与既有 0 token 门禁并列；不改变现有步骤的判据。
6. **JVM 用例**：至少一条端到端用例证明同一请求在 replay 模式下得到与录制时相同的工具选择与
   答案摘要；缺 cassette 时红。再用一条变异对照（改 replay key 或改响应字段）证明门禁不是恒绿。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify_llm_replay.py
python scripts/eval_suites.py
python scripts/verify_eval_judge.py
git diff --check
```

## 验收项

- `llm.mode=replay` 可启动、可离线复跑；生产默认不变。
- 缺 cassette 时 fail-closed，错误信息能指到具体 key 与请求摘要。
- CI 新步在干净 runner 上 0 token 通过；改坏一条 cassette 会红，还原即绿。
- 回放结果与录制时的工具选择、答案摘要、判据结论一致。
- 文档写清边界：**回放守编排 / 提示词回归，不证明模型漂移**；模型能力仍靠周期性活体评测。
- 至少一组真实模型 cassette 入库（数量与来源在 Handoff 写清；未录满的条目标「未覆盖」，
  不许用 Mock 录制冒充真实模型）。

## Handoff notes

**关键决策**

- **replay 是第五种 `llm.mode`，不是生产回退**：`SHOPPILOT_LLM_MODE=replay` 只读 cassette；
  缺卡抛 `LlmException(UNAVAILABLE)`，消息带文件路径、key、model、promptVersion 与最后一条
  消息摘要。没有任何“找不到就调真模型/调 Mock”的分支——那会让门禁绿着通过。
- **key 覆盖所有会改变模型行为的输入**：`sha256(model + promptVersion + 规范化 messages +
  tools + 生效 temperature + maxTokens)`。对象键排序、数组保序；temperature 用客户端实际
  回落后的值（请求 <=0 时取配置值），避免录制与回放各算一个 key。模型名进目录时做文件名
  安全化（`qwen2.5:3b` → `qwen2.5-3b`）。
- **录制在装配层，不改状态机**：`RecordingLlmClient` 只包 dev/local 的真实客户端；
  perf/demo 的确定性输出**不允许**进 cassette。录制开关是 `SHOPPILOT_LLM_RECORD_DIR`，
  空值即关闭；写盘失败只 warn（录制不是生产路径，但缺失会在回放时 fail-closed）。
- **真实模型 cassette 用 dev `qwen-plus` / promptVersion `v1.2.0`，共 6 张**：
  政策 2 条、订单详情/物流/退款/改地址各 1 条。录制器 `LlmCassetteRecorder` 类名不以
  `Test` 结尾，普通 `mvnw verify` 不跑；必须 `SHOPPILOT_RECORD_CASSETTES=1` 显式拉起。
  **其中“改地址”那张模型当时用散文追问、没有发工具**，基线如实记录 `tool_ok=false`——
  门禁的语义是“与录制时一致”，不是“模型答对了”。不要为了让 gate 好看去删这条。
- **没有录 local 3B**：本机只装了 `qwen2.5:1.5b`，没有文档默认的 `qwen2.5:3b`；用 1.5b
  冒充 3B 会污染“默认模型”结论。留待票 206 定默认模型后补录，此处显式登记未覆盖。

**验证落点**

```text
./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true
    BUILD SUCCESS；四模块 12 / 92 / 626 / 50 = 780 tests / 131 suites、0 失败
    （不加 skip 时 repackage 被运行中的网关 fat jar 锁住，是 README 已登记的本机限制）
python scripts/gen_status.py --check          STATUS ok
python scripts/verify_llm_replay.py           PASS；6 张卡 / 1 个模型；judge tool_ok 5/6；0 token
python scripts/verify_eval_judge.py           40/40 PASS
python scripts/eval_suites.py                 SUITE SELFCHECK ok=30
变异实测：改一张卡的 response.content → 退出 1；还原 → 退出 0
```

JVM 侧 `ReplayLlmClientTest` 6 条：录制→回放逐字段一致、流式拼接、入仓真卡回放、
缺卡 fail-closed、messages 变一个字节即不命中、改 cassette 的 key 字段立即红。CI 新增独立一步
「LLM replay gate (0-token)」，不改既有步骤判据。

**三个现场追问**

1. **回放能证明模型没退化吗？** 不能。卡里录的是 2026-11-04 的 qwen-plus 回复；换模型、
   供应商降智、采样端变化，本门禁仍然绿。它守的是编排/提示词/工具 schema 回归；模型能力
   仍靠周期性 180 gold 与 UX golden 活体评测。这句话同时写进 README 与 EVIDENCE。
2. **为什么不把 180 gold 全录成 cassette？** 单次 LLM 往返与 gold 用例不是一一对应：一个
   用例会经过 T0/T1、检索、多轮工具与答案轮，录一张卡只固定其中一次调用。全量录放需要把
   “整轮请求序列”做成契约，属于另一个量级；本轮先立住单调用门禁与 fail-closed 纪律。
3. **promptVersion 变了会怎样？** 目录第一段换成新版本，旧卡不会再被读到；回放立刻因缺卡
   报红，必须显式重录并更新基线。这是有意的：提示词换代就该逼一次人工复核，而不是让旧卡
   继续冒充新提示词的结果。
