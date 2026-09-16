# ShopPilot v1.0.0

状态：`v1.0.0` 已发布并冻结。Tag `v1.0.0` 指向 CI run `35104751284` 已验证的 release commit `7f4334c`。

## What it does

一条买家诉求从 SSE 对话进入有界状态机，经鉴权、意图判定、缓存与检索后，走到政策答复、业务工具办理或降级工单。项目定位是单机可复现的面试作品，不宣称任何形态的生产上线。

## Verified highlights

- 命中路径 P99 **22 ms**：200 并发、perf 模式、未饱和队列下的读数。
- Token 节约率 **62.4%**：关闭缓存基线与开启缓存后同一流量模型对比。
- 虚拟线程收益：400-800 并发 **+64%**；100-200 并发无收益。

数字口径、数据文件和复现命令见 [README.md](README.md) 与 [docs/EVIDENCE.md](docs/EVIDENCE.md)。

## Known red lines

- 缓存总拦截率 **73.2%-74.0% / 77.8%-78.2%**，判据 `>=80%`，未达成；指标定义与 ADR 0003 的冲突已知但裁决挂起。
- 吞吐峰值 **1013 QPS**，判据 `>=1200`，未达成；发压机与被压网关同机，读数由两边共同限制。
- 未命中 TTFT **690-1499 ms**，判据 `<500 ms`，未达成；perf Mock 首字 300 ms 与本机 embedding 311 ms 已占固定下限。

这三条红线和归因保留在 [README.md](README.md) 的指标与限制段，不通过改口径、阈值或 Mock 参数刷绿。

## Reproduce

```powershell
git clone https://github.com/shing26/shoppilot.git ShopPilot
cd ShopPilot
pwsh -NoProfile -File scripts/up.ps1
pwsh -NoProfile -File scripts/demo.ps1
```

完整入口见 [README.md](README.md) 的快速开始、三条演示和复现段。CI 子集在干净 Ubuntu runner 上只执行构建与全部 JVM 测试，不替代本机 17 步活体验收。

## Known limits

- 业务系统是 `biz-mock`：H2 内嵌库、生成数据，不承诺生产规模。
- 身份提供方是 mock 的：验签真实，发放身份是开发工具；仓库钉的是“身份不可伪造”，不是“身份不可领取”。
- 跨实例 singleflight 已实现但只在单实例环境验证；异机全栈复现仍未验证。
- 工单“可查”只覆盖业务 Mock 进程生命周期，跨重启与 `RESOLVED -> 买家回流` 不承诺。
- 政策语料为生成内容；ES 停在够用级，不做 rerank。

## Freeze policy

`v1.0.0` 后冻结功能面。只接受：

1. 事实性错误、回归、崩溃或现有门禁要求的修复；
2. [ADR 0030](docs/adr/0030-round14-closure-scope-and-reopen-triggers.md) 五条触发条件；
3. [ADR 0031](docs/adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md) 的面试反馈触发条件：同一缺口被不同面试官问过至少两次，且能在 1 个工作日内补齐。

未触发的工作只登记到 [docs/interview-feedback.md](docs/interview-feedback.md)，不直接进入实现队列。
