// 공통: .env에서 키를 읽어 변수로만 사용. 키 값은 출력/저장하지 않는다.
import fs from 'node:fs';
const ENV_PATH = process.env.SIM_ENV_PATH || 'C:/Users/my/dev/seoul-startup-sim/.env';
export function loadEnv() {
  const out = {};
  for (const line of fs.readFileSync(ENV_PATH, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return out;
}
const env = loadEnv();
export const SEOUL_KEY = process.env.SEOUL_OPEN_API_KEY || env.SEOUL_OPEN_API_KEY;
export const DATA_GO_KEY = process.env.DATA_GO_KR_SERVICE_KEY || env.DATA_GO_KR_SERVICE_KEY;
export function mask(s) {
  let r = String(s);
  for (const k of [SEOUL_KEY, DATA_GO_KEY, encodeURIComponent(DATA_GO_KEY || '')]) if (k) r = r.split(k).join('{KEY}');
  return r;
}
export async function seoul(service, start, end, ...args) {
  const url = `http://openapi.seoul.go.kr:8088/${SEOUL_KEY}/json/${service}/${start}/${end}/${args.map(encodeURIComponent).join('/')}`;
  for (let i = 0; i < 3; i++) {
    try {
      const res = await fetch(url);
      const txt = await res.text();
      try { return JSON.parse(txt); } catch { return { _raw: mask(txt).slice(0, 500), _status: res.status }; }
    } catch (e) { if (i === 2) throw new Error(mask(e.message)); await new Promise(r => setTimeout(r, 1000)); }
  }
}
export function seoulUrlMasked(service, start, end, ...args) {
  return `http://openapi.seoul.go.kr:8088/{KEY}/json/${service}/${start}/${end}/${args.join('/')}`;
}
