# 票 230：round60 项目收尾（文档换代 + 全量验收 + 发布判定）

Round: 60（收尾 / 验收 / 文档；无新功能面）
ADR: 无（不新增功能；不触发 ADR 0030/0031 的新功能限制）
Status: in-progress（2026-11-25 发布案 B 已落地且独立 Verifier PASS；AC2–AC4 待执行）　Claimed by: root　Blocked by: 无

契约：[plans/230-round60-closeout.md](../../plans/230-round60-closeout.md)（2026-11-25 冻结）

## What to build

1. **文档换代**：`DELIVERY.md` 状态段从 2026-10-19 更新到当前 HEAD；
   `RELEASE.md` 增加 `v1.0.0` 之后未发布指针与发布判定入口；
   `docs/PROJECT_PLAN.md` 保持历史指针不误导；`README` / `STATUS` 数字一致。
2. **全量验收**：在最终 HEAD 实跑 `run-acceptance.ps1 -Tier full`（29 步），
   逐条归因红项；前置不足时登记 SKIP 与边界，不写 PASS。
3. **U2 闭环**：备份 `.env` 后完整跑一次 `up.ps1`，验证 biz-mock 播种演示账号
   与登录 200；恢复 `.env` 并断言 SHA 不变；按结果更新 U2。
4. **欠账裁决**：逐项处理 R1/R3/R5/R6/R8/R10/R15 与 U1/U2/U3/U8/U10/U11，
   给出「保留 / 关闭 / 需所有者裁定」并同步台账。
5. **发布判定**：所有者 2026-11-25 选定 **B（`1.1.0-SNAPSHOT`，不打 tag）**；
   本票只执行 B，C（发布 `v1.1.0`）待 29 步 full 与欠账裁决后再评估。
6. **仓库卫生**：清理本地已合并的 `codex/*` 分支，确认工作区干净、
   HEAD 与 `origin/main` 同步、CI 对最终 HEAD success。

## 边界

- 不改 Java / Vue / 业务配置 / 数据库 / API / gold / 阈值 / 覆盖率门槛。
- 不新增功能；不改验收判据；不删除、跳过或弱化测试。
- 不把未跑、SKIP 或环境红写成 PASS。
- 不在所有者选定发布案前改 `pom.xml` 版本或打 tag。
- 不提交 `.env`、`logs/`、本机截图或临时产物。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-launcher-env.ps1
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体（AC2）：

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full
```

U2（AC3）：备份 `.env` → `scripts/up.ps1` → 断言播种与登录 200 →
恢复 `.env` 并断言 SHA-256 不变。

## Handoff notes

**开票时事实（2026-11-25）**

- 票 01-229 已全部收口，frontier 清空；`main` 与 `origin/main` 同步在
  `19ebd12`，工作区干净，CI 对 `19ebd12` 为 success。
- 当前基线 **878 tests / 144 suites**；`gen_status --check` 与
  `verify_doc_truth` 通过。
- 交付层未收尾项：`v1.0.0` 之后 **400+ 个提交**未发布；`DELIVERY.md`
  状态段停在 2026-10-19（200 提交 / 577 tests / 票 01-150）；
  29 步 full 档最近落点是 2026-10-19 `0bfb400`；U2 完整 `up.ps1` 未复跑；
  工具准确率 `179/180` 因 `ACT-ORD-14` 仍判未达成；活跃欠账
  R1/R3/R5/R6/R8/R10/R15 与 U1/U2/U3/U8/U10/U11。

**关键判定**

- 本票是收尾/验收/文档票，不是功能票；如涉及发布，只执行所有者选定的
  A/B/C 之一，不自行决定版本号或 tag。
- 全量验收与 U2 都需要本机前置（Ollama / Docker / 浏览器 / 内存）；
  前置不足时按边界登记，不能以「定向活体已过」替代 29 步 full。
- 票 229 的「不得改动 `.env`」只属于 229；本票允许备份 → 运行 → 恢复，
  但最终必须断言 SHA-256 不变。
- **发布案已定 B（2026-11-25 所有者裁定）**：5 个 POM 的版本统一为
  `1.1.0-SNAPSHOT`；`scripts/demo.py` 的 `--prebuilt` 改为按
  `shoppilot-<module>-*.jar` 通配解析，避免版本号变更后找不到 jar。
  不打 tag、不建 GitHub Release；C 的前置条件不变。
- **B 落地读数**：`.\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**
  （3:32，878 tests / 144 suites，0 skipped）；三个 fat jar 实际名为
  `shoppilot-{gateway,biz-mock,ticket}-1.1.0-SNAPSHOT.jar`；
  `scripts/demo.py::_fat_jar` 探针实测解析到这三个 1.1.0 jar。
  AC2（29 步 full）、AC3（U2）、AC4（欠账裁决）尚未执行。
- **首轮 B 增量 Verifier P1 已整改**：原实现按 `*.jar` + mtime 取最新，
  旧版本 jar 残留且 mtime 更新时会静默启动旧版本（各模块还可能跨版本混搭）。
  现改为从根 `pom.xml` 读取项目版本，精确拼
  `shoppilot-<module>-<version>.jar`；缺失即返回 `None` 走既有 `missing jars`
  快速失败。受控反证：旧 jar mtime 更新时仍只选 1.1.0；缺失版本返回 `None`。
- **独立 Verifier 复验 PASS（HEAD `0e24718`）**：P0/P1 无；P1 已关闭
  （版本号单一来源，mtime 不再参与选择，缺失即快速失败）；P2「提交计数
  口径未单列修订」已由**契约修订 2**关闭。AC1 换代、AC2/AC3/AC4 尚未执行，
  不在本次 B 增量判定内。

**三个现场追问**

1. 发布案选 A、B 还是 C？建议在全量验收绿之前选 B。
2. 全量 29 步前置不足时，接受「SKIP + 边界」还是等环境？
3. R15 / U10 / U11 是继续登记还是本轮修？收尾票只给建议，需所有者裁决。
