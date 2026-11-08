# round48 收口验收契约

- Task ID: review-round48-closeout
- 日期冻结: 2026-11-09
- **裁决: PASS（2026-11-09，见文末「裁决记录」）**
- 固定点: `fdb4a37`（round48 首个提交 348dec7 的父提交）
- 受审 HEAD: `72991e31131d23bf4d6e307e660de6826c411cf9`
- 范围: `git diff fdb4a37...HEAD`，5 个提交（348dec7, 15f1f40, 7236926, 08d4648, 72991e3），106 文件 +15745/−301

## 轴线

- Standards 轴: AGENTS.md 硬性规则 + docs/CODE_MAP.md + CONTEXT.md 术语 + Fowler smell baseline
- Spec 轴: tracker 票 204–209（`.scratch/shoppilot-mvp/issues/`）+ round48 定位

## Allowed paths（Verifier 可读写/执行的路径）

- Verifier 只读整个仓库；写入仅限本计划文件与会话临时目录。
- 禁止改动受审代码、docs/、eval/、loadtest/、.scratch/tracker。

## Acceptance criteria

1. Standards 轴与 Spec 轴各自产出报告，保持分离不合并。
2. Verifier 用全新会话独立重跑下列 Verification commands，证据不得采信实现方贴出的日志。
3. 证据绑定 HEAD SHA `72991e3` + `git status --short` 快照。
4. 硬违规（改判据/gold/分母换绿、改 ADR 结论、动历史证据目录、ticket 缺 Status/Handoff）= 验收失败。
5. baseline smell 一律按 judgement call 报告，不构成验收失败，除非 repo standard 覆盖后仍违反。

## Verification commands（Verifier 亲自重跑）

```bash
git rev-parse HEAD                          # 确认 SHA
git status --short                          # 快照（仅本机残留，.claude/.scratch 调试脚本）
git diff --check fdb4a37...HEAD             # diff 内无 whitespace 错误
git log --oneline fdb4a37..HEAD             # 提交风格 docs/fix/test(round48)
python scripts/verify_doc_truth.py          # 文档真值门禁（票 207 产物自验）
python scripts/gen_status.py --check        # STATUS 生成一致性
pwsh -NoProfile -File scripts/check-ps-syntax.ps1   # PowerShell 脚本语法（本段改动涉及 start-gateway.ps1/ingest.ps1/up.ps1）
```

不重跑的内容（记录为有意缺口，理由）：

- `mvnw -B -ntp verify`：JVM 测试在 round48 期间已多轮跑过，本轮为文档+脚本+前端为主；
  Verifier 可选择性补跑（不强制，时间约数分钟）。
- 前端 vitest（frontend-buyer/frontend-workspace）与 nightly Testcontainers 工作流：
  依赖本机 Docker/Node 状态，Verifier 以读代码 + CI run 记录为准，不活体重跑。
- token 预算键不清零属所有者裁决，不重跑消耗 token 的活体验证。

## Forbidden

- Verifier 不得修改受审内容；发现问题时只记录，修复回本会话处理。
- 不得为通过验证改判据、gold、阈值、分母。
- 不得清理 .scratch/、target/、logs/ 等本机目录。

## 裁决记录（2026-11-09，收尾追加）

- **裁决: PASS**。证据链：双轴并行审查（Standards + Spec，两个独立子代理）→ 独立 Verifier 全新会话亲自重跑全部 7 条契约命令（退出码全 0）+ 补跑 `./mvnw.cmd -B -ntp -q -pl shoppilot-gateway -am test` 全绿。证据绑定 HEAD SHA `72991e3` 与 `git status --short` 快照（验证前后一致，仅本机残留）。
- **非阻断缺陷（待修）**: `scripts/run_async_loadtest.py` 内存键 `rate_limited_delta`（第 80 行）与 CSV 字段 `ratelimited_delta`（第 119 行）错位，`DictWriter(extrasaction="ignore")` 静默丢列；已入库 `ladder-mix80-async-perf-20261105.csv` 该列为空。本轮限流实际为 0（三档 valid==requests），派生指标内存预算后落盘，无数据错误；损失为证据可复算性。
- **有痕残余（2）**: ① 票 208 TTFT 探针读数（252.6/277.3 ms）未落 `loadtest/results/` 产物（probe_embedding_latency.py 只 print）；② 票 204 nightly.yml 已入库但未在 GitHub runner 实跑，票面已如实登记。
- **轻微票外修复（2，已留痕）**: `ingest.ps1`/`up.ps1`、`.gitattributes` CRLF 豁免，Handoff 补录。
- **judgement calls（4）**: 自标注夹具判定三处重复、surefire 正则双脚本逐字重复、GatewayProperties 测试构造器 14 连 null、profile→mode 映射双写。择机收拢，不阻断。
