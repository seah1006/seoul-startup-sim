import { afterEach, describe, expect, it, vi } from 'vitest';

describe('무효 토큰 처리 (실제 API 모드)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
    vi.resetModules();
    localStorage.clear();
  });

  it('GET /auth/me 가 401이면 토큰만 지우고 로그인 화면 이동 이벤트는 보내지 않는다', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false');
    vi.resetModules();
    const { request, guestStorageKey, useMock } = await import('../api/client');
    expect(useMock).toBe(false);
    localStorage.setItem(guestStorageKey, 'stale');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'x' }), { status: 401 }),
    );
    const required = vi.fn();
    window.addEventListener('authrequired', required);
    await expect(request('/auth/me')).rejects.toMatchObject({ status: 401 });
    expect(localStorage.getItem(guestStorageKey)).toBeNull();
    expect(required).not.toHaveBeenCalled();
    window.removeEventListener('authrequired', required);
  });

  it('회원 전용 요청의 401은 그대로 로그인 화면 이동 이벤트를 보낸다', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false');
    vi.resetModules();
    const { request, guestStorageKey } = await import('../api/client');
    localStorage.setItem(guestStorageKey, 'stale');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'x' }), { status: 401 }),
    );
    const required = vi.fn();
    window.addEventListener('authrequired', required);
    await expect(request('/auth/me', 'PATCH', { nickname: 'a' })).rejects.toMatchObject({
      status: 401,
    });
    expect(required).toHaveBeenCalledTimes(1);
    window.removeEventListener('authrequired', required);
  });

  it('공개 조회(GET /rankings)는 무효 토큰 401 뒤 토큰 없이 다시 조회하고 로그인 화면으로 보내지 않는다', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false');
    vi.resetModules();
    const { request, guestStorageKey } = await import('../api/client');
    localStorage.setItem(guestStorageKey, 'stale');
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response('[]', { status: 200 }));
    const required = vi.fn();
    window.addEventListener('authrequired', required);
    await expect(request('/rankings')).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondHeaders = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<
      string,
      string
    >;
    expect(secondHeaders.Authorization).toBeUndefined();
    expect(required).not.toHaveBeenCalled();
    window.removeEventListener('authrequired', required);
  });
});
