| 反义对在 0.95 下不互相命中 | 系统 · 守卫层 | 「这个能退吗」/「这个不能退吗」余弦 0.9799，两侧同为 T0 命中的 `POLICY_RETURN`，准入层放行；`scripts/verify-polarity.ps1` 实测这次 L2 命中被 `PolarityGuard` 在复用前拒掉，`shoppilot_cache_l2_polarity_blocked_total` +1 | 通过（守卫兜住，非阈值兜住） |
| 反义对在 0.95 下不互相命中 | 系统 · 准入层 | 「这个是不是不能退」不含任何 RETURN 关键词，T0 认不出 -> `intent=UNKNOWN` -> fail-closed 根本不进缓存读取路径；同一脚本实测该探针的守卫计数增量为 0，证明兜住它的是准入层而非守卫 | 通过 |
