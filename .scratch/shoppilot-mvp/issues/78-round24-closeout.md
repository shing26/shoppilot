# 78 round24 收口：可选容器档

**Status:** blocked（等 77 的 `container_name` 冲突解决）

## What to build

- `docs/EVIDENCE.md` 的 round24 行（镜像体积、真跑读数、清场日那条与本轮无关）；
- `docs/CODE_MAP.md` 加容器档一节（哪条命令起什么、四个服务各自的镜像与端口）；
- `README` 的「怎么起」段补一行 `-Containerized`，并写清**要导出三个凭证**这件事
  （ADR 0029 的守卫会拦，本轮实测拦过一次）；
- 收口审计常数换代（G6 是四模块读数，本轮加的 `SHOPPILOT_*` 不影响判据面，应无其它改动）；
- **必须留下的欠账**：
  1. 固定 `container_name` 挡住第二份克隆（票 77 未达成）；
  2. compose 里 gateway 对依赖用 `service_started` 而非健康门（票 76 登记）；
  3. 容器化**不省内存**这件事本身（spec §0，本轮二次验证：引擎被内存压杀过一次）。
