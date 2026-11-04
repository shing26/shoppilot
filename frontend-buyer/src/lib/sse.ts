/**
 * SSE 分帧（round48 票 205 / ADR 0083，从 `api.ts` 下沉）。
 *
 * <p>分帧不能按子串猜：round21 记过一次「流式分帧不能子串匹配 → 读回用同步端点」。
 * 这里只做一件事——把网络读到的字节块还原成 `event:` / `data:` 对；**一帧的边界是 `\n\n`**，
 * 跨 chunk 的半帧留在缓冲里等下一块。它没有 IO、没有状态机分支，是买家端最值得单测的一块。
 *
 * <p>行为与下沉前逐字一致：帧内 `data:` 只有在前面出现过 `event:` 时才认；同一帧里出现
 * 多条 `data:` 会各自产出事件（调用方按既有分支处理）；帧处理完事件名即丢弃。
 */
export interface SseEvent {
  event: string
  data: string
}

/** 一帧的原文 → 事件序列。 */
export function parseFrame(frame: string): SseEvent[] {
  const events: SseEvent[] = []
  let event = ''
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:') && event) {
      events.push({ event, data: line.slice(5).trim() })
    }
  }
  return events
}

/**
 * 有状态的读帧器：`push` 一块文本，返回其中已经完整的帧。
 *
 * <p>字符串拼接而不是 `TextDecoder`：解码仍在调用方做（它要处理多字节字符跨块的
 * `{ stream: true }`），这里只负责分隔符。
 */
export class SseFrameReader {
  private buffer = ''

  push(chunk: string): SseEvent[] {
    this.buffer += chunk
    const events: SseEvent[] = []
    let split = this.buffer.indexOf('\n\n')
    while (split >= 0) {
      const frame = this.buffer.slice(0, split)
      this.buffer = this.buffer.slice(split + 2)
      events.push(...parseFrame(frame))
      split = this.buffer.indexOf('\n\n')
    }
    return events
  }
}
