import { NumericText } from '../components/NumericText';
import { useEffect, useState } from 'react';
import { api, useMock } from '../api/client';
import type { Area, GameState, Industry, Report, Stores } from '../api/types';
import { formatDistance, money } from '../components/ResultCard';
import { AreaMap } from '../components/AreaMap';
export default function ShopSetup({
  game,
  onGame,
}: {
  game: GameState;
  onGame: (g: GameState) => void;
}) {
  const [query, setQuery] = useState('');
  const [areas, setAreas] = useState<Area[]>([]);
  const [area, setArea] = useState<Area | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [industries, setIndustries] = useState<Industry[]>([]);
  const [service, setService] = useState('CS100010');
  const [position, setPosition] = useState<{ lng: number; lat: number } | null>(null);
  const [stores, setStores] = useState<Stores | null>(null);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api
      .industries()
      .then(setIndustries)
      .catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    setReport(null);
    if (!area) return;
    let active = true;
    api
      .report(area.areaCode, game.currentQuarter)
      .then((r) => {
        if (active) setReport(r);
      })
      .catch((e) => setError(e.message));
    return () => {
      active = false;
    };
  }, [area, game.currentQuarter]);
  useEffect(() => {
    setStores(null);
    if (!area || !position) return;
    let active = true;
    api
      .stores(area.areaCode, service, position.lng, position.lat)
      .then((r) => {
        if (active) setStores(r);
      })
      .catch((e) => setError(e.message));
    return () => {
      active = false;
    };
  }, [area, position, service]);
  const metric = report?.industries.find((i) => i.serviceCode === service);
  function choose(a: Area) {
    setArea(a);
    setPosition(null);
    setStores(null);
  }
  async function open() {
    if (!area || !position) return;
    setBusy(true);
    try {
      onGame(
        await api.shop(game.gameId, {
          areaCode: area.areaCode,
          serviceCode: service,
          ...position,
          shopName: name.trim(),
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <h1>첫 가게, 어느 동네가 좋을까요?</h1>
      <div className="grid map-layout">
        <section>
          <form
            className="search"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!query.trim()) {
                setAreas([]);
                return;
              }
              try {
                setAreas(await api.search(query.trim()));
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <input
              aria-label="상권 검색"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="상권 이름 검색"
            />
            <button>검색</button>
          </form>
          <div className="choices">
            {areas.map((a) => (
              <button key={a.areaCode} onClick={() => choose(a)}>
                {a.name}
              </button>
            ))}
          </div>
          <AreaMap
            area={area}
            position={position}
            stores={stores}
            onArea={choose}
            onPosition={setPosition}
          />
        </section>
        <section className="card">
          <p>상권 리포트 {useMock ? '· 목 데이터' : ''}</p>
          <h2>{area?.name || '동네를 선택해 주세요'}</h2>
          {report && (
            <>
              <dl className="report-values">
                <div>
                  <dt>유동인구</dt>
                  <dd>
                    {report.floatingPopulationTopPct != null
                      ? `서울 상권 중 상위 ${report.floatingPopulationTopPct}%`
                      : report.floatingPopulation != null
                        ? '집계됨'
                        : '자료 없음'}
                  </dd>
                </div>
              </dl>
              <small>길단위인구 분기 집계를 서울 1,650개 상권과 비교한 값</small>
            </>
          )}
          <label>
            업종
            <select value={service} onChange={(e) => setService(e.target.value)}>
              {industries.map((i) => (
                <option key={i.serviceCode} value={i.serviceCode}>
                  {i.name}
                </option>
              ))}
            </select>
          </label>
          {metric && (
            <dl className="report-values">
              <div>
                <dt>점포 / 프랜차이즈</dt>
                <dd>
                  <NumericText value={metric.storeCount} />곳 /{' '}
                  <NumericText value={metric.franchiseCount} />곳
                </dd>
              </div>
              <div>
                <dt>개업률 / 폐업률</dt>
                <dd>
                  {metric.openingRatePct === null ? (
                    '자료 없음'
                  ) : (
                    <NumericText value={`${metric.openingRatePct}%`} />
                  )}{' '}
                  /{' '}
                  {metric.closingRatePct === null ? (
                    '자료 없음'
                  ) : (
                    <NumericText value={`${metric.closingRatePct}%`} />
                  )}
                </dd>
              </div>
              <div>
                <dt>분기 매출</dt>
                <dd>
                  {metric.quarterSales === null ? (
                    '자료 없음'
                  ) : (
                    <NumericText value={money(metric.quarterSales)} />
                  )}{' '}
                  {metric.salesEstimated && <small>추정치</small>}
                </dd>
              </div>
            </dl>
          )}
          <label>
            가게 이름
            <input
              value={name}
              maxLength={50}
              onChange={(e) => setName(e.target.value)}
              placeholder="나의 첫 가게"
            />
          </label>
          {area && (
            <button className="block-action" onClick={() => setPosition(area.centroid)}>
              선택 상권 중심점에 위치 지정
            </button>
          )}
          {position && (
            <p>
              선택 위치 {position.lat.toFixed(5)}, {position.lng.toFixed(5)}
            </p>
          )}
          {stores && (
            <>
              <h3>반경 300m 경쟁 점포 {stores.count}곳</h3>
              <table className="competitor-table">
                <thead>
                  <tr>
                    <th scope="col">이름</th>
                    <th scope="col" className="numeric">
                      거리
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {[...stores.stores]
                    .sort((a, b) => a.distanceM - b.distanceM)
                    .map((s) => (
                      <tr key={s.storeId}>
                        <td>{s.name}</td>
                        <td className="numeric">
                          <NumericText value={formatDistance(s.distanceM)} />
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </>
          )}
          <small>
            출처: 서울시 상권분석서비스 / 소상공인 상가정보{useMock ? ' (현재 목 예시)' : ''}
          </small>
          <button
            className="primary full"
            disabled={!area || !position || !name.trim() || !stores || busy}
            onClick={open}
          >
            {busy ? '개업 중…' : '이곳에서 창업하기'}
          </button>
        </section>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </>
  );
}
