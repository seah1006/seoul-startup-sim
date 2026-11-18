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

const testCommentPost = 2;
it('목에서 대댓글 페이징, 소속/깊이 확인, 댓글 신고, 429를 계약대로 응답한다', async () => {
  const { boardApi, guestStorageKey } = await client(true);
  const now = vi.spyOn(Date, 'now').mockReturnValue(1_800_000_000_000);
  try {
    localStorage.setItem(guestStorageKey, 'mock-guest');
    const original = await boardApi.post(testCommentPost);
    await expect(boardApi.createComment(testCommentPost, { content: '3단계', parentId: 4 }))
      .rejects.toMatchObject({ status: 400, code: 'COMMENT_DEPTH_EXCEEDED' });
    await expect(boardApi.createComment(3, { content: '다른 게시글', parentId: 1 })).rejects.toMatchObject({ status: 404 });
    for (let i = 0; i < 10; i++) await boardApi.createComment(testCommentPost, { content: `답글 ${i}`, parentId: 1 });
    await expect(boardApi.createComment(testCommentPost, { content: '제한 초과' })).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(await boardApi.replies(testCommentPost, 1, { page: 1, size: 5 })).toMatchObject({ page: 1, totalElements: 11, totalPages: 3 });
    expect(await boardApi.post(testCommentPost)).toMatchObject({ version: original.version, commentCount: original.commentCount + 10 });
    const report = { targetType: 'COMMENT', targetId: 1, reason: 'SPAM' } as const;
    await expect(boardApi.report({ ...report, targetId: 2 })).rejects.toMatchObject({ status: 403, code: 'FORBIDDEN' });
    await expect(boardApi.report({ ...report, targetId: 3 })).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' });
    await expect(boardApi.report(report)).resolves.toMatchObject({ status: 'PENDING' });
    await expect(boardApi.report(report)).rejects.toMatchObject({ status: 409, code: 'ALREADY_REPORTED' });
  } finally { now.mockRestore(); }
});

it('선택 인증 401에서 토큰 없이 한 번만 재시도하고, 필수 인증 401은 보존한다', async () => {
  const request = vi.fn(async (_: string, options: RequestInit) => (options.headers as Record<string, string>)['X-Guest-Token']
    ? Response.json({ code: 'UNAUTHORIZED', message: '만료된 토큰' }, { status: 401 }) : Response.json({ likedByMe: false }));
  vi.stubGlobal('fetch', request);
  const { boardApi, guestStorageKey } = await client(false);
  localStorage.setItem(guestStorageKey, 'expired');
  await expect(boardApi.post(2)).resolves.toMatchObject({ likedByMe: false });
  expect(request).toHaveBeenCalledTimes(2);
  expect(request).toHaveBeenLastCalledWith('/api/board/posts/2', expect.objectContaining({ headers: {} }));
  expect(localStorage.getItem(guestStorageKey)).toBe('expired');
  await expect(boardApi.like(2, true)).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED', message: '만료된 토큰' });
  expect(request).toHaveBeenCalledTimes(3);
});

it('실제 모드 요청 경로: 홈 인기글, 내 글 인증, 종료 요약 및 RECORD 작성 본문 (fetch 대역)', async () => {
  const request = vi.fn(async (url: string) => url.endsWith('/summary')
    ? Response.json({ gameId: 104, endingType: 'LONG_RUNNING' })
    : Response.json({ postId: 31, content: [] }));
  vi.stubGlobal('fetch', request);
  const { api, boardApi, guestStorageKey } = await client(false);
  const token = 'fixture-token';
  localStorage.setItem(guestStorageKey, token);
  await boardApi.posts({ sort: 'popular', size: 3 });
  await boardApi.myPosts({ category: 'RECORD', page: 0, size: 20 });
  await expect(api.summary(104)).resolves.toMatchObject({ gameId: 104 });
  const body = { category: 'RECORD', gameId: 104, title: '제목', content: '본문' } as const;
  await boardApi.createPost(body);
  expect(request).toHaveBeenNthCalledWith(1, '/api/board/posts?sort=popular&size=3', expect.objectContaining({ headers: {} }));
  expect(request).toHaveBeenNthCalledWith(2, '/api/board/me/posts?category=RECORD&page=0&size=20', expect.objectContaining({ headers: { 'X-Guest-Token': token } }));
  expect(request).toHaveBeenNthCalledWith(3, '/api/games/104/summary', expect.objectContaining({ method: 'GET', headers: expect.objectContaining({ 'X-Guest-Token': token }) }));
  expect(request).toHaveBeenNthCalledWith(4, '/api/board/posts', expect.objectContaining({ method: 'POST', body: JSON.stringify(body) }));
});