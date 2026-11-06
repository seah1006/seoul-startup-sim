// EPSG:5181 (Korea 2000 / Central Belt, FE=200000, FN=500000) -> WGS84 역변환 검산 (Snyder TM 공식)
import fs from 'node:fs';
const a = 6378137, f = 1 / 298.257222101, e2 = f * (2 - f), ep2 = e2 / (1 - e2), k0 = 1, lat0 = 38 * Math.PI / 180, lon0 = 127 * Math.PI / 180, FE = 200000, FN = 500000;
const Mf = phi => a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * Math.sin(2 * phi) + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * Math.sin(4 * phi) - (35 * e2 ** 3 / 3072) * Math.sin(6 * phi));
export function tm2wgs(x, y) {
  const M = Mf(lat0) + (y - FN) / k0; const mu = M / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256)); const e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
  const p1 = mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * Math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * Math.sin(4 * mu) + (151 * e1 ** 3 / 96) * Math.sin(6 * mu);
  const C1 = ep2 * Math.cos(p1) ** 2, T1 = Math.tan(p1) ** 2, N1 = a / Math.sqrt(1 - e2 * Math.sin(p1) ** 2), R1 = a * (1 - e2) / (1 - e2 * Math.sin(p1) ** 2) ** 1.5, D = (x - FE) / (N1 * k0);
  const lat = p1 - (N1 * Math.tan(p1) / R1) * (D ** 2 / 2 - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * ep2) * D ** 4 / 24 + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * ep2 - 3 * C1 ** 2) * D ** 6 / 720);
  const lon = lon0 + (D - (1 + 2 * T1 + C1) * D ** 3 / 6 + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * ep2 + 24 * T1 ** 2) * D ** 5 / 120) / Math.cos(p1);
  return [lon * 180 / Math.PI, lat * 180 / Math.PI];
}
const relm = JSON.parse(fs.readFileSync('data/raw/TbgisTrdarRelm_all.json', 'utf8'));
for (const nm of ['망원역', '성수역', '강남역', '서울시청']) { const r = relm.find(r => r.TRDAR_CD_NM.includes(nm)); if (r) console.log(r.TRDAR_CD, r.TRDAR_CD_NM, r.XCNTS_VALUE, r.YDNTS_VALUE, '->', tm2wgs(+r.XCNTS_VALUE, +r.YDNTS_VALUE).map(v => v.toFixed(5)).join(', ')); }
const xs = relm.map(r => tm2wgs(+r.XCNTS_VALUE, +r.YDNTS_VALUE));
console.log('WGS84 bbox lon', Math.min(...xs.map(p => p[0])).toFixed(4), Math.max(...xs.map(p => p[0])).toFixed(4), 'lat', Math.min(...xs.map(p => p[1])).toFixed(4), Math.max(...xs.map(p => p[1])).toFixed(4));
