import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { money } from '../components/ResultCard';
import { TurningPointCard, EndingScreen } from '../components/GameMilestones';
import { rankingDates } from '../components/Rankings';
import { api } from '../api/client';
import { mockRequest } from '../api/mock';
import type { GameState, TurningPointResult } from '../api/types';
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
async function game() {
  return mockRequest<GameState>('/games', 'POST', {
    ownerName: '연습',
    background: 'LOCAL',
    candidateId: 'c1',
    candidateSeed: 123,
    focusStats: [],
  });
}
describe('금액 경계값', () => {
  it.each([
    [0, '0원'],
    [9800, '9,800원'],
    [9999, '9,999원'],
    [10000, '1만 원'],
    [33280000, '3,328만 원'],
    [446628600, '4억 4,663만 원'],
    [-2440500, '-244만 원'],
    [100000000, '1억 원'],
    [99999999, '1억 원'],
  ])('%s → %s', (n, expected) => expect(money(n as number)).toBe(expected));
});
it('빈 검색어는 fetch와 게스트 요청도 보내지 않는다', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch');
  expect(await api.search('  ')).toEqual([]);
  expect(fetch).not.toHaveBeenCalled();
});
it('전환점 선택 결과를 확인한 뒤 응답 version으로 계속한다', async () => {
  const g = await game();
  g.pendingTurningPoint = true;
  const updated = { ...g, version: g.version + 1, pendingTurningPoint: false };
  vi.spyOn(api, 'turningPoint').mockResolvedValue({
    gameTurningPointId: 2,
    code: 'TEST',
    title: '새로운 기회',
    description: '선택해 주세요',
    options: [
      {
        optionId: 1,
        label: '도전한다',
        successRatePct: 70,
        onSuccess: '수익 증가',
        onFail: '비용 발생',
      },
    ],
  });
  const choose = vi.spyOn(api, 'choose').mockResolvedValue({
    success: true,
    resultText: '성공했어요',
    effects: [{ label: '보유금', value: '+300만 원' }],
    game: updated,
  } satisfies TurningPointResult);
  const onGame = vi.fn();
  render(<TurningPointCard game={g} onGame={onGame} />);
  fireEvent.click(await screen.findByRole('button', { name: '도전한다' }));
  expect(await screen.findByText('보유금 +300만 원')).toBeInTheDocument();
  expect(choose).toHaveBeenCalledWith(g.gameId, 1);
  expect(onGame).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '계속' }));
  expect(onGame).toHaveBeenCalledWith(updated);
});
it('엔딩 요약과 새 창업을 표시한다', async () => {
  const g = await game();
  g.status = 'ENDED';
  g.endingType = 'SOLD';
  vi.spyOn(api, 'summary').mockResolvedValue({
    gameId: g.gameId,
    endingType: 'SOLD',
    ownerName: '연습',
    shopName: '나의 연습 가게',
    areaName: '연습 상권',
    serviceName: '커피-음료',
    survivedQuarters: 4,
    totalProfit: 100000000,
    bestQuarterProfit: 30000000,
    shopCount: 1,
    shareUrl: '/share/1',
  });
  render(
    <MemoryRouter>
      <EndingScreen game={g} />
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByText('4분기')).toBeInTheDocument());
  expect(screen.getByText('1억 원')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '새 창업' })).toHaveAttribute('href', '/new');
});
it('날짜는 KST 오늘부터 7일이다', () => {
  expect(rankingDates(new Date('2026-11-18T16:00:00Z'))).toEqual([
    '2026-11-19',
    '2026-11-18',
    '2026-11-17',
    '2026-11-16',
    '2026-11-15',
    '2026-11-14',
    '2026-11-13',
  ]);
});
it('8분기 목은 2분기마다 전환점과 마지막 엔딩을 만든다', async () => {
  let g = await game();
  await expect(mockRequest(`/games/${g.gameId}/summary`, 'GET')).rejects.toMatchObject({
    code: 'GAME_NOT_ENDED',
  });
  for (let i = 0; i < 8; i++) {
    await mockRequest(`/games/${g.gameId}/turns/${i}/decision`, 'PUT', {
      priceLevel: 'NORMAL',
      openHours: 10,
      staff: 2,
      marketingBudget: 0,
      ownerActivity: 'MENU_DEV',
    });
    const r = await mockRequest<{ game: GameState; result: { reactions: unknown[] } }>(
      `/games/${g.gameId}/advance`,
      'POST',
      { expectedVersion: g.version },
      `advance-${i}`,
    );
    g = r.game;
    expect(r.result.reactions).toHaveLength(2);
    expect(g.pendingTurningPoint).toBe((i + 1) % 2 === 0 && i < 7);
    if (g.pendingTurningPoint) {
      const c = await mockRequest<TurningPointResult>(
        `/games/${g.gameId}/turning-point/choice`,
        'POST',
        { optionId: 2 },
        `choice-${i}`,
      );
      g = c.game;
    }
  }
  expect(g.status).toBe('ENDED');
  expect(g.endingType).toBe('LONG_RUNNING');
});
it('매각은 최소 분기·버전·전환점·멱등 키를 검사한다', async () => {
  let g = await game();
  await expect(
    mockRequest(`/games/${g.gameId}/sell`, 'POST', { expectedVersion: 0 }, 'early'),
  ).rejects.toMatchObject({ status: 400 });
  for (let i = 0; i < 4; i++) {
    await mockRequest(`/games/${g.gameId}/turns/${i}/decision`, 'PUT', {
      priceLevel: 'NORMAL',
      openHours: 10,
      staff: 2,
      marketingBudget: 0,
      ownerActivity: 'MENU_DEV',
    });
    g = (
      await mockRequest<{ game: GameState }>(
        `/games/${g.gameId}/advance`,
        'POST',
        { expectedVersion: g.version },
        `sell-advance-${i}`,
      )
    ).game;
    if (g.pendingTurningPoint) {
      await expect(
        mockRequest(`/games/${g.gameId}/sell`, 'POST', { expectedVersion: g.version }, 'pending'),
      ).rejects.toMatchObject({ code: 'TURNING_POINT_PENDING' });
      g = (
        await mockRequest<TurningPointResult>(
          `/games/${g.gameId}/turning-point/choice`,
          'POST',
          { optionId: 2 },
          `sell-choice-${i}`,
        )
      ).game;
    }
  }
  await expect(
    mockRequest(`/games/${g.gameId}/sell`, 'POST', { expectedVersion: 0 }, 'stale'),
  ).rejects.toMatchObject({ status: 409 });
  const sold = await mockRequest<GameState>(
    `/games/${g.gameId}/sell`,
    'POST',
    { expectedVersion: g.version },
    'sold',
  );
  expect(sold.endingType).toBe('SOLD');
  expect(
    await mockRequest(`/games/${g.gameId}/sell`, 'POST', { expectedVersion: g.version }, 'sold'),
  ).toEqual(sold);
  await expect(
    mockRequest(`/games/${g.gameId}/advance`, 'POST', { expectedVersion: g.version }, 'sold'),
  ).rejects.toMatchObject({ code: 'IDEMPOTENCY_KEY_REUSED' });
});
