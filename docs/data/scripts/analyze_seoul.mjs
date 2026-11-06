// 서울 상권분석 데이터 분석 (data/raw 의 API 덤프와 연도별 zip CSV 사용)
import fs from 'node:fs';
const J = f => JSON.parse(fs.readFileSync(`data/raw/${f}`, 'utf8'));
const csv = (d) => { const t = new TextDecoder('euc-kr').decode(fs.readFileSync(`data/raw/${d}/data.csv`)); const L = t.split(/\r?\n/).filter(Boolean); const h = L[0].split(',').map(s=>s.replace(/"/g,'')); return L.slice(1).map(l => { const v = l.split('","').map(s=>s.replace(/"/g,'')); const o={}; h.forEach((k,i)=>o[k]=v[i]); return o; }); };
const relm = J('TbgisTrdarRelm_all.json');
const s26 = J('VwsmTrdarSelngQq_20262.json'), s23 = J('VwsmTrdarSelngQq_20231.json');
const t26 = J('VwsmTrdarStorQq_20262.json'), t23 = J('VwsmTrdarStorQq_20231.json');
const wrc = J('VwsmTrdarWrcPopltnQq_all.json'), fl = J('VwsmTrdarFlpopQq_20262.json');
const cnt = (arr, f) => arr.reduce((m, r) => (m[f(r)] = (m[f(r)]||0)+1, m), {});
const R = {};
// 1. 영역
R.relm_count = relm.length;
R.relm_by_type = cnt(relm, r => `${r.TRDAR_SE_CD}:${r.TRDAR_SE_CD_NM}`);
const xs = relm.map(r=>+r.XCNTS_VALUE), ys = relm.map(r=>+r.YDNTS_VALUE), ar = relm.map(r=>+r.RELM_AR);
R.relm_x_range = [Math.min(...xs), Math.max(...xs)]; R.relm_y_range = [Math.min(...ys), Math.max(...ys)];
R.relm_area_range = [Math.min(...ar), Math.max(...ar)]; R.relm_signgu = new Set(relm.map(r=>r.SIGNGU_CD)).size; R.relm_adstrd = new Set(relm.map(r=>r.ADSTRD_CD)).size;
const dbf = J('area/trdar_dbf.json'); const relmSet = new Set(relm.map(r=>r.TRDAR_CD)); const dbfSet = new Set(dbf.map(r=>r.TRDAR_CD));
R.shp_vs_api = { shp: dbfSet.size, api: relmSet.size, both: [...dbfSet].filter(c=>relmSet.has(c)).length, onlyShp: [...dbfSet].filter(c=>!relmSet.has(c)).slice(0,10), onlyApi: [...relmSet].filter(c=>!dbfSet.has(c)).slice(0,10) };
const relmDiff = relm.filter(r => { const d = dbf.find(x=>x.TRDAR_CD===r.TRDAR_CD); return d && (d.XCNTS_VALU!==String(r.XCNTS_VALUE) || d.RELM_AR!==String(r.RELM_AR) || d.TRDAR_CD_N!==r.TRDAR_CD_NM); });
R.shp_api_attr_diff = relmDiff.length;
// 2. 업종
const storIndSet = new Set(t26.map(r=>r.SVC_INDUTY_CD)), salesIndSet = new Set(s26.map(r=>r.SVC_INDUTY_CD));
const ind = {}; for (const r of [...t26, ...s26]) ind[r.SVC_INDUTY_CD] = r.SVC_INDUTY_CD_NM;
R.industry_count_stores26 = new Set(t26.map(r=>r.SVC_INDUTY_CD)).size; R.industry_count_sales26 = new Set(s26.map(r=>r.SVC_INDUTY_CD)).size;
R.industry_by_prefix = cnt(Object.keys(ind), c => c.slice(0,3));
fs.writeFileSync('data/raw/service_industries.json', JSON.stringify(Object.entries(ind).sort().map(([c,n])=>({code:c,name:n, inStores: storIndSet.has(c), inSales: salesIndSet.has(c)}))));
R.industries_not_in_sales = Object.keys(ind).filter(c => !salesIndSet.has(c)).map(c=>`${c} ${ind[c]}`);
// 3. 매출 합계 관계
const sum = (r, ks) => ks.reduce((s,k)=>s+(+r[k]||0),0);
const days = ['MON','TUES','WED','THUR','FRI','SAT','SUN'].map(d=>`${d}_SELNG_AMT`), tz = ['00_06','06_11','11_14','14_17','17_21','21_24'].map(t=>`TMZON_${t}_SELNG_AMT`), ag = ['10','20','30','40','50','60_ABOVE'].map(a=>`AGRDE_${a}_SELNG_AMT`);
const chk = (f) => { let eq=0, tot=0; for (const r of s26) { tot++; if (Math.abs(f(r) - r.THSMON_SELNG_AMT) <= 2) eq++; } return `${eq}/${tot}`; };
R.sales_eq_weekday_weekend = chk(r => r.MDWK_SELNG_AMT + r.WKEND_SELNG_AMT);
R.sales_eq_days = chk(r => sum(r, days)); R.sales_eq_tmzon = chk(r => sum(r, tz));
R.sales_eq_gender = chk(r => r.ML_SELNG_AMT + r.FML_SELNG_AMT); R.sales_eq_age = chk(r => sum(r, ag));
R.sales_gender_ratio_median = (()=>{ const a = s26.filter(r=>r.THSMON_SELNG_AMT>0).map(r=>(r.ML_SELNG_AMT+r.FML_SELNG_AMT)/r.THSMON_SELNG_AMT).sort((a,b)=>a-b); return [a[0], a[Math.floor(a.length/2)], a[a.length-1]]; })();
R.sales_cnt_eq = (()=>{ let eq=0; for (const r of s26) if (r.MDWK_SELNG_CO + r.WKEND_SELNG_CO === r.THSMON_SELNG_CO) eq++; return `${eq}/${s26.length}`; })();
R.sales_zero_amt_rows = s26.filter(r=>!r.THSMON_SELNG_AMT).length; R.sales_neg_rows = s26.filter(r=>r.THSMON_SELNG_AMT<0).length;
R.sales_amt_quantiles = (()=>{ const a = s26.map(r=>r.THSMON_SELNG_AMT).sort((a,b)=>a-b); return [0,0.25,0.5,0.75,0.99,1].map(p=>a[Math.min(a.length-1,Math.floor(p*a.length))]); })();
R.seoul_total_sales_20262 = s26.reduce((s,r)=>s+r.THSMON_SELNG_AMT,0);
R.sales_types = typeof s26[0].THSMON_SELNG_AMT;
// 4. 점포 관계
R.stor_similr_eq_stor_plus_frc = `${t26.filter(r=>r.SIMILR_INDUTY_STOR_CO===r.STOR_CO+r.FRC_STOR_CO).length}/${t26.length}`;
R.stor_similr_ge_stor = `${t26.filter(r=>r.SIMILR_INDUTY_STOR_CO>=r.STOR_CO).length}/${t26.length}`;
R.stor_stor_eq_0 = t26.filter(r=>r.STOR_CO===0).length; R.stor_similr_eq_0 = t26.filter(r=>r.SIMILR_INDUTY_STOR_CO===0).length;
R.stor_examples_mismatch = t26.filter(r=>r.SIMILR_INDUTY_STOR_CO!==r.STOR_CO+r.FRC_STOR_CO).slice(0,3).map(r=>({c:r.TRDAR_CD,i:r.SVC_INDUTY_CD,S:r.SIMILR_INDUTY_STOR_CO,s:r.STOR_CO,f:r.FRC_STOR_CO}));
R.opbiz_rt_check = t26.filter(r=>r.SIMILR_INDUTY_STOR_CO>0 && r.OPBIZ_STOR_CO>0).slice(0,5).map(r=>({S:r.SIMILR_INDUTY_STOR_CO, s:r.STOR_CO, op:r.OPBIZ_STOR_CO, rt:r.OPBIZ_RT, calcS: +(r.OPBIZ_STOR_CO/r.SIMILR_INDUTY_STOR_CO*100).toFixed(1), calcs: +(r.OPBIZ_STOR_CO/r.STOR_CO*100).toFixed(1)}));
R.stores_areas26 = new Set(t26.map(r=>r.TRDAR_CD)).size; R.sales_areas26 = new Set(s26.map(r=>r.TRDAR_CD)).size;
// 5. 결측
const key = r => `${r.TRDAR_CD}|${r.SVC_INDUTY_CD}`;
const salesKeys = new Set(s26.map(key));
const storPos = t26.filter(r=>r.SIMILR_INDUTY_STOR_CO>0);
const salesInd = new Set(s26.map(r=>r.SVC_INDUTY_CD));
const storPosSalesInd = storPos.filter(r=>salesInd.has(r.SVC_INDUTY_CD));
R.missing = {
  full_grid: relm.length * Object.keys(ind).length,
  grid_salesIndustries: relm.length * salesInd.size,
  sales_rows: s26.length,
  sales_coverage_of_full_grid: +(s26.length / (relm.length * Object.keys(ind).length)).toFixed(4),
  sales_coverage_of_salesInd_grid: +(s26.length / (relm.length * salesInd.size)).toFixed(4),
  stor_rows: t26.length, stor_rows_positive: storPos.length,
  stor_pos_with_sales: storPos.filter(r=>salesKeys.has(key(r))).length,
  stor_pos_without_sales: storPos.filter(r=>!salesKeys.has(key(r))).length,
  stor_pos_in_salesInd: storPosSalesInd.length,
  stor_pos_in_salesInd_without_sales: storPosSalesInd.filter(r=>!salesKeys.has(key(r))).length,
};
const storKeyMap = new Map(t26.map(r=>[key(r), r]));
R.missing.sales_with_store_zero = s26.filter(r=>{ const t=storKeyMap.get(key(r)); return t && t.SIMILR_INDUTY_STOR_CO===0; }).length;
R.missing.sales_without_store_row = s26.filter(r=>!storKeyMap.has(key(r))).length;
// 결측 by 업종 (외식 CS1)
const byInd = {}; for (const r of storPos) { const k = r.SVC_INDUTY_CD; byInd[k] ??= {name:r.SVC_INDUTY_CD_NM, pos:0, withSales:0}; byInd[k].pos++; if (salesKeys.has(key(r))) byInd[k].withSales++; }
fs.writeFileSync('data/raw/missing_by_industry.json', JSON.stringify(byInd, null, 1));
R.missing_by_industry_CS1 = Object.entries(byInd).filter(([k])=>k.startsWith('CS1')).map(([k,v])=>`${k} ${v.name} ${v.withSales}/${v.pos}`);
// 상권 구분별 결측
R.missing_by_type = {}; const typeOf = Object.fromEntries(relm.map(r=>[r.TRDAR_CD, r.TRDAR_SE_CD_NM]));
for (const r of storPosSalesInd) { const t = typeOf[r.TRDAR_CD]; R.missing_by_type[t] ??= [0,0]; R.missing_by_type[t][1]++; if (salesKeys.has(key(r))) R.missing_by_type[t][0]++; }
// 6. 코드 변경
const codes = arr => new Set(arr.map(r=>r.TRDAR_CD));
const cmp = (a, b) => ({ a: a.size, b: b.size, same: [...a].filter(c=>b.has(c)).length, onlyA: [...a].filter(c=>!b.has(c)).length, onlyB: [...b].filter(c=>!a.has(c)).length, onlyA_sample: [...a].filter(c=>!b.has(c)).slice(0,5), onlyB_sample: [...b].filter(c=>!a.has(c)).slice(0,5) });
R.code_api20231_vs_api20262_stores = cmp(codes(t23), codes(t26));
R.code_api20231_vs_api20262_sales = cmp(codes(s23), codes(s26));
const z22 = csv('stor2022'), z23 = csv('stor2023');
const zc = (rows, yq) => new Set(rows.filter(r=>r['기준_년분기_코드']===yq).map(r=>r['상권_코드']));
R.code_zip2022Q1_vs_api20262 = cmp(zc(z22,'20221'), codes(t26));
R.code_zip2023Q1_vs_api20231 = cmp(zc(z23,'20231'), codes(t23));
R.code_zip2022Q4_vs_relm = cmp(zc(z22,'20224'), relmSet);
R.zip2022_quarters = cnt(z22, r=>r['기준_년분기_코드']); R.zip2023_quarters = cnt(z23, r=>r['기준_년분기_코드']);
// 같은 상권 같은 분기에서 zip 2022 vs API 값 비교 (재산출 여부)
// 7. 인구
R.wrc_quarters = cnt(wrc, r=>r.STDR_YYQU_CD); R.fl26_rows = fl.length;
R.wrc_same_value_check = (()=>{ const by = {}; for (const r of wrc) (by[r.TRDAR_CD] ??= {})[r.STDR_YYQU_CD] = r.TOT_WRC_POPLTN_CO; let same=0, diff=0; for (const c in by) { const q = by[c]; if (q['20251']!=null && q['20252']!=null) (q['20251']===q['20252']?same++:diff++); } return {Q1vsQ2_2025_same: same, diff}; })();
R.fl_check = (()=>{ const r = fl[0]; return { tot: r.TOT_FLPOP_CO, mf: r.ML_FLPOP_CO + r.FML_FLPOP_CO, days: ['MON','TUES','WED','THUR','FRI','SAT','SUN'].reduce((s,d)=>s+r[`${d}_FLPOP_CO`],0), tz: ['00_06','06_11','11_14','14_17','17_21','21_24'].reduce((s,t)=>s+r[`TMZON_${t}_FLPOP_CO`],0) }; })();
console.log(JSON.stringify(R, null, 1));
fs.writeFileSync('data/raw/analysis_seoul.json', JSON.stringify(R, null, 1));

