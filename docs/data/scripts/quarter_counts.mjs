// 분기별 행 수 조사: 각 서비스 x 분기 코드로 1건만 요청해 list_total_count 확인
import { seoul } from './lib.mjs';
const services = ['VwsmTrdarSelngQq','VwsmTrdarStorQq','VwsmTrdarFlpopQq','VwsmTrdarWrcPopltnQq'];
const quarters = [];
for (let y = 2014; y <= 2026; y++) for (let q = 1; q <= 4; q++) quarters.push(`${y}${q}`);
const out = {};
for (const s of services) {
  out[s] = {};
  for (const yq of quarters) {
    const r = await seoul(s, 1, 1, yq);
    const b = r[s];
    out[s][yq] = b ? b.list_total_count : (r.RESULT?.CODE || JSON.stringify(r).slice(0,80));
  }
  console.log(s, JSON.stringify(out[s]));
}
