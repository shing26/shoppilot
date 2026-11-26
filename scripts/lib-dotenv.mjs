// 验收脚本共用的 .env 读取。凭证唯一来源：进程环境（非空）> 仓库根 .env。
// 2026-11-25 票 230 的 R16：脚本内置 dev 默认值，而 bootstrap_env.py 生成随机值，
// fresh clone 跑 full 会在鉴权步骤整片红。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

export function envValue(name) {
  const fromProcess = process.env[name];
  if (fromProcess && fromProcess.trim()) return fromProcess.trim();
  const file = join(ROOT, '.env');
  if (!existsSync(file)) return '';
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const text = line.trim();
    if (!text || text.startsWith('#') || !text.includes('=')) continue;
    const at = text.indexOf('=');
    if (text.slice(0, at).trim() !== name) continue;
    return text.slice(at + 1).trim().replace(/^['"]|['"]$/g, '');
  }
  return '';
}

export function requireEnv(name, message) {
  const value = envValue(name);
  if (value) return value;
  console.error(message || (`缺少 ${name}：请在 .env 里给一个（python scripts/bootstrap_env.py）再跑。`));
  process.exit(2);
}
