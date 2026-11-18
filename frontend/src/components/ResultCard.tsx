import type { QuarterResult } from '../api/types';
import { statLabels } from '../api/types';
export const money = (n: number) => {
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  if (abs < 10000) return `${sign}${abs.toLocaleString('ko-KR')}원`;
  const man = Math.round(abs / 10000);
  return man >= 10000
    ? `${sign}${Math.floor(man / 10000).toLocaleString('ko-KR')}억${man % 10000 ? ` ${(man % 10000).toLocaleString('ko-KR')}만` : ''} 원`
    : `${sign}${man.toLocaleString('ko-KR')}만 원`;
};
export const formatDistance = (m: number) =>
  m >= 1000 ? `${(Math.round(m / 100) / 10).toLocaleString('ko-KR')}km` : `${Math.round(m)}m`;
export const quarterLabel = (q: string) => `${q.slice(0, 4)}년 ${q.slice(4)}분기`;
export function ResultCard({ result: r }: { result: QuarterResult }) {
  return (
    <article className="card result">
      <p className="eyebrow">분기 결산 · {quarterLabel(r.quarter)}</p>
      <h2>{quarterLabel(r.quarter)}가 끝났어요</h2>
      <p>{r.headline.label}</p>
      <strong className="headline">{money(r.headline.amount)}</strong>
      <div className="metrics">
        <div>
          매출<strong>{money(r.revenue)}</strong>
        </div>
        <div>
          방문객<strong>{r.visitors.toLocaleString()}명</strong>
        </div>
        <div>
          객단가<strong>{money(r.avgTicket)}</strong>
        </div>
      </div>
      <div className="detail">
        <h3>상권 내 같은 업종 순위</h3>
        <p>
          {r.areaRank.of}곳 중 <b>{r.areaRank.rank}위</b>
        </p>
        <h3>능력치 변화</h3>
        <div className="stat-chips">
          {r.statChanges.map((s) => (
            <span key={s.stat}>
              {statLabels[s.stat]} {s.after} ({s.after - s.before >= 0 ? '+' : ''}
              {s.after - s.before})
            </span>
          ))}
        </div>
        <h3>상권 소식</h3>
        <p>
          개업 {r.areaNews.openings}곳 · 폐업 {r.areaNews.closings}곳
        </p>
        <small>출처: {r.areaNews.source.replace(/\b(20\d{2}[1-4])\b/g, quarterLabel)}</small>
        <h3>비용과 잔액</h3>
        <p>
          총비용 {money(r.costs.total)} · 잔액 {money(r.cashAfter)}
        </p>
        <details>
          <summary>비용 자세히</summary>
          <p>
            임대료 {money(r.costs.rent)} / 재료비 {money(r.costs.ingredients)} / 인건비{' '}
            {money(r.costs.labor)} / 마케팅 {money(r.costs.marketing)}
          </p>
        </details>
      </div>
      {r.moment && (
        <section className="moment">
          <h3>{r.moment.title}</h3>
          <p>{r.moment.text}</p>
          <small>
            {statLabels[r.moment.stat]} · {r.moment.success ? '성공' : '실패'}
          </small>
        </section>
      )}
      {r.reactions.length > 0 && (
        <section aria-label="동네 반응">
          <h3>동네 반응</h3>
          {r.reactions.map((reaction, i) => (
            <article
              key={i}
              className={reaction.kind === 'COMMUNITY' ? 'reaction community' : 'reaction review'}
            >
              <strong>{reaction.author}</strong>
              {reaction.kind === 'REVIEW' && (
                <p aria-label={`별점 ${reaction.rating}점`}>
                  {'★'.repeat(Math.max(0, Math.min(5, reaction.rating || 0)))}
                </p>
              )}
              <p>{reaction.text}</p>
              <small>공감 {reaction.likes}</small>
            </article>
          ))}
        </section>
      )}
      <aside className="notice">
        {r.dataNotes.map((n) => (
          <p key={n}>{n}</p>
        ))}
        <p>동네 반응은 게임 속 가상 반응입니다.</p>
      </aside>
    </article>
  );
}
