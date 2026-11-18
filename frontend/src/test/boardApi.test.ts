import { afterEach, describe, expect, it, vi } from 'vitest';

async function client(mock: boolean) {
  vi.resetModules();
  vi.stubEnv('VITE_USE_MOCK', String(mock));
  return { ...await import('../api/board'), ...await import('../api/client') };
}
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

describe.each([false, true])('게시판 어댑터 (목=%s)', mock => {
  it('공개 조회는 토큰을 보내지 않고 선택 인증은 자동 발급하지 않는다', async () => {
    const request = vi.fn(async () => Response.json({ isMine: false }));
    vi.stubGlobal('fetch', request);
    const { boardApi, api, guestStorageKey } = await client(mock);
    const guest = vi.spyOn(api, 'guest');
    expect(await boardApi.post(2)).toMatchObject({ isMine: false });
    expect(localStorage.getItem(guestStorageKey)).toBeNull();
    localStorage.setItem(guestStorageKey, mock ? 'mock-guest' : 'existing');
    await boardApi.posts();
    await boardApi.post(2);
    await boardApi.comments(2);
    expect(guest).not.toHaveBeenCalled();
    if (!mock) {
      expect(request).toHaveBeenNthCalledWith(1, '/api/board/posts/2', expect.objectContaining({ headers: {} }));
      expect(request).toHaveBeenNthCalledWith(2, '/api/board/posts', expect.objectContaining({ headers: {} }));
      expect(request).toHaveBeenNthCalledWith(3, '/api/board/posts/2', expect.objectContaining({ headers: { 'X-Guest-Token': 'existing' } }));
      expect(request).toHaveBeenNthCalledWith(4, '/api/board/posts/2/comments', expect.objectContaining({ headers: { 'X-Guest-Token': 'existing' } }));
    }
  });

  it('204에서 본문을 파싱하지 않고 필수 인증은 게스트 발급을 사용한다', async () => {
    const reply = new Response(null, { status: 204 });
    const parse = vi.spyOn(reply, 'json');
    const request = vi.fn(async (path: string) => path === '/api/guest' ? Response.json({ guestToken: 'new-guest' }) : reply);
    vi.stubGlobal('fetch', request);
    const { boardApi, guestStorageKey } = await client(mock);
    await expect(boardApi.recordLink(9999)).resolves.toBeUndefined();
    expect(localStorage.getItem(guestStorageKey)).toBeNull();
    await expect(boardApi.like(2, true)).resolves.toBeUndefined();
    await expect(boardApi.like(2, false)).resolves.toBeUndefined();
    await expect(boardApi.deletePost(2, 0)).resolves.toBeUndefined();
    expect(localStorage.getItem(guestStorageKey)).toBe(mock ? 'mock-guest' : 'new-guest');
    expect(parse).not.toHaveBeenCalled();
    if (!mock) expect(request).toHaveBeenNthCalledWith(2, '/api/guest', { method: 'POST' });
    if (mock) expect((await boardApi.posts()).content.some(p => p.postId === 2)).toBe(false);
  });

  it('서로 다른 409 오류의 status·code·message를 보존한다', async () => {
    const conflict = { code: 'POST_VERSION_CONFLICT', message: '게시글이 변경되었습니다. 최신 게시글을 확인해 주세요.' };
    const duplicate = { code: 'ALREADY_REPORTED', message: '이미 신고한 대상입니다.' };
    vi.stubGlobal('fetch', vi.fn(async (path: string) => path.endsWith('/reports') ? Response.json(duplicate, { status: 409 }) : Response.json(conflict, { status: 409 })));
    const { boardApi, ApiError, guestStorageKey } = await client(mock);
    localStorage.setItem(guestStorageKey, mock ? 'mock-guest' : 'existing');
    const stale = boardApi.deletePost(2, 99);
    await expect(stale).rejects.toBeInstanceOf(ApiError);
    await expect(stale).rejects.toMatchObject({ status: 409, ...conflict });
    const report = { targetType: 'POST', targetId: 3, reason: 'SPAM' } as const;
    if (mock) await expect(boardApi.report(report)).resolves.toMatchObject({ status: 'PENDING' });
    await expect(boardApi.report(report)).rejects.toMatchObject({ status: 409, ...duplicate });
  });
});
