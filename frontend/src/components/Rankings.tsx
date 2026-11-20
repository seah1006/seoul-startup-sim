import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Area, Industry, RankingKind, Rankings } from '../api/types';
import { money } from './ResultCard';
export function rankingDates(now = new Date()) {
  return Array.from({ length: 7 }, (_, i) =>
    new Date(now.getTime() + 9 * 3600000 - i * 86400000).toISOString().slice(0, 10),
  );
}
function RankingTable({ data }: { data: Rankings }) {
  return !data.entries.length ? (
    <p>이 날짜의 창업 기록이 아직 없어요.</p>
  ) : (
    <div className="table-scroll">
      <table className="ranking-table">
        <thead>
          <tr>
            <th>순위</th>
            <th>사장님 / 가게</th>
            <th>동네 / 업종</th>
            <th>생존</th>
            <th>누적 순이익</th>
          </tr>
        </thead>
        <tbody>
          {data.entries.map((e) => (
            <tr key={e.gameId} className="rank-row">
              <td className="rk-rank">{e.rank}위</td>
              <td className="rk-owner">
                {e.ownerName}
                <br />
                <small>
                  {e.shopName} · {e.nickname}
                </small>
              </td>
              <td className="rk-area">
                {e.areaName}
                <br />
                <small>{e.serviceName}</small>
              </td>
              <td className="rk-survive">{e.survivedQuarters}분기</td>
              <td className="rk-profit">{money(e.totalProfit)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function RankingPreview() {
  const [data, setData] = useState<Rankings | null>(null),
    [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    api
      .rankings(undefined, 'ALL', undefined, 3)
      .then((r) => {
        if (active) setData(r);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, []);
  return (
    <article className="card">
      <h2>오늘의 랭킹</h2>
      {data ? <RankingTable data={data} /> : <p>{error || '랭킹을 불러오는 중…'}</p>}
      <Link className="button" to="/rankings">
        전체 랭킹 보기
      </Link>
    </article>
  );
}
export function RankingsScreen() {
  const dates = rankingDates();
  const [date, setDate] = useState(dates[0]),
    [kind, setKind] = useState<RankingKind>('ALL'),
    [key, setKey] = useState(''),
    [industries, setIndustries] = useState<Industry[]>([]),
    [areas, setAreas] = useState<Area[]>([]),
    [query, setQuery] = useState(''),
    [data, setData] = useState<Rankings | null>(null),
    [error, setError] = useState('');
  useEffect(() => {
    api
      .industries()
      .then(setIndustries)
      .catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    let active = true;
    if (kind !== 'ALL' && !key) return;
    api
      .rankings(date, kind, key)
      .then((r) => {
        if (active) setData(r);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [date, kind, key]);
  function changeKind(k: RankingKind) {
    setKind(k);
    setKey('');
    setData(null);
    setError('');
  }
  async function search() {
    try {
      setAreas(await api.search(query));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <>
      <h1>서울 사장님 랭킹</h1>
      <section className="card">
        <label>
          조회 날짜 (최근 7일)
          <select
            value={date}
            onChange={(e) => {
              setDate(e.target.value);
              setData(null);
              setError('');
            }}
          >
            {dates.map((d) => (
              <option key={d}>{d}</option>
            ))}
          </select>
        </label>
        <div className="choices" role="tablist" aria-label="랭킹 종류">
          {(['ALL', 'INDUSTRY', 'AREA'] as RankingKind[]).map((k, i) => (
            <button key={k} role="tab" aria-selected={kind === k} onClick={() => changeKind(k)}>
              {['전체', '업종별', '상권별'][i]}
            </button>
          ))}
        </div>
        {kind === 'INDUSTRY' && (
          <label>
            업종
            <select
              value={key}
              onChange={(e) => {
                setKey(e.target.value);
                setData(null);
              }}
            >
              <option value="">업종을 선택해 주세요</option>
              {industries.map((i) => (
                <option key={i.serviceCode} value={i.serviceCode}>
                  {i.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {kind === 'AREA' && (
          <>
            <form
              className="search"
              onSubmit={(e) => {
                e.preventDefault();
                void search();
              }}
            >
              <input
                aria-label="랭킹 상권 검색"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="상권 이름"
              />
              <button>상권 검색</button>
            </form>
            <div className="choices">
              {areas.map((a) => (
                <button
                  key={a.areaCode}
                  className={key === a.areaCode ? 'selected' : ''}
                  onClick={() => {
                    setKey(a.areaCode);
                    setData(null);
                  }}
                >
                  {a.name}
                </button>
              ))}
            </div>
          </>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {data ? (
          <>
            <RankingTable data={data} />
            <small>
              갱신: {new Date(data.refreshedAt).toLocaleString('ko-KR')} · 30분마다 갱신
            </small>
          </>
        ) : (
          <p>{kind !== 'ALL' && !key ? '조회할 항목을 선택해 주세요.' : '랭킹을 불러오는 중…'}</p>
        )}
      </section>
    </>
  );
}
