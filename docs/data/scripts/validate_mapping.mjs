// 매핑 검증: 서울시 전체 점포 수(점포-서울시, 최신 분기) vs 소상공인 서울 상가(2026-09)를 매핑 초안으로 합산한 값 비교
import fs from 'node:fs';
import { seoul } from './lib.mjs';
const S = 'VwsmMegaStorW';
let rows = []; const f = await seoul(S, 1, 1000); const total = f[S].list_total_count; rows = f[S].row;
for (let s = 1001; s <= total; s += 1000) rows = rows.concat((await seoul(S, s, s + 999))[S].row);
const latest = rows.reduce((m, r) => r.STDR_YYQU_CD > m ? r.STDR_YYQU_CD : m, '0');
const seoulCnt = Object.fromEntries(rows.filter(r => r.STDR_YYQU_CD === latest).map(r => [r.SVC_INDUTY_CD, [r.SVC_INDUTY_CD_NM, r.SIMILR_INDUTY_STOR_CO]]));
const map = fs.readFileSync('docs/data/industry_mapping_draft.csv', 'utf8').replace(/^\uFEFF/, '').split('\n').slice(1).filter(Boolean).map(l => l.split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/));
const sb = {}; for (const r of map) if (r[2]) sb[r[2]] = (sb[r[2]] || 0) + +r[6];
const out = Object.entries(seoulCnt).map(([c, [n, v]]) => ({ c, n, seoul: v, sbiz: sb[c] || 0, ratio: v ? +((sb[c] || 0) / v).toFixed(2) : null })).sort((a, b) => a.c.localeCompare(b.c));
fs.writeFileSync('data/raw/mapping_validation.json', JSON.stringify({ latest, out }, null, 1));
console.log('latest', latest, 'rows', total);
const qs = out.filter(o => o.sbiz > 0).map(o => o.ratio).sort((a, b) => a - b);
console.log('ratio quantiles', [0, .1, .25, .5, .75, .9, 1].map(p => qs[Math.min(qs.length - 1, Math.floor(p * qs.length))]));
console.log('within 0.5~2.0:', qs.filter(x => x >= 0.5 && x <= 2).length, '/', qs.length);
console.log(out.filter(o => o.sbiz > 0 && (o.ratio < 0.5 || o.ratio > 2)).map(o => `${o.c} ${o.n} 서울시=${o.seoul} 소상공인매핑=${o.sbiz} (${o.ratio})`).join('\n'));
console.log('외식 비교:', out.filter(o => o.c.startsWith('CS1')).map(o => `${o.n} ${o.seoul}/${o.sbiz}`).join(', '));
