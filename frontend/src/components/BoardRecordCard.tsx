import { NumericText } from './NumericText';
import { useMock } from '../api/client';
import type { GameSummary } from '../api/types';
import { money } from './ResultCard';

const ending = { CLOSED: '폐업', SOLD: '매각', LONG_RUNNING: '장기 운영' };
type CardData = Omit<GameSummary, 'bestQuarterProfit' | 'shopCount'>;
export function BoardRecordCard({
  record,
  preview = false,
}: {
  record: CardData;
  preview?: boolean;
}) {
  return (
    <section
      className="board-record-card"
      aria-label={preview ? '엔딩 인증 카드 미리보기' : '엔딩 인증 카드'}
    >
      <p>{preview ? '엔딩 인증 카드 미리보기' : '엔딩 인증'}</p>
      <h2>{record.shopName}</h2>
      <p>
        {record.ownerName} · {ending[record.endingType]}
      </p>
      <dl>
        <dt>업종</dt>
        <dd>{record.serviceName}</dd>
        <dt>상권</dt>
        <dd>{record.areaName}</dd>
        <dt>생존 분기</dt>
        <dd>{record.survivedQuarters}분기</dd>
        <dt>누적 순이익</dt>
        <dd><NumericText value={money(record.totalProfit)} /></dd>
      </dl>
      {!preview &&
        (useMock ? (
          <p>공유 페이지는 서버 연결 시 열려요</p>
        ) : (
          <a href={record.shareUrl}>공유 카드 보기</a>
        ))}
    </section>
  );
}
