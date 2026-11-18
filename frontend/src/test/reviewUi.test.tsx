import { afterEach, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ShopSetup from '../pages/ShopSetup';
import { EndingScreen } from '../components/GameMilestones';
import { api } from '../api/client';
import { mockRequest, areas, industries } from '../api/mock';
import type { GameState } from '../api/types';
vi.mock('../components/AreaMap', () => ({ AreaMap: () => null }));
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
it.each([null, 0])('상권 누락값과 0을 구분한다: %s', async (value) => {
  vi.spyOn(api, 'industries').mockResolvedValue(industries);
  vi.spyOn(api, 'search').mockResolvedValue(areas);
  vi.spyOn(api, 'report').mockResolvedValue({
    areaCode: areas[0].areaCode,
    name: areas[0].name,
    quarter: '20231',
    floatingPopulation: value,
    industries: [
      {
        ...industries[9],
        storeCount: 0,
        franchiseCount: 0,
        openingRatePct: value,
        closingRatePct: value,
        quarterSales: null,
        salesEstimated: false,
      },
    ],
  });
  render(<ShopSetup game={await game()} onGame={vi.fn()} />);
  fireEvent.change(screen.getByLabelText('상권 검색'), { target: { value: '망원' } });
  fireEvent.click(screen.getByRole('button', { name: '검색' }));
  fireEvent.click(await screen.findByRole('button', { name: areas[0].name }));
  if (value === null)
    expect((await screen.findAllByText(/자료 없음/)).length).toBeGreaterThanOrEqual(3);
  else {
    expect(await screen.findByText('0')).toBeInTheDocument();
    expect(await screen.findByText(/개업률 0% · 폐업률 0%/)).toBeInTheDocument();
  }
});
it.each([
  ['CLOSED', '가게의 문을 닫고, 다음 시작을 준비해요'],
  ['SOLD', '가게를 매각하고 은퇴했어요'],
  ['LONG_RUNNING', '오래 사랑받는 가게가 되었어요'],
] as const)('엔딩 %s 문구를 구분한다', async (endingType, title) => {
  const g = await game();
  g.status = 'ENDED';
  g.endingType = endingType;
  vi.spyOn(api, 'summary').mockRejectedValue(new Error('요약 없음'));
  render(
    <MemoryRouter>
      <EndingScreen game={g} />
    </MemoryRouter>,
  );
  expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
});
