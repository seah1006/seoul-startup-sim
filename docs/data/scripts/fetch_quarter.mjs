// 분기 전체 행 내려받기 -> data/raw/{service}_{yq}.json (키는 lib.mjs가 .env에서 읽음)
import fs from 'node:fs';
import { seoul } from './lib.mjs';
const [service, yq] = process.argv.slice(2);
const args = yq && yq !== 'all' ? [yq] : [];
const first = await seoul(service, 1, 1000, ...args);
const total = first[service].list_total_count;
let rows = first[service].row;
for (let s = 1001; s <= total; s += 1000) {
  const r = await seoul(service, s, Math.min(s + 999, total), ...args);
  if (!r[service]) { console.error('error at', s, JSON.stringify(r).slice(0, 200)); process.exit(1); }
  rows = rows.concat(r[service].row);
}
fs.writeFileSync(`data/raw/${service}_${yq || 'all'}.json`, JSON.stringify(rows));
console.log(service, yq, 'total', total, 'fetched', rows.length, 'calls', Math.ceil(total / 1000));
