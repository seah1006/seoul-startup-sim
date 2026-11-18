import type { QuarterResult } from '../api/types';
import { money, quarterLabel } from '../components/ResultCard';
export default function History({ turns }: { turns: QuarterResult[] }) {
  const max = Math.max(1, ...turns.map((t) => Math.abs(t.profit)));
  return (
    <section className="card">
      <h2>가게의 성장 기록</h2>
      {!turns.length ? (
        <p>첫 분기를 운영하면 여기에 기록이 쌓여요.</p>
      ) : (
        <>
          <svg
            viewBox={`0 0 ${Math.max(320, turns.length * 70)} 240`}
            role="img"
            aria-label="분기별 순이익 차트"
          >
            <line x1="0" y1="120" x2="100%" y2="120" stroke="#bcc7bf" />
            {turns.map((r, i) => {
              const height = (Math.abs(r.profit) / max) * 85;
              return (
                <g key={r.turn}>
                  <title>
                    {quarterLabel(r.quarter)}: {money(r.profit)}
                  </title>
                  <rect
                    x={i * 70 + 15}
                    y={r.profit >= 0 ? 120 - height : 120}
                    width="35"
                    height={height}
                    fill={r.profit >= 0 ? '#27634c' : '#ba6557'}
                    rx="3"
                  />
                  <text x={i * 70 + 32} y="225" textAnchor="middle" fontSize="12">
                    {quarterLabel(r.quarter)}
                  </text>
                </g>
              );
            })}
          </svg>
          <ul>
            {turns.map((r) => (
              <li key={r.turn}>
                {quarterLabel(r.quarter)} · 순이익 {money(r.profit)}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
