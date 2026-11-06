// 최소 CFB(OLE) + HWP5 BodyText 텍스트 추출기
const fs = require('fs'), zlib = require('zlib');
const dirArg = process.argv[2] || 'data/raw/guide';
const file = fs.readdirSync(dirArg).find(f => f.endsWith('.hwp'));
const b = fs.readFileSync(dirArg + '/' + file);
const ss = 1 << b.readUInt16LE(30), mss = 1 << b.readUInt16LE(32);
const nFat = b.readUInt32LE(44), dirStart = b.readUInt32LE(48), miniCut = b.readUInt32LE(56), miniFatStart = b.readUInt32LE(60), difStart = b.readUInt32LE(68);
const sec = n => b.subarray(512 + n * ss, 512 + (n + 1) * ss);
let fatSecs = []; for (let i = 0; i < 109 && fatSecs.length < nFat; i++) fatSecs.push(b.readUInt32LE(76 + i * 4));
let d = difStart; while (fatSecs.length < nFat && d < 0xFFFFFFFA) { const s = sec(d); for (let i = 0; i < ss / 4 - 1 && fatSecs.length < nFat; i++) fatSecs.push(s.readUInt32LE(i * 4)); d = s.readUInt32LE(ss - 4); }
const fat = []; for (const fs_ of fatSecs) { const s = sec(fs_); for (let i = 0; i < ss / 4; i++) fat.push(s.readUInt32LE(i * 4)); }
const chain = st => { const out = []; while (st < 0xFFFFFFFA) { out.push(sec(st)); st = fat[st]; } return Buffer.concat(out); };
const dir = chain(dirStart); const ents = [];
for (let o = 0; o < dir.length; o += 128) { const nl = dir.readUInt16LE(o + 64); ents.push({ name: dir.toString('utf16le', o, o + Math.max(0, nl - 2)), type: dir[o + 66], start: dir.readUInt32LE(o + 116), size: dir.readUInt32LE(o + 120) }); }
const root = ents[0]; const ministream = chain(root.start);
const minifat = []; { const m = chain(miniFatStart); for (let i = 0; i < m.length / 4; i++) minifat.push(m.readUInt32LE(i * 4)); }
const read = e => { if (e.size < miniCut) { const out = []; let s = e.start; while (s < 0xFFFFFFFA) { out.push(ministream.subarray(s * mss, (s + 1) * mss)); s = minifat[s]; } return Buffer.concat(out).subarray(0, e.size); } return chain(e.start).subarray(0, e.size); };
const hdr = read(ents.find(e => e.name === 'FileHeader')); const compressed = hdr[36] & 1;
const secs = ents.filter(e => /^Section\d+$/.test(e.name)).sort((a, b2) => +a.name.slice(7) - +b2.name.slice(7));
const lines = [];
for (const e of secs) { let data = read(e); if (compressed) data = zlib.inflateRawSync(data); let i = 0;
  while (i < data.length) { const h = data.readUInt32LE(i); i += 4; const tag = h & 0x3ff; let size = (h >>> 20) & 0xfff; if (size === 0xfff) { size = data.readUInt32LE(i); i += 4; }
    if (tag === 67) { let t = ''; const seg = data.subarray(i, i + size); for (let k = 0; k < seg.length; k += 2) { const c = seg.readUInt16LE(k); if ([1,2,3,11,12,14,15,16,17,18,21,22,23].includes(c)) { k += 14; continue; } if ([4,5,6,7,8,9,19,20].includes(c)) { k += 14; continue; } if (c < 32) { t += ' '; continue; } t += String.fromCharCode(c); } lines.push(t.trim()); }
    i += size; } }
fs.writeFileSync(dirArg + '/guide.txt', lines.join('\n'));
console.log('sections', secs.length, 'lines', lines.length);

