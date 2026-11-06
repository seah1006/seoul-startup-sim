// 영역-상권 SHP/DBF 헤더 분석 (외부 라이브러리 없이)
import fs from 'node:fs';
const dir = process.argv[2] || 'data/raw/area';
const shp = fs.readFileSync(`${dir}/trdar.shp`);
const shapeType = shp.readInt32LE(32);
const bbox = [36,44,52,60].map(o => shp.readDoubleLE(o));
console.log('shapeType', shapeType, '(5=Polygon)', 'bbox', bbox);
// records
let off = 100, n = 0, parts = 0, multi = 0, types = {};
while (off < shp.length) {
  const len = shp.readInt32BE(off + 4) * 2; const st = shp.readInt32LE(off + 8);
  types[st] = (types[st]||0)+1;
  if (st === 5) { const np = shp.readInt32LE(off + 8 + 36); parts += np; if (np > 1) multi++; }
  off += 8 + len; n++;
}
console.log('records', n, 'types', types, 'totalParts', parts, 'multiPartRecords', multi);
const enc = fs.readFileSync(`${dir}/trdar.cpg`, 'utf8').trim();
const dbf = fs.readFileSync(`${dir}/trdar.dbf`);
const nrec = dbf.readInt32LE(4), hlen = dbf.readInt16LE(8), rlen = dbf.readInt16LE(10);
const fields = []; for (let p = 32; dbf[p] !== 0x0d; p += 32) fields.push({ name: dbf.toString('latin1', p, p+11).replace(/\0.*$/,''), type: String.fromCharCode(dbf[p+11]), len: dbf[p+16] });
console.log('dbf records', nrec, 'encoding(cpg)', enc, 'fields', JSON.stringify(fields));
const dec = new TextDecoder(/utf/i.test(enc) ? 'utf-8' : 'euc-kr');
const rows = [];
for (let i = 0; i < nrec; i++) { const base = hlen + i*rlen + 1; let p = base; const r = {}; for (const f of fields) { r[f.name] = dec.decode(dbf.subarray(p, p+f.len)).trim(); p += f.len; } rows.push(r); }
console.log('first rows', JSON.stringify(rows.slice(0,3)));
fs.writeFileSync(`${dir}/trdar_dbf.json`, JSON.stringify(rows));
