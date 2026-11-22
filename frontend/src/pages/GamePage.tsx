import { NumericText } from '../components/NumericText';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, advanceWithRecovery } from '../api/client';
import type { Decision, GameState, QuarterResult, MissionState } from '../api/types';
import { money, quarterLabel, ResultCard } from '../components/ResultCard';
import { TurningPointCard, EndingScreen, SellButton } from '../components/GameMilestones';
import ShopSetup from './ShopSetup';
import History from './History';
export default function Game() {
  const { id } = useParams();
  const [game, setGame] = useState<GameState | null>(null);
  const [turns, setTurns] = useState<QuarterResult[]>([]);
  const [result, setResult] = useState<QuarterResult | null>(null);
  const [tab, setTab] = useState<'play' | 'history'>('play');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [decision, setDecision] = useState<Decision>({
    priceLevel: 'NORMAL',
    openHours: 10,
    staff: 2,
    marketingBudget: 3000000,
    ownerActivity: 'MENU_DEV',
  });
  useEffect(() => {
    if (id)
      api
        .game(id)
        .then(async (g) => {
          setGame(g);
          setTurns(await api.turns(g.gameId));
        })
        .catch((e) => setError(e.message));
  }, [id]);
  async function next() {
    if (!game) return;
    setBusy(true);
    setError('');
    try {
      await api.decision(game, decision);
      const r = await advanceWithRecovery(game, setGame);
      setGame(r.game);
      setResult(r.result);
      setTurns(await api.turns(game.gameId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (!game) return <p role="status">{error || '가게를 준비하고 있어요…'}</p>;
  if (!game.shop) return <ShopSetup game={game} onGame={setGame} />;
  return (
    <>
      <div className="section-title">
        <div>
          <p>
            {quarterLabel(game.currentQuarter)} · {game.owner.name} 사장님
          </p>
          <h1>{game.shop.shopName}</h1>
          <MissionProgress mission={game.mission} />
          <p>
            {game.shop.areaName} · {game.shop.serviceName} · 보유금{' '}
            <NumericText value={money(game.cash)} />
          </p>
        </div>
        <div className="game-tabs underline-tabs" aria-label="게임 화면">
          <button aria-pressed={tab === 'play'} onClick={() => setTab('play')}>
            운영
          </button>
          <button aria-pressed={tab === 'history'} onClick={() => setTab('history')}>
            기록
          </button>
        </div>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {tab === 'history' ? (
        <History turns={turns} />
      ) : result && game.status === 'ENDED' ? (
        <>
          <ResultCard result={result} shopName={game.shop.shopName} areaName={game.shop.areaName} />
          <EndingScreen game={game} />
        </>
      ) : result ? (
        <>
          <ResultCard result={result} shopName={game.shop.shopName} areaName={game.shop.areaName} />
          <button className="primary" onClick={() => setResult(null)}>
            다음 운영 계획
          </button>
        </>
      ) : game.status === 'ENDED' ? (
        <EndingScreen game={game} />
      ) : game.pendingTurningPoint ? (
        <TurningPointCard game={game} onGame={setGame} />
      ) : (
        <section className="card">
          <h2>이번 분기는 어떻게 운영할까요?</h2>
          <form
            id="operation-decision"
            className="operation-form"
            onSubmit={(e) => {
              e.preventDefault();
              void next();
            }}
          >
            <div className="grid two">
              <label>
                가격 수준
                <select
                  value={decision.priceLevel}
                  onChange={(e) =>
                    setDecision({
                      ...decision,
                      priceLevel: e.target.value as Decision['priceLevel'],
                    })
                  }
                >
                  <option value="LOW">합리적인 가격</option>
                  <option value="NORMAL">기본 가격</option>
                  <option value="HIGH">프리미엄 가격</option>
                </select>
              </label>
              <label>
                하루 영업시간
                <input
                  type="number"
                  min="6"
                  max="16"
                  required
                  value={decision.openHours}
                  onChange={(e) => setDecision({ ...decision, openHours: Number(e.target.value) })}
                />
              </label>
              <label>
                직원 수
                <input
                  type="number"
                  min="0"
                  max="10"
                  required
                  value={decision.staff}
                  onChange={(e) => setDecision({ ...decision, staff: Number(e.target.value) })}
                />
              </label>
              <label>
                분기 마케팅 예산 (원)
                <input
                  type="number"
                  min="0"
                  step="10000"
                  required
                  value={decision.marketingBudget}
                  onChange={(e) =>
                    setDecision({ ...decision, marketingBudget: Number(e.target.value) })
                  }
                />
              </label>
              <label>
                사장님 활동
                <select
                  value={decision.ownerActivity}
                  onChange={(e) =>
                    setDecision({
                      ...decision,
                      ownerActivity: e.target.value as Decision['ownerActivity'],
                    })
                  }
                >
                  <option value="MENU_DEV">메뉴 개발</option>
                  <option value="REGULARS">단골 관리</option>
                  <option value="STUDY">경영 공부</option>
                  <option value="REST">충분한 휴식</option>
                </select>
              </label>
            </div>
          </form>
          <div className="operation-actions">
            <button type="submit" form="operation-decision" className="primary" disabled={busy}>
              {busy ? '분기를 결산하는 중…' : '결정 저장하고 다음 분기'}
            </button>
            <SellButton game={game} onGame={setGame} />
          </div>
        </section>
      )}
    </>
  );
}

export function MissionProgress({ mission }: { mission: MissionState }) {
  if (!mission) return null;
  const { current, target, unit, label } = mission.progress;
  const percent = mission.achieved
    ? 100
    : current === null
      ? 0
      : unit === 'RANK'
        ? current <= target
          ? 100
          : (target / current) * 100
        : Math.max(0, Math.min(100, (current / target) * 100));
  return (
    <div className="mission-progress" aria-label="목표 진행">
      <p>
        목표 · <NumericText value={mission.title} /> <NumericText value={label} />{' '}
        {mission.achieved && <strong className="mission-seal">달성</strong>}
      </p>
      <div
        role="progressbar"
        aria-label={mission.title}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(percent)}
        aria-valuetext={label}
      >
        <span style={{ width: percent + '%' }} />
      </div>
    </div>
  );
}
