import fs from 'node:fs';
import { sdsc } from './sdsc.mjs';
const r = await sdsc('smallUpjongList', { type: 'json' });
fs.writeFileSync('data/raw/sbiz_small.json', JSON.stringify(r.body.body.items));
fs.writeFileSync('data/raw/sbiz_small_url.txt', r.url);
console.log(r.body.body.items.length, new Set(r.body.body.items.map(i=>i.indsMclsCd)).size, new Set(r.body.body.items.map(i=>i.indsLclsCd)).size, [...new Set(r.body.body.items.map(i=>i.stdrDt))]);
