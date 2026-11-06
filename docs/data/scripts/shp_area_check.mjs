// RELM_AR 단위 확인: SHP 폴리곤 면적(EPSG:5181, m 단위 좌표)의 신발끈 공식 결과와 비교, 다중 파트 구조(구멍/분리) 확인
import fs from 'node:fs';
const shp = fs.readFileSync('data/raw/area/trdar.shp'); const dbf = JSON.parse(fs.readFileSync('data/raw/area/trdar_dbf.json', 'utf8'));
let off = 100, i = 0; const ratios = []; let holes = 0, multiOuter = 0;
while (off < shp.length) {
  const len = shp.readInt32BE(off + 4) * 2; const b = off + 8; const np = shp.readInt32LE(b + 36), npts = shp.readInt32LE(b + 40);
  const parts = []; for (let k = 0; k < np; k++) parts.push(shp.readInt32LE(b + 44 + 4 * k));
  const pb = b + 44 + 4 * np; let area = 0, outer = 0, inner = 0;
  for (let k = 0; k < np; k++) { const s = parts[k], e = k + 1 < np ? parts[k + 1] : npts; let a = 0; for (let j = s; j < e - 1; j++) { const x1 = shp.readDoubleLE(pb + 16 * j), y1 = shp.readDoubleLE(pb + 16 * j + 8), x2 = shp.readDoubleLE(pb + 16 * (j + 1)), y2 = shp.readDoubleLE(pb + 16 * (j + 1) + 8); a += x1 * y2 - x2 * y1; } a /= 2; if (a < 0) outer++; else inner++; area += -a; }
  if (inner) holes++; if (outer > 1) multiOuter++;
  ratios.push(area / +dbf[i].RELM_AR); off = b + len; i++;
}
ratios.sort((a, b) => a - b);
console.log('records', i, 'area/RELM_AR quantiles', [0, .05, .5, .95, 1].map(p => ratios[Math.floor(p * (ratios.length - 1))].toFixed(4)), 'recordsWithHoles', holes, 'recordsWithMultipleOuterRings', multiOuter);
