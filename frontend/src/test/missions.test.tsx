import { it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NewOwner from '../pages/NewOwner';
import { MissionProgress } from '../pages/GamePage';
import { TurningPointCard } from '../components/GameMilestones';
import { ResultCard } from '../components/ResultCard';
import { api } from '../api/client';
import { mockRequest, sampleResult, missions } from '../api/mock';
import type { GameState } from '../api/types';
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});
const create = () =>
  mockRequest<GameState>('/games', 'POST', {
    ownerName: '목표 테스트',
    background: 'LOCAL',
    candidateId: 'c1',
    candidateSeed: 123,
    focusStats: [],
    missionCode: 'BLACK_4',
  });
it('목표 목록은 계약 순서 5개이며 잘못된 목표는 거부한다', async () => {
  expect(await api.missions()).toEqual(missions);
  await expect(mockRequest('/games', 'POST', { missionCode: 'INVALID' })).rejects.toMatchObject({
    code: 'VALIDATION_FAILED',
  });
});
it('선택한 목표가 생성 요청에 실린다', async () => {
  const g = await create();
  const spy = vi.spyOn(api, 'create').mockResolvedValue(g);
  render(
    <MemoryRouter>
      <NewOwner />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText('사장님 이름'), { target: { value: '연습' } });
  fireEvent.click(await screen.findByRole('button', { name: /4분기 연속 흑자/ }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '확인하고 동네 찾기' })).not.toBeDisabled(),
  );
  fireEvent.click(screen.getByRole('button', { name: '확인하고 동네 찾기' }));
  await waitFor(() =>
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ missionCode: 'BLACK_4' })),
  );
});
it('목표 진행과 달성 도장을 표시한다', async () => {
  const g = await create();
  g.mission.progress = { current: 2, target: 4, unit: 'STREAK', label: '2 / 4분기' };
  const view = render(<MissionProgress mission={g.mission} />);
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
  view.rerender(<MissionProgress mission={{ ...g.mission, achieved: true }} />);
  expect(screen.getByText('달성')).toBeInTheDocument();
});
it('MINOR DATA 사건은 확률 막대 없이 출처와 결과를 표시한다', async () => {
  const g = await create();
  vi.spyOn(api, 'turningPoint').mockResolvedValue({
    gameTurningPointId: 1,
    code: 'DATA',
    tier: 'MINOR',
    source: 'DATA',
    dataNote: '연습용 가상 수치',
    title: '동네 변화',
    description: '대응을 골라 주세요.',
    options: [
      {
        optionId: 1,
        label: '대응한다',
        successRatePct: 70,
        onSuccess: '수익 증가',
        onFail: '비용 발생',
      },
    ],
  });
  vi.spyOn(api, 'choose').mockResolvedValue({
    success: false,
    resultText: '경험을 쌓았습니다.',
    effects: [{ label: '현금', value: '-100만 원' }],
    game: g,
  });
  render(<TurningPointCard game={g} onGame={vi.fn()} />);
  await screen.findByRole('button', { name: '대응한다' });
  expect(screen.getByText('실제 데이터')).toBeInTheDocument();
  expect(document.querySelector('meter')).toBeNull();
  expect(screen.getByText(/출처: 연습용/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '대응한다' }));
  expect(await screen.findByRole('button', { name: '운영 계획으로' })).toBeInTheDocument();
  expect(screen.getByText(/경험을 쌓았습니다/)).toBeInTheDocument();
});
it('결산 영수증 아래 목표 달성을 알린다', () => {
  render(
    <ResultCard
      result={{
        ...sampleResult,
        missionEvent: { code: 'BLACK_4', title: '4분기 연속 흑자', text: '목표 달성!' },
      }}
    />,
  );
  expect(screen.getByRole('status')).toHaveTextContent('목표 달성');
});

it('4분기 연속 흑자 달성이 결산과 매각 요약·랭킹에 유지된다', async () => {
  let g = await create();
  for (let i = 0; i < 4; i++) {
    if (g.pendingTurningPoint) g = (await api.choose(g.gameId, 2)).game;
    await api.decision(g, {
      priceLevel: 'NORMAL',
      openHours: 10,
      staff: 2,
      marketingBudget: 0,
      ownerActivity: 'REST',
    });
    const response = await mockRequest<{ game: GameState; result: typeof sampleResult }>(
      '/games/' + g.gameId + '/advance',
      'POST',
      { expectedVersion: g.version },
    );
    g = response.game;
    expect(response.result.missionEvent !== null).toBe(i === 3);
  }
  if (g.pendingTurningPoint) g = (await api.choose(g.gameId, 2)).game;
  g = await api.sell(g.gameId, g.version);
  expect((await api.summary(g.gameId)).mission.achieved).toBe(true);
  expect((await api.rankings()).entries.find((e) => e.gameId === g.gameId)?.missionAchieved).toBe(
    true,
  );
});

it('시작 목표의 금액과 순위 누락값을 장부 형식으로 표시한다', async () => {
  const base = {
    ownerName: '연습',
    background: 'LOCAL',
    candidateId: 'c1',
    candidateSeed: 123,
    focusStats: [],
  };
  const profit = await mockRequest<GameState>('/games', 'POST', {
    ...base,
    missionCode: 'PROFIT_100M',
  });
  expect(profit.mission.progress.label).toBe('0원 / 1억 원');
  const rank = await mockRequest<GameState>('/games', 'POST', {
    ...base,
    missionCode: 'AREA_TOP3',
  });
  expect(rank.mission.progress.label).toBe('순위 없음 / 5위 이내');
  profit.mission.progress.label = '1,383만 원 / 1억 원';
  const { container } = render(<MissionProgress mission={profit.mission} />);
  expect(screen.getByText('목표 · 누적 순이익 1억')).toBeInTheDocument();
  expect(container.querySelector('.mission-progress p > span:first-child .num')).toBeNull();
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuetext', '1,383만 원 / 1억 원');
});
it('12분기 생존 달성과 DATA 사건 두 종류의 대응 선택지를 제공한다', async () => {
  let g = await mockRequest<GameState>('/games', 'POST', {
    ownerName: '연습',
    background: 'LOCAL',
    candidateId: 'c1',
    candidateSeed: 123,
    focusStats: [],
  });
  const dataTitles = new Set<string>();
  for (let i = 0; i < 12; i++) {
    if (g.pendingTurningPoint) {
      const point = await api.turningPoint(g.gameId);
      if (point?.source === 'DATA') {
        dataTitles.add(point.title);
        expect(point.options).toHaveLength(3);
        expect(point.options.map((o) => o.label)).not.toContain('행사에 참여한다');
      }
      g = (await api.choose(g.gameId, 2)).game;
    }
    await api.decision(g, {
      priceLevel: 'NORMAL',
      openHours: 10,
      staff: 2,
      marketingBudget: 3000000,
      ownerActivity: 'REST',
    });
    const response = await mockRequest<{ game: GameState; result: typeof sampleResult }>(
      '/games/' + g.gameId + '/advance',
      'POST',
      { expectedVersion: g.version },
    );
    g = response.game;
    expect(response.result.missionEvent?.code ?? null).toBe(i === 11 ? 'SURVIVE_3Y' : null);
  }
  expect(dataTitles.size).toBe(2);
  expect(g.mission.achieved).toBe(true);
});
