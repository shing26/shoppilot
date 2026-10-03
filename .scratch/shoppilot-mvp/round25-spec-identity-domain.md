# round25 Spec：身份域（program 第二条竖切）

> 状态：**已开轮（2026-10-04）**，票 80-85。
> 依据：[`system-program-customer-service.md`](system-program-customer-service.md) 决策 7 与第 3 节主线、
> [ADR 0052](../../docs/adr/0052-reposition-as-runnable-service-system-prototype.md)（定位改写，program 的所有者授权）、
> [ADR 0056](../../docs/adr/0056-identity-domain-and-audit-stream.md)（身份域内容）、
> [ADR 0058](../../docs/adr/0058-identity-hosting-role-guard-and-self-report-retirement.md)（宿主、角色守卫、自报身份退役）。
> 前一轮：[round24](round24-spec-containerized-stack-profile.md)（可选容器档，票 76-79）。

## 0. 这一轮补的是哪一格

round23 把「人工介入」这条路打通了：降级/审批/点踩都落成工单，规则表分流，坐席在工作台领取、处理、回写。
但**那条路上的责任人一直是空的**——领取人来自 `X-Agent` 请求头，退款审核人来自 `X-Reviewer` 请求头，
两者都进审计事件的 `actor` 字段。退款放行是不可逆的资金动作，它的责任人是一个任何登录用户都能随便写的头。

**这就是本轮要补的那一格：让「谁做的」有账号可查。**

## 1. 硬边界

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9、CI 九步的既有步骤全部原样。
- **`X-Ops-Token` 不退役**（ADR 0058 第 5 条）：验收脚本、调试台、演示脚本全靠它，那批脚本是判据面。
  它与角色守卫是两条线，不是取代关系。
- **ADR 0029 一字不改**：三处默认凭证与 bind-address 门控照旧。身份域**不新增**任何默认凭证。
- **不做的**（ADR 0056 已定，本轮照抄）：refresh token、多因子、密码重置与找回、联邦登录、账号生命周期管理。
- **顺带不做**：买家端前端（program 决策 6 的另一半）、渠道出站消费端、知识与业务服务化——均登记不执行。
- **`ddl-auto: validate` 全档不变**：`users` 表必须走 Flyway（biz-mock `V7`），否则启动红。

## 2. 票序

| 票 | 范围 | Blocked by |
|---|---|---|
| [80](issues/80-user-table-and-bcrypt-authenticate.md) | `users` 表（Flyway V7）+ 三角色 + bcrypt + `/api/identity/*` + 种子账号 | — |
| [81](issues/81-gateway-login-endpoints-and-role-claim.md) | 网关 `/auth/login` `/auth/register` `/auth/me` + `role` claim 进 `TenantContext` | 80 |
| [82](issues/82-role-guard-and-self-report-retirement.md) | 角色守卫 + 自报身份退役 + 审计 actor 区分认证/自报 | 81 |
| [83](issues/83-workspace-real-login.md) | 坐席工作台换真登录（演示入口保留） | 82 |
| [84](issues/84-verify-plan-restore-order.md) | 验收脚本 `plan` 步恢复顺序（票 75 登记的脚本脆弱性） | — |
| [85](issues/85-round25-closeout.md) | 收口（EVIDENCE / CODE_MAP / CONTEXT / README / 审计常数 / tracker） | 80-84 |

**84 与本轮主线无关**（它是票 75 登记的脚本缺陷，本来就不该混进工单竖切），单开一张是为了不把它塞进别人的 `Blocked by` 里。

## 3. 逐票依据（不许混称）

| 票 | 依据 | 性质 |
|---|---|---|
| 80-83 | program 决策 7 + ADR 0052 的定位改写 | **所有者政策越过**（program 已获授权开轮） |
| 84 | 票 75「一条新登记的缺陷（不在本轮修）」 | **事实性修正**：验收脚本自身的恢复顺序缺陷，不动被测代码 |

## 4. 验收里必须有的两条「不成立也算数」

- **认证与自报要能被机器区分**：光有真登录端点不算兑现——必须有一条机器断言能证明
  「审计事件里的 actor 来自已验签的令牌」与「只有 ops 凭证时 actor 被显式标为自报」这两种结局不同。
  否则本轮就退化成「多了一个能登录的接口」，而那正是 0056 否决「维持 mock JWT」时说的「交白卷」。
- **ops token 路径不许被顺手删掉**：那条线是判据面。用例要钉住「带 ops 凭证的既有调用仍然按原样工作」。

## 5. 资源与验证分档（round23 裁定 A 的延续）

本机当前可用内存约 **1.9 GB**，全栈档（round24 容器档的四个服务 + 中间件 + 本地模型）已在跑。
**本轮默认只交 JVM 层证据**，活体一律按未达成登记；确需活体读数时由所有者安排清场日，
按 [`round23-spec`](round23-spec-ticket-routing-vertical-slice.md) §0 的规矩：不得为跑全栈去停别的项目容器。