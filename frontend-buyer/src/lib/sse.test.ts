/**
 * SSE 分帧（round48 票 205 / ADR 0083）。
 *
 * <p>这几个用例钉的是 round21 踩过的坑：帧必须按 `\n\n` 切，不能猜子串；
 * 半帧要留到下一块；`data:` 只有在同一帧里先出现 `event:` 时才成立。
 */
import { describe, expect, it } from 'vitest'
import { parseFrame, SseFrameReader } from './sse'

describe('parseFrame', () => {
  it('把一帧解析成 event/data 对', () => {
    expect(parseFrame('event: token\ndata: "你好"')).toEqual([{ event: 'token', data: '"你好"' }])
  })

  it('data 行前面没有 event 时忽略（与下沉前一致）', () => {
    expect(parseFrame('data: orphan\n\nevent: done\ndata: {}')).toEqual([{ event: 'done', data: '{}' }])
  })

  it('同一帧里多条 data 各自成事件', () => {
    expect(parseFrame('event: token\ndata: "a"\ndata: "b"')).toEqual([
      { event: 'token', data: '"a"' },
      { event: 'token', data: '"b"' },
    ])
  })
})

describe('SseFrameReader', () => {
  it('一次 push 里有多帧时全部返回，帧边界按 \\n\\n', () => {
    const reader = new SseFrameReader()
    expect(reader.push('event: status\ndata: {"label":"识别意图"}\n\nevent: token\ndata: "答"\n\n')).toEqual([
      { event: 'status', data: '{"label":"识别意图"}' },
      { event: 'token', data: '"答"' },
    ])
  })

  it('跨 chunk 的半帧留到下一块再解析', () => {
    const reader = new SseFrameReader()
    expect(reader.push('event: token\nda')).toEqual([])
    expect(reader.push('ta: "你')).toEqual([])
    expect(reader.push('好"\n\n')).toEqual([{ event: 'token', data: '"你好"' }])
  })

  it('一帧结束后事件名不留给下一帧', () => {
    const reader = new SseFrameReader()
    expect(reader.push('event: token\ndata: "a"\n\ndata: "b"\n\n')).toEqual([{ event: 'token', data: '"a"' }])
  })
})
