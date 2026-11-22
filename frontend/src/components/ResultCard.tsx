import { NumericText } from './NumericText';
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
export function ResultCard({
  result: r,
  shopName = '가게 결산',
  areaName,
}: {
  result: QuarterResult;
  shopName?: string;
  areaName?: string;
}) {
  return (
    <article className="result-sections">
      <section className="result receipt" aria-label="분기 결산 영수증">
        <div className="receipt-heading">
          <h2>{shopName}</h2>
          <p>
            {quarterLabel(r.quarter)} 결산{areaName ? ` · ${areaName}` : ''}
          </p>
        </div>
        <span className="stamp" aria-label={r.profit >= 0 ? '흑자' : '적자'}>
          {r.profit >= 0 ? '흑자' : '적자'}
        </span>

        <dl className="receipt-lines">
          <div>
            <dt>매출</dt>
            <dd>
              <NumericText value={money(r.revenue)} />
            </dd>
          </div>
          <div>
            <dt>방문객</dt>
            <dd>
              <NumericText value={r.visitors.toLocaleString()} />명
            </dd>
          </div>
          <div>
            <dt>객단가</dt>
            <dd>
              <NumericText value={money(r.avgTicket)} />
            </dd>
          </div>
          <div className="group-start">
            <dt>임대료</dt>
            <dd>
              <NumericText value={money(r.costs.rent)} />
            </dd>
          </div>
          <div>
            <dt>재료비</dt>
            <dd>
              <NumericText value={money(r.costs.ingredients)} />
            </dd>
          </div>
          <div>
            <dt>인건비</dt>
            <dd>
              <NumericText value={money(r.costs.labor)} />
            </dd>
          </div>
          <div>
            <dt>마케팅</dt>
            <dd>
              <NumericText value={money(r.costs.marketing)} />
            </dd>
          </div>
          <div>
            <dt>총비용</dt>
            <dd>
              <NumericText value={money(r.costs.total)} />
            </dd>
          </div>
          <div className={`profit-line ${r.profit >= 0 ? 'positive' : 'negative'}`}>
            <dt>순이익</dt>
            <dd>
              <NumericText value={money(r.profit)} />
            </dd>
          </div>
          <div className="group-start">
            <dt>잔액</dt>
            <dd>
              <NumericText value={money(r.cashAfter)} />
            </dd>
          </div>
        </dl>
      </section>
      {r.missionEvent && (
        <section className="mission-banner" role="status">
          <strong className="mission-seal">목표 달성</strong>
          <h3>
            <NumericText value={r.missionEvent.title} />
          </h3>
          <p>
            <NumericText value={r.missionEvent.text} />
          </p>
        </section>
      )}
      <div className="detail">
        <h3>상권 내 같은 업종 순위</h3>
        <p>
          {r.areaRank.of}곳 중 <b>{r.areaRank.rank}위</b>
        </p>
        <h3>능력치 변화</h3>
        <div className="stat-chips">
          {r.statChanges.map((s) => (
            <span key={s.stat}>
              {statLabels[s.stat]} <NumericText value={s.after} /> (
              <NumericText value={`${s.after - s.before >= 0 ? '+' : ''}${s.after - s.before}`} />)
            </span>
          ))}
        </div>
        <h3>상권 소식</h3>
        <p>
          개업 {r.areaNews.openings}곳 · 폐업 {r.areaNews.closings}곳
        </p>
        <small>출처: {r.areaNews.source.replace(/\b(20\d{2}[1-4])\b/g, quarterLabel)}</small>
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
