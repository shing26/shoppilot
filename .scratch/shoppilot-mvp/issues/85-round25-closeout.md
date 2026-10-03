# 85 round25 收口：身份域

**Status:** ready-for-agent

## What to build

- **`docs/EVIDENCE.md`**：新读数（身份域 JVM 读数、角色守卫的变异对照、CI 步数）；**未达成照登**。
- **`docs/CODE_MAP.md`**：身份域的位置与首改文件；**并写清 biz-mock 的域范围已被 ADR 0058 追加「身份与账号」**
  ——0053 的清单不含它，不写清就是让后来者按旧清单找不到。
- **`CONTEXT.md`**：新术语（账号、角色、认证身份 vs 自报身份）——**只加术语，不改既有结论**。
- **`README.md` / `DELIVERY.md` / `CHANGELOG.md`**：定位与端口照 ADR 0052；**性能与判据数字不得因身份域而重算**。
- **审计常数**：G6 的 surefire 计数、CI 步数按实读数换代。
- **tracker**：票 80-85 状态与 Handoff。

## Blocked by

[80](80-user-table-and-bcrypt-authenticate.md) … [84](84-verify-plan-restore-order.md) 全部收口。

## 口径

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9。
- ADR 0024 的「不宣称上线」红线在交付层照写（0052 继承）。
- **ADR 0056 要求写进交付材料的那句必须写**：密码与令牌策略只做到演示口径
  （BCrypt cost、锁定策略、密钥轮换都未做量产级处理）。
- 未达成项保留实测值、归因与限制，**不摘红**。按 spec §5，本轮的未达成栏至少包含：活体登录与角色守卫的端到端读数。

## 验收

- 收口审计读数落 `docs/EVIDENCE.md`；
- 干净克隆的 CI 九步全绿（前端构建门禁那一步仍要逐字节校验产物）。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

（收口时补）