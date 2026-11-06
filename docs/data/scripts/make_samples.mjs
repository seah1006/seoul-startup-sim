// docs/data/samples/ 생성: 실제 응답에서 5~20행. 키는 {KEY}로 가린다.
import fs from 'node:fs';
import { seoul, seoulUrlMasked, mask } from './lib.mjs';
import { sdsc } from './sdsc.mjs';
const out = 'docs/data/samples';
const save = (name, obj) => { const s = mask(JSON.stringify(obj, null, 1)); fs.writeFileSync(`${out}/${name}`, s + '\n'); console.log(name, s.length); };
const fetchedAt = new Date().toISOString();
for (const [svc, n, args] of [['TbgisTrdarRelm', 10, []], ['VwsmTrdarSelngQq', 5, ['20262']], ['VwsmTrdarStorQq', 10, ['20262']], ['VwsmTrdarFlpopQq', 5, ['20262']], ['VwsmTrdarWrcPopltnQq', 5, []], ['VwsmMegaSelngW', 5, []], ['VwsmMegaStorW', 5, []]]) {
  const r = await seoul(svc, 1, n, ...args);
  save(`seoul_${svc}${args[0] ? '_' + args[0] : ''}.json`, { request: seoulUrlMasked(svc, 1, n, ...args), fetchedAt, response: r });
}
const cases = [
  ['sbiz_storeListInDong.json', 'storeListInDong', { divId: 'adongCd', key: '11440690', numOfRows: 5, pageNo: 1, type: 'json' }],
  ['sbiz_storeListInRadius.json', 'storeListInRadius', { radius: 300, cx: 126.9055, cy: 37.5524, indsSclsCd: 'I21201', numOfRows: 5, pageNo: 1, type: 'json' }],
  ['sbiz_storeListByDate.json', 'storeListByDate', { key: '20260930', numOfRows: 5, pageNo: 1, type: 'json' }],
  ['sbiz_smallUpjongList.json', 'smallUpjongList', { indsLclsCd: 'I2', type: 'json' }],
];
for (const [file, op, p] of cases) { const r = await sdsc(op, p); save(file, { request: r.url, fetchedAt, httpStatus: r.status, response: r.body }); }
const err = await sdsc('storeListByDate', { key: '20261001', numOfRows: 1, pageNo: 1, type: 'json' });
save('sbiz_storeListByDate_nodata.json', { request: err.url, fetchedAt, httpStatus: err.status, response: err.body ?? err.raw });
// SHP 속성 샘플과 .prj
const dbf = JSON.parse(fs.readFileSync('data/raw/area/trdar_dbf.json', 'utf8'));
save('seoul_area_shp_dbf.json', { source: '서울시 상권분석서비스(영역-상권).zip (OA-15560, 수정일 2023.10.23)', prj: fs.readFileSync('data/raw/area/trdar.prj', 'utf8').trim(), cpg: 'UTF-8', shapeType: 'Polygon(5), 1650 records', rows: dbf.slice(0, 5) });
// 서비스 업종 100개 목록
const ind = JSON.parse(fs.readFileSync('data/raw/service_industries.json', 'utf8'));
fs.writeFileSync('docs/data/service_industries.csv', '\uFEFFservice_code,service_name,category,in_sales_20262\n' + ind.map(i => `${i.code},${i.name},${{ CS1: '외식', CS2: '서비스', CS3: '소매' }[i.code.slice(0, 3)]},${i.inSales ? 'Y' : 'N'}`).join('\n') + '\n');
