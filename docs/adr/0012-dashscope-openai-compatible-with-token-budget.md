# dev 模式使用 DashScope OpenAI 兼容端点，并设 token 预算熔断

Context: ADR 0001 把"Tool Calling 准确率 >= 95%"押在 dev 模式的真实模型上，而本地 `qwen2.5:3b` 在中文电商口语上的参数抽取现实准确率约 60-80%，撑不起该指标。决定：dev 模式接阿里云 DashScope 的 OpenAI 兼容端点（`https://dashscope.aliyuncs.com/compatible-mode/v1`），评测与演示默认 `qwen-plus`，关键演示路径可临时切 `qwen-max`；`local` 模式仍走 Ollama 用于降级链路验证，不用于准确率评测。

Consequences:
- 选 OpenAI 兼容协议而非厂商 SDK，客户端代码换供应商零改动，这层抽象本身可作为交付亮点。
- qwen 系列与本地 `qwen2.5:3b` 同族，`dev -> local` 降级时行为差异最小，降级链路验证才有意义。
- API key 只经环境变量 `SHOPPILOT_LLM_API_KEY` 注入，仓库仅保留 `.env.example`，`application.yml` 不得出现明文。
- dev 模式设 token 日预算熔断（默认 20 万 token），超限走新增的 `LLM_BUDGET_EXCEEDED` 降级原因；评测脚本一旦写错会连续打数百次模型，烧额度是真实风险。
- ADR 0009 的降级原因枚举增加 `LLM_BUDGET_EXCEEDED`。
