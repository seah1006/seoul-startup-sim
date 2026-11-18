import type {
  GameSummary,
  Rankings,
  RankingKind,
  TurningPoint,
  TurningPointResult,
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
  Stores,
} from './types';
import { mockRequest } from './mock';
export const useMock = import.meta.env.VITE_USE_MOCK !== 'false';
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: string,
  ) {
    super(message);
  }
}
export const guestStorageKey = useMock ? 'sss-guest-mock' : 'sss-guest';
let guestPromise: Promise<string> | undefined;
async function guest(): Promise<string> {
  const existing = localStorage.getItem(guestStorageKey);
  if (existing) return existing;
  if (!guestPromise)
    guestPromise = (async () => {
      const data = useMock
        ? await mockRequest<{ guestToken: string }>('/guest', 'POST')
        : await fetch('/api/guest', { method: 'POST' }).then(async (r) => {
            if (!r.ok) throw new ApiError(r.status, '게스트 연결에 실패했습니다.');
            return r.json();
          });
      localStorage.setItem(guestStorageKey, data.guestToken);
      return data.guestToken;
    })().catch((e) => {
      guestPromise = undefined;
      throw e;
    });
  return guestPromise;
}
export function restartGuest() {
  localStorage.removeItem(guestStorageKey);
  guestPromise = undefined;
  return guest();
}
// Retry only once. A refreshed guest is a new identity, not a recovery of old ownership.
export async function sendWithGuestRecovery(
  send: (token: string | null) => Promise<Response>,
  token: string | null,
  optional = false,
): Promise<Response> {
  const response = await send(token);
  if (response.status !== 401 || !token) return response;
  if (localStorage.getItem(guestStorageKey) === token) {
    localStorage.removeItem(guestStorageKey);
    guestPromise = undefined;
  }
  return send(optional ? null : await guest());
}
export async function request<T>(
  url: string,
  method = 'GET',
  body?: unknown,
  key?: string,
): Promise<T> {
  const token = await guest();
  if (useMock) return mockRequest<T>(url, method, body, key);
  const response = await sendWithGuestRecovery(
    (token) =>
      fetch(`/api${url}`, {
        method,
        headers: {
          'Content-Type': 'application/json',
          'X-Guest-Token': token!,
          ...(key ? { 'Idempotency-Key': key } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    token,
  );
  if (!response.ok) {
    const data = await response.json().catch(() => ({ message: '서버 연결에 실패했습니다.' }));
    throw new ApiError(response.status, data.message, data.code);
  }
  return response.status === 204 ? (null as T) : response.json();
}
export async function advanceWithRecovery(game: GameState, onRefresh: (game: GameState) => void) {
  try {
    return await request<{ result: QuarterResult; game: GameState }>(
      `/games/${game.gameId}/advance`,
      'POST',
      { expectedVersion: game.version },
      crypto.randomUUID(),
    );
  } catch (error) {
    if ((error as ApiError).status === 409) {
      onRefresh(await request<GameState>(`/games/${game.gameId}`));
    }
    throw error;
  }
}
export const api = {
  guest,
  turningPoint: (id: number) => request<TurningPoint | null>(`/games/${id}/turning-point`),
  choose: (id: number, optionId: number) =>
    request<TurningPointResult>(
      `/games/${id}/turning-point/choice`,
      'POST',
      { optionId },
      crypto.randomUUID(),
    ),
  sell: (id: number, expectedVersion: number) =>
    request<GameState>(`/games/${id}/sell`, 'POST', { expectedVersion }, crypto.randomUUID()),
  summary: (id: number) => request<GameSummary>(`/games/${id}/summary`),
  rankings: (date?: string, kind: RankingKind = 'ALL', key?: string, limit?: number) =>
    request<Rankings>(
      '/rankings?' +
        new URLSearchParams({
          kind,
          ...(date ? { date } : {}),
          ...(key ? { key } : {}),
          ...(limit ? { limit: String(limit) } : {}),
        }),
    ),
  candidates: (b: Background, s: number) =>
    request<{ candidates: Candidate[] }>(`/owners/candidates?background=${b}&seed=${s}`),
  create: (b: OwnerInput) => request<GameState>('/games', 'POST', b),
  game: (id: string | number) => request<GameState>(`/games/${id}`),
  shop: (id: number, b: ShopInput) => request<GameState>(`/games/${id}/shop`, 'POST', b),
  decision: (g: GameState, d: Decision) =>
    request(`/games/${g.gameId}/turns/${g.currentTurn}/decision`, 'PUT', d),
  turns: (id: number) => request<QuarterResult[]>(`/games/${id}/turns`),
  industries: () => request<Industry[]>('/industries?playable=true'),
  areas: (bbox: string, zoom: number) =>
    request<AreaCollection>(`/areas?bbox=${bbox}&zoom=${zoom}`),
  search: (q: string) =>
    !q.trim()
      ? Promise.resolve([] as Area[])
      : request<Area[]>(`/areas/search?q=${encodeURIComponent(q)}`),
  report: (code: string, quarter: string) =>
    request<Report>(`/areas/${code}/report?quarter=${quarter}`),
  stores: (code: string, service: string, lng: number, lat: number) =>
    request<Stores>(
      `/areas/${code}/stores?serviceCode=${service}&lng=${lng}&lat=${lat}&radius=300`,
    ),
};
