import { seoul } from './lib.mjs';
for (const s of process.argv.slice(2)) {
  const r = await seoul(s, 1, 1);
  const k = Object.keys(r)[0];
  console.log(s, k, JSON.stringify(r[k]?.list_total_count ?? r[k] ?? r).slice(0,300), JSON.stringify(r[k]?.RESULT||''));
  if (r[k]?.row) console.log(JSON.stringify(r[k].row[0]));
}
