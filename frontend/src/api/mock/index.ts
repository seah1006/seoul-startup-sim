import type {
  Area,
  AreaCollection,
  Background,
  Candidate,
  Decision,
  GameState,
  Industry,
  OwnerInput,
  QuarterResult,
  Report,
  ShopInput,
  Stat,
  Stores,
  TurningPoint,
  RecordSnapshot,
} from '../types';
export const industries: Industry[] = [
  '한식음식점',
  '중식음식점',
  '일식음식점',
  '양식음식점',
  '제과점',
  '패스트푸드점',
  '치킨전문점',
  '분식전문점',
  '호프-간이주점',
  '커피-음료',
].map((name, i) => ({
  serviceCode: `CS1000${String(i + 1).padStart(2, '0')}`,
  name,
  category: 'FOOD',
  playable: true,
}));
export const areas: Area[] = [
  {
    areaCode: 'demo-1',
    name: '망원동 연습 상권',
    districtName: '마포구',
    areaTypeName: '연습용',
    centroid: { lng: 126.905, lat: 37.556 },
  },
  {
    areaCode: 'demo-2',
    name: '성수동 연습 상권',
    districtName: '성동구',
    areaTypeName: '연습용',
    centroid: { lng: 127.055, lat: 37.544 },
  },
];
export const polygons: AreaCollection = {
  type: 'FeatureCollection',
  truncated: false,
  features: areas.map((a) => ({
    type: 'Feature',
    properties: a,
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [a.centroid.lng - 0.003, a.centroid.lat - 0.002],
          [a.centroid.lng + 0.003, a.centroid.lat - 0.002],
          [a.centroid.lng + 0.003, a.centroid.lat + 0.002],
          [a.centroid.lng - 0.003, a.centroid.lat + 0.002],
          [a.centroid.lng - 0.003, a.centroid.lat - 0.002],
        ],
      ],
    },
  })),
};
export function candidates(background: Background, seed: number): Candidate[] {
  const bases = {
    OFFICE_WORKER: [35, 40, 50, 48, 37, 30],
    CULINARY: [65, 35, 35, 30, 45, 30],
    PART_TIMER: [35, 55, 35, 30, 55, 30],
    LOCAL: [35, 40, 35, 35, 35, 60],
  };
  return [0, 1, 2].map((i) => {
    const values = [...bases[background]];
    const change = ((seed + i * 7) % 15) - 7;
    values[i] += change;
    values[(i + 3) % 6] -= change;
    return {
      candidateId: `c${i + 1}`,
      stats: Object.fromEntries(
        ['cooking', 'service', 'management', 'marketing', 'stamina', 'network'].map((s, j) => [
          s,
          values[j],
        ]),
      ) as Candidate['stats'],
      total: 240,
    };
  });
}
export const sampleResult: QuarterResult = {
  turn: 0,
  quarter: '20231',
  headline: { label: '이번 분기 순이익', amount: 12400000 },
  revenue: 58200000,
  visitors: 4130,
  avgTicket: 14100,
  costs: {
    rent: 9000000,
    ingredients: 20370000,
    labor: 12000000,
    marketing: 4430000,
    total: 45800000,
  },
  profit: 12400000,
  cashAfter: 62400000,
  areaRank: { rank: 4, of: 12 },
  statChanges: [{ stat: 'cooking', before: 49, after: 52 }],
  areaNews: {
    openings: 2,
    closings: 1,
    source: '서울시 상권분석서비스 20231 (목 예시, 실제 조회 아님)',
  },
  moment: null,
  reactions: [],
  dataNotes: [
    '목 데이터로 실행 중입니다. 실제 상권의 매출이나 순위를 의미하지 않습니다.',
    '유동인구는 길단위인구 분기 집계를 서울 상권과 비교한 상위 비율로 표시합니다.',
  ],
};
const records: RecordSnapshot[] = [
  {
    gameId: 101,
    endingType: 'LONG_RUNNING',
    serviceCode: 'CS100010',
    serviceName: '커피-음료',
    areaCode: '3110123',
    areaName: '망원역',
    survivedQuarters: 14,
    totalProfit: 182000000,
    shopName: '가상 구름모래 찻집',
    ownerName: '가상 사장 하루',
    shareUrl: '/share/101',
  },
  {
    gameId: 102,
    endingType: 'SOLD',
    serviceCode: 'CS100008',
    serviceName: '분식전문점',
    areaCode: '3110123',
    areaName: '망원역',
    survivedQuarters: 8,
    totalProfit: 45000000,
    shopName: '가상 초승달 분식',
    ownerName: '가상 사장 나래',
    shareUrl: '/share/102',
  },
  {
    gameId: 103,
    endingType: 'CLOSED',
    serviceCode: 'CS100010',
    serviceName: '커피-음료',
    areaCode: '3110837',
    areaName: '성수역',
    survivedQuarters: 4,
    totalProfit: -17000000,
    shopName: '가상 파란행성 다방',
    ownerName: '가상 사장 바다',
    shareUrl: '/share/103',
  },
];
const demoRecords = [
  ...records,
  { ...records[0], gameId: 104, shopName: '가상 별빛우체국 찻집', shareUrl: '/share/104' },
  { ...records[0], gameId: 105, shopName: '가상 달빛정원 찻집', shareUrl: '/share/105' },
];
export { records as seedBoardRecords };
const storageKey = 'sss-mock-games-v2';
function load(): Record<
  string,
  {
    game: GameState;
    turns: QuarterResult[];
    decision?: Decision;
    keys: Record<string, { path: string; result: unknown }>;
  }
