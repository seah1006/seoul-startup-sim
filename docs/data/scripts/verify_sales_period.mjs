// 추정매출-서울시(VwsmMegaSelngW) 분기 합계를 golmok 메인의 "월평균 매출액"과 비교해 THSMON 기준 기간 검증
import { seoul } from './lib.mjs';
const S = 'VwsmMegaSelngW';
const first = await seoul(S, 1, 1000); const total = first[S].list_total_count; let rows = first[S].row;
for (let s = 1001; s <= total; s += 1000) rows = rows.concat((await seoul(S, s, s + 999))[S].row);
const by = {}; for (const r of rows) { by[r.STDR_YYQU_CD] ??= { amt: 0, n: 0 }; by[r.STDR_YYQU_CD].amt += r.THSMON_SELNG_AMT; by[r.STDR_YYQU_CD].n++; }
console.log('rows', total);
for (const q of ['20252', '20261', '20262']) console.log(q, 'industries', by[q].n, 'sum(억원)', Math.round(by[q].amt / 1e8), 'sum/3(억원)', Math.round(by[q].amt / 3e8));
