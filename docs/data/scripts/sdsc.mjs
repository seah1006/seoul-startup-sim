// 소상공인시장진흥공단 상가(상권)정보 API 탐색. 키는 .env에서 읽고, 출력은 mask()로 가린다.
import fs from 'node:fs';
import { DATA_GO_KEY, mask } from './lib.mjs';
const BASE = 'https://apis.data.go.kr/B553077/api/open/sdsc2';
export async function sdsc(op, params) {
  const qs = new URLSearchParams({ serviceKey: DATA_GO_KEY, ...params }).toString();
  const url = `${BASE}/${op}?${qs}`;
  const res = await fetch(url);
  const txt = await res.text();
  let body; try { body = JSON.parse(txt); } catch { body = null; }
  return { status: res.status, url: mask(url).replace(/serviceKey=[^&]*/, 'serviceKey={KEY}'), body, raw: body ? null : mask(txt).slice(0, 600) };
}
if (process.argv[2]) {
  const [op, ...kv] = process.argv.slice(2);
  const params = Object.fromEntries(kv.map(s => s.split('=')));
  const r = await sdsc(op, params);
  console.log(r.status, r.url);
  console.log(r.body ? mask(JSON.stringify(r.body)).slice(0, Number(process.env.MAXLEN || 2500)) : r.raw);
}