> {
  try {
    return JSON.parse(localStorage.getItem(storageKey) || '{}');
  } catch {
    return {};
  }
}
function save(db: ReturnType<typeof load>) {
  localStorage.setItem(storageKey, JSON.stringify(db));
}
export function mockEndingRecord(id: number): RecordSnapshot | 'PLAYING' | undefined {
  const row = load()[id];
  if (!row) return demoRecords.find((record) => record.gameId === id);
  const { game, turns } = row;
  if (game.status !== 'ENDED') return 'PLAYING';
  if (!game.shop || !game.endingType) return undefined;
  const { shopName, areaCode, areaName, serviceCode, serviceName } = game.shop;
  return {
    gameId: id,
    endingType: game.endingType,
    ownerName: game.owner.name,
    shopName,
    areaCode,
    areaName,
    serviceCode,
    serviceName,
    survivedQuarters: turns.length,
    totalProfit: turns.reduce((sum, turn) => sum + turn.profit, 0),
    shareUrl: `/share/${id}`,
  };
}
export async function mockRequest<T>(
  url: string,
  method: string,
  body?: unknown,
  key?: string,
): Promise<T> {
  const db = load();
  const path = url.split('?')[0];
  const query = new URLSearchParams(url.split('?')[1]);
  let result: unknown;
  const catalogRoutes = [
    {
      matches: () => path === '/guest',
      handle: function handleCatalogRoute0() {
        result = { guestToken: 'mock-guest', userId: 1, nickname: '연습 사장님' };
      },
    },
    {
      matches: () => path === '/rankings',
      handle: function handleCatalogRoute1() {
        const date = query.get('date');
        const today = new Date(Date.now() + 9 * 3600000).toISOString().slice(0, 10);
        const oldest = new Date(Date.now() + 9 * 3600000 - 6 * 86400000).toISOString().slice(0, 10);
        if (date && (date < oldest || date > today))
          throw Object.assign(new Error('최근 7일만 조회할 수 있어요.'), { status: 400 });
        const kind = query.get('kind') || 'ALL';
        const entries = Object.values(db)
          .filter((r) => r.game.status === 'ENDED' && (!date || date === today))
          .filter(
            (r) =>
              kind === 'ALL' ||
              (kind === 'INDUSTRY' ? r.game.shop?.serviceCode : r.game.shop?.areaCode) ===
                query.get('key'),
          )
          .sort(
            (a, b) =>
              b.turns.length - a.turns.length ||
              b.turns.reduce((n, t) => n + t.profit, 0) -
                a.turns.reduce((n, t) => n + t.profit, 0) ||
              Math.max(0, ...b.turns.map((t) => t.profit)) -
                Math.max(0, ...a.turns.map((t) => t.profit)),
          )
          .map((r, _i, list) => ({
            rank:
              list.findIndex(
                (x) =>
                  x.turns.length === r.turns.length &&
                  x.turns.reduce((n, t) => n + t.profit, 0) ===
                    r.turns.reduce((n, t) => n + t.profit, 0) &&
                  Math.max(0, ...x.turns.map((t) => t.profit)) ===
                    Math.max(0, ...r.turns.map((t) => t.profit)),
              ) + 1,
            gameId: r.game.gameId,
            nickname: '연습 사장님',
            ownerName: r.game.owner.name,
            shopName: r.game.shop?.shopName || '연습 가게',
            areaName: r.game.shop?.areaName || '',
            serviceName: r.game.shop?.serviceName || '',
            endingType: r.game.endingType,
            survivedQuarters: r.turns.length,
            totalProfit: r.turns.reduce((n, t) => n + t.profit, 0),
          }));
        result = {
          date: query.get('date') || new Date(Date.now() + 9 * 3600000).toISOString().slice(0, 10),
          refreshedAt: new Date().toISOString(),
          kind,
          entries: entries.slice(0, Number(query.get('limit')) || entries.length),
        };
      },
    },
    {
      matches: () => path === '/industries',
      handle: function handleCatalogRoute2() {
        result = industries;
      },
    },
    {
      matches: () => path === '/owners/candidates',
      handle: function handleCatalogRoute3() {
        result = {
          background: query.get('background'),
          candidates: candidates(query.get('background') as Background, Number(query.get('seed'))),
        };
      },
    },
    {
      matches: () => path === '/areas',
      handle: function handleCatalogRoute4() {
        result = polygons;
      },
    },
    {
      matches: () => path === '/areas/search',
      handle: function handleCatalogRoute5() {
        result = areas.filter((a) => a.name.includes(query.get('q') || ''));
      },
    },
    {
      matches: () => path.startsWith('/areas/'),
      handle: function handleCatalogRoute6() {
        const a = areas.find((a) => a.areaCode === path.split('/')[2]) || areas[0];
        if (path.endsWith('/report'))
          result = {
            areaCode: a.areaCode,
            name: a.name,
            quarter: query.get('quarter') || '20231',
            floatingPopulation: 433442,
            floatingPopulationTopPct: 35,
            industries: industries.map((i) => ({
              ...i,
              storeCount: 42,
              franchiseCount: 9,
              openingRatePct: 3,
              closingRatePct: 2,
              quarterSales: 1820000000,
              salesEstimated: true,
            })),
          } satisfies Report;
        else if (path.endsWith('/stores')) {
          const lng = Number(query.get('lng'));
          const lat = Number(query.get('lat'));
          result = {
            count: 3,
            stores: [0, 1, 2].map((i) => ({
              storeId: `mock-${i}`,
              name: `가상 경쟁점 ${i + 1}`,
              lng: lng + (i - 1) * 0.0007,
              lat: lat + 0.0005,
              distanceM: 80 + i * 45,
            })),
          } satisfies Stores;
        } else result = a;
      },
    },
    {
      matches: () => /^\/games\/\d+\/summary$/.test(path) && !db[path.split('/')[2]],
      handle: function handleCatalogRoute7() {
        const id = Number(path.split('/')[2]);
        const record = demoRecords.find((r) => r.gameId === id);
        if (!record)
          throw Object.assign(new Error('인증할 게임을 찾을 수 없어요'), {
            status: 404,
            code: 'NOT_FOUND',
          });
        return { ...record, bestQuarterProfit: 0, shopCount: 1 } as T;
      },
    },
    {
      matches: () => path === '/games' && method === 'POST',
      handle: function handleCatalogRoute8() {
        const b = body as OwnerInput;
        let gameId = Math.max(0, ...Object.keys(db).map(Number)) + 1;
        while (demoRecords.some((r) => r.gameId === gameId)) gameId++;
        const game: GameState = {
          gameId,
          version: 0,
          status: 'PLAYING',
          endingType: null,
          currentTurn: 0,
          currentQuarter: b.startQuarter || '20231',
          startQuarter: b.startQuarter || '20231',
          lastQuarter: '20262',
          cash: 50000000,
          owner: {
            name: b.ownerName,
            background: b.background,
            stats: candidates(b.background, b.candidateSeed).find(
              (c) => c.candidateId === b.candidateId,
            )!.stats,
            focusStats: b.focusStats,
          },
          shop: null,
          pendingTurningPoint: false,
          pendingDecision: true,
        };
        db[gameId] = { game, turns: [], keys: {} };
        result = game;
      },
    },
  ];
  const matched1 = catalogRoutes.find((route) => route.matches());
  if (matched1) {
    const direct = matched1.handle();
    if (direct !== undefined) return direct as T;
  } else {
    const id = path.split('/')[2];
    const row = db[id];
    if (!row) throw new Error('게임을 찾을 수 없습니다. 새 창업을 시작해 주세요.');
    if (key && row.keys[key]) {
      if (row.keys[key].path !== path)
        throw Object.assign(new Error('다른 작업에 사용한 요청 키입니다.'), {
          status: 409,
          code: 'IDEMPOTENCY_KEY_REUSED',
        });
      return structuredClone(row.keys[key].result) as T;
    }
    const gameRoutes = [
      {
        matches: () => path.endsWith('/turning-point/choice'),
        handle: function handleGameRoute0() {
          if (!row.game.pendingTurningPoint)
            throw Object.assign(new Error('대기 중인 전환점이 없습니다.'), { status: 409 });
          const optionId = (body as { optionId: number }).optionId;
          if (![1, 2].includes(optionId)) throw new Error('선택지를 확인해 주세요.');
          const success = optionId === 2 || row.game.currentTurn % 4 === 2;
          row.game.pendingTurningPoint = false;
          row.game.version++;
          if (optionId === 1) row.game.cash += success ? 3000000 : -1000000;
          result = {
            success,
            resultText:
              optionId === 2
                ? '독립 가게의 길을 이어갑니다.'
                : success
                  ? '동네 행사 참여가 좋은 반응을 얻었어요.'
                  : '행사 준비 비용이 예상보다 컸어요.',
            effects: [
              {
                label: '보유금',
                value: optionId === 2 ? '변화 없음' : success ? '+300만 원' : '-100만 원',
              },
            ],
            game: row.game,
          };
        },
      },
      {
        matches: () => path.endsWith('/turning-point'),
        handle: function handleGameRoute1() {
          result = row.game.pendingTurningPoint
            ? ({
                gameTurningPointId: row.game.currentTurn,
                code: 'LOCAL_EVENT',
                title: '동네 행사 참여 제안',
                description: '이웃에게 가게를 알릴 기회가 찾아왔어요.',
                options: [
                  {
                    optionId: 1,
                    label: '행사에 참여한다',
                    successRatePct: 70,
                    onSuccess: '보유금 +300만 원',
                    onFail: '보유금 -100만 원',
                  },
                  {
                    optionId: 2,
                    label: '가게 운영에 집중한다',
                    successRatePct: 100,
                    onSuccess: '변화 없음',
                    onFail: '-',
                  },
                ],
              } satisfies TurningPoint)
            : null;
        },
      },
      {
        matches: () => path.endsWith('/sell'),
        handle: function handleGameRoute2() {
          if ((body as { expectedVersion: number })?.expectedVersion !== row.game.version)
            throw Object.assign(new Error('최신 게임을 다시 불러와 주세요.'), { status: 409 });
          if (row.game.pendingTurningPoint)
            throw Object.assign(new Error('전환점을 먼저 선택해 주세요.'), {
              status: 409,
              code: 'TURNING_POINT_PENDING',
            });
          if (row.game.status === 'ENDED')
            throw Object.assign(new Error('이미 마친 창업입니다.'), { status: 409 });
          if (row.turns.length < 4)
            throw Object.assign(new Error('4분기 이상 운영한 뒤 매각할 수 있어요.'), {
              status: 400,
            });
          row.game.status = 'ENDED';
          row.game.endingType = 'SOLD';
          row.game.pendingTurningPoint = false;
          row.game.version++;
          result = row.game;
        },
      },
      {
        matches: () => path.endsWith('/summary'),
        handle: function handleGameRoute3() {
          if (row.game.status !== 'ENDED')
            throw Object.assign(new Error('아직 종료되지 않은 창업입니다.'), {
              status: 409,
              code: 'GAME_NOT_ENDED',
            });
          result = {
            gameId: row.game.gameId,
            endingType: row.game.endingType,
            ownerName: row.game.owner.name,
            shopName: row.game.shop?.shopName || '연습 가게',
            areaName: row.game.shop?.areaName || '',
            serviceName: row.game.shop?.serviceName || '',
            survivedQuarters: row.turns.length,
            totalProfit: row.turns.reduce((n, t) => n + t.profit, 0),
            bestQuarterProfit: row.turns.length ? Math.max(...row.turns.map((t) => t.profit)) : 0,
            shopCount: row.game.shop ? 1 : 0,
            shareUrl: '/share/' + row.game.gameId,
          };
        },
      },
      {
        matches: () => path.endsWith('/shop'),
        handle: function handleGameRoute4() {
          const b = body as ShopInput;
          row.game.shop = {
            ...b,
            areaName: areas.find((a) => a.areaCode === b.areaCode)?.name || '선택 상권',
            serviceName: industries.find((i) => i.serviceCode === b.serviceCode)?.name || '외식',
            rivalCount: 3,
          };
          row.game.version++;
          result = row.game;
        },
      },
      {
        matches: () => path.endsWith('/decision'),
        handle: function handleGameRoute5() {
          row.decision = body as Decision;
          row.game.pendingDecision = false;
          result = { turn: row.game.currentTurn, saved: true };
        },
      },
      {
        matches: () => path.endsWith('/advance'),
        handle: function handleGameRoute6() {
          if ((body as { expectedVersion: number }).expectedVersion !== row.game.version)
            throw Object.assign(new Error('최신 게임을 다시 불러왔습니다. 결정을 확인해 주세요.'), {
              status: 409,
            });
          if (row.game.status === 'ENDED')
            throw Object.assign(new Error('이미 마친 창업입니다.'), { status: 409 });
          if (row.game.pendingTurningPoint)
            throw Object.assign(new Error('전환점을 먼저 선택해 주세요.'), { status: 409 });
          if (!row.decision) throw new Error('운영 결정을 먼저 저장해 주세요.');
          const stat: Stat = row.game.owner.focusStats[0] || 'cooking';
          const before = row.game.owner.stats[stat];
          const costs = {
            rent: 9000000,
            ingredients: 20370000,
            labor: row.decision.staff * 6000000,
            marketing: row.decision.marketingBudget,
            total: 0,
          };
          costs.total = costs.rent + costs.ingredients + costs.labor + costs.marketing;
          const profit = sampleResult.revenue - costs.total;
          const quarter = row.game.currentQuarter;
          const r: QuarterResult = {
            ...sampleResult,
            moment: {
              title: '단골손님이 친구들을 데려왔어요',
              text: '꾸준한 노력이 이웃에게 전해졌어요.',
              stat,
              success: true,
            },
            reactions: [
              {
                kind: 'REVIEW',
                author: '동네 주민',
                rating: 4,
                text: '편안하게 들를 수 있는 가게예요.',
                likes: 12,
              },
              {
                kind: 'COMMUNITY',
                author: '동네 커뮤니티',
                text: '우리 동네에 이야기 나눌 공간이 생겼어요.',
                likes: 31,
              },
            ],
            quarter,
            turn: row.game.currentTurn,
            profit,
            headline: { ...sampleResult.headline, amount: profit },
            cashAfter: row.game.cash + profit,
            costs,
            statChanges: [{ stat, before, after: before + 3 }],
          };
          row.turns.push(r);
          row.game.cash = r.cashAfter;
          row.game.owner.stats[stat] += 3;
          row.game.currentTurn++;
          row.game.version++;
          row.game.pendingDecision = true;
          const year = Number(quarter.slice(0, 4)),
            q = Number(quarter[4]);
          row.game.pendingTurningPoint = row.game.currentTurn % 2 === 0 && row.game.currentTurn < 8;
          if (row.game.currentTurn >= 8 || quarter === row.game.lastQuarter) {
            row.game.status = 'ENDED';
            row.game.endingType = 'LONG_RUNNING';
          } else row.game.currentQuarter = q === 4 ? `${year + 1}1` : `${year}${q + 1}`;
          delete row.decision;
          result = { result: r, game: row.game };
        },
      },
      {
        matches: () => path.endsWith('/turns'),
        handle: function handleGameRoute7() {
          result = row.turns;
        },
      },
    ];
    const matched0 = gameRoutes.find((route) => route.matches());
    if (matched0) {
      const direct = matched0.handle();
      if (direct !== undefined) return direct as T;
    } else result = row.game;
  }
  if (key && path.startsWith('/games/'))
    db[path.split('/')[2]].keys[key] = { path, result: structuredClone(result) };
  save(db);
  return structuredClone(result) as T;
}
