import { boardCategories, boardSearchTypes, boardSorts } from '../types';
import type { BoardErrorCode, CreateReportRequest, PageResponse, PostDetail, PostListItem, RecordSnapshot, UpdatePostRequest } from '../types';

type MockPost = { item: PostListItem; content: string; version: number; mine: boolean; deleted?: boolean };
const key = 'sss-mock-board-v1';
const counter = { popular: 'likeCount', views: 'viewCount', comments: 'commentCount' } as const;
const stories = ['첫 창업 회고', '단골을 만든 운영 기록', '고정비를 줄인 방법', '다음 분기 전략', '골목 상권 도전기', '첫 흑자 분기를 돌아보며'];
const records: RecordSnapshot[] = [
  { gameId: 101, endingType: 'LONG_RUNNING', serviceCode: 'CS100010', serviceName: '커피-음료', areaCode: '3110123', areaName: '망원역', survivedQuarters: 14, totalProfit: 182000000, shopName: '가상 구름모래 찻집', ownerName: '가상 사장 하루', shareUrl: '/share/101' },
  { gameId: 102, endingType: 'SOLD', serviceCode: 'CS100008', serviceName: '분식전문점', areaCode: '3110123', areaName: '망원역', survivedQuarters: 8, totalProfit: 45000000, shopName: '가상 초승달 분식', ownerName: '가상 사장 나래', shareUrl: '/share/102' },
  { gameId: 103, endingType: 'CLOSED', serviceCode: 'CS100010', serviceName: '커피-음료', areaCode: '3110837', areaName: '성수역', survivedQuarters: 4, totalProfit: -17000000, shopName: '가상 파란행성 다방', ownerName: '가상 사장 바다', shareUrl: '/share/103' },
];

function seed() {
  return Array.from({ length: 29 }, (_, i): MockPost => {
    const record = records[i - 1] ?? null;
    const date = new Date(Date.UTC(2026, 10, 18, 3, -i * 20)).toISOString();
    return { item: {
      postId: i + 1, category: record ? 'RECORD' : boardCategories[i % 3],
      title: i === 0 ? '사장님 커뮤니티 이용 안내' : record ? `${record.shopName} · ${stories[i - 1]}` : `${stories[i % stories.length]} (${i + 1})`,
      authorNickname: i === 0 ? '운영팀' : `연습 사장님 ${i % 5 + 1}`, createdAt: date, updatedAt: date,
      viewCount: i * 7 % 83, likeCount: i % 11, commentCount: i % 7, isPinned: i === 0, record,
    }, content: i === 0 ? '게임 속 가상 창업 경험을 공유해 주세요.' : '첫 분기에는 고정비를 줄였습니다.\n다음에는 운영 시간을 바꿔 볼 계획입니다.', version: 0, mine: i === 1 || i === 4 };
  });
}

type MockDb = { posts: MockPost[]; likes: number[]; views: string[]; reports: string[] };
function load(): MockDb {
  try { const raw = localStorage.getItem(key); if (raw) return JSON.parse(raw); } catch { /* 초기 목 데이터 사용 */ }
  return { posts: seed(), likes: [], views: [], reports: [] };
}
const json = (body: unknown, status = 200, headers?: HeadersInit) => Response.json(body, { status, headers });
const privateHeaders = { 'Cache-Control': 'no-store' };
const error = (status: number, code: BoardErrorCode, message: string) => json({ code, message, timestamp: new Date().toISOString() }, status);
const invalid = () => error(400, 'VALIDATION_FAILED', '입력값을 확인해 주세요.');
const missing = () => error(404, 'NOT_FOUND', '대상을 찾을 수 없습니다.');
const forbidden = () => error(403, 'FORBIDDEN', '권한이 없습니다.');
const text = (value: unknown, limit: number) => typeof value === 'string' && value.trim().length >= 1 && value.trim().length <= limit;
const integer = (n: unknown, min: number): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= min;
const enumValue = <T extends string>(values: readonly T[], value: unknown): value is T => values.includes(value as T);
const readInt = (q: URLSearchParams, name: string, fallback: number) => {
  const value = q.get(name);
  return value === null ? fallback : /^\d+$/.test(value) ? Number(value) : NaN;
};

function list(posts: MockPost[], params: URLSearchParams, mine: boolean) {
  const page = readInt(params, 'page', 0), size = readInt(params, 'size', 20);
  const sort = params.get('sort') ?? 'latest', category = params.get('category');
  const searchType = params.get('searchType') ?? 'titleContent', q = params.get('q')?.trim().toLowerCase() ?? '';
  const service = params.get('serviceCode')?.trim(), area = params.get('areaCode')?.trim();
  if (!integer(page, 0) || !integer(size, 1) || size > 50 || !enumValue(boardSorts, sort)
    || !enumValue(boardSearchTypes, searchType) || (category !== null && !enumValue(boardCategories, category))
    || q.length > 100 || (service !== undefined && !text(service, 32)) || (area !== undefined && !text(area, 32))
    || ((service || area) && category !== null && category !== 'RECORD')) return invalid();
  const rows = posts.filter(({ item: p, content, deleted, mine: owner }) => {
    if (deleted || (mine && !owner) || (category && p.category !== category)) return false;
    if (service && service !== p.record?.serviceCode) return false;
    if (area && area !== p.record?.areaCode) return false;
    const targets = { title: [p.title], content: [content], titleContent: [p.title, content], author: [p.authorNickname] };
    return !q || targets[searchType].some(s => s.toLowerCase().includes(q));
  }).sort(({ item: a }, { item: b }) => Number(b.isPinned) - Number(a.isPinned)
    || (sort === 'latest' ? 0 : b[counter[sort]] - a[counter[sort]])
    || b.createdAt.localeCompare(a.createdAt) || b.postId - a.postId);
  return json({ content: rows.slice(page * size, (page + 1) * size).map(p => mine ? { ...p.item, status: 'ACTIVE', version: p.version } : p.item),
    page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) } satisfies PageResponse<PostListItem>,
  200, mine ? privateHeaders : undefined);
}

export function mockBoardRequest(url: string, method: string, token: string | null, body?: unknown): Response {
  const { pathname, searchParams } = new URL(url, 'http://mock.local');
  const db = load();
  const save = () => localStorage.setItem(key, JSON.stringify(db));
  const authenticated = token === 'mock-guest';
  const mine = pathname === '/board/me/posts';
  const recordMatch = pathname.match(/^\/board\/records\/(\d+)$/);
  const publicRead = method === 'GET' && (pathname === '/board/posts' || recordMatch);
  if (!publicRead && !authenticated && (method !== 'GET' || mine || token !== null)) {
    return error(401, 'UNAUTHORIZED', '유효한 게스트 토큰이 필요합니다.');
  }
  const detail = (p: MockPost): PostDetail => ({ ...p.item, content: p.content, version: p.version,
    isMine: authenticated && p.mine, likedByMe: authenticated && db.likes.includes(p.item.postId) });
  if (method === 'GET' && (pathname === '/board/posts' || mine)) return list(db.posts, searchParams, mine);
  if (method === 'GET' && recordMatch) {
    const p = db.posts.find(p => !p.deleted && p.item.record?.gameId === Number(recordMatch[1]))?.item;
    return p ? json({ postId: p.postId, title: p.title, commentCount: p.commentCount, postUrl: `/board/posts/${p.postId}` }, 200, privateHeaders)
      : new Response(null, { status: 204, headers: privateHeaders });
  }
  if (method === 'POST' && pathname === '/board/reports') {
    const b = body as CreateReportRequest | undefined;
    if (!b || !['POST', 'COMMENT'].includes(b.targetType) || !integer(b.targetId, 1)
      || !['SPAM', 'ABUSE', 'ADULT', 'ETC'].includes(b.reason)
      || (b.detail !== undefined && (typeof b.detail !== 'string' || b.detail.trim().length > 500))
      || (b.reason === 'ETC' && !text(b.detail, 500))) return invalid();
    const target = b.targetType === 'POST' ? db.posts.find(p => !p.deleted && p.item.postId === b.targetId) : undefined;
    if (!target) return missing();
    if (target.mine) return forbidden();
    const id = `${b.targetType}:${b.targetId}`;
    if (db.reports.includes(id)) return error(409, 'ALREADY_REPORTED', '이미 신고한 대상입니다.');
    db.reports.push(id); save();
    return json({ reportId: db.reports.length, status: 'PENDING' }, 201);
  }
  const match = pathname.match(/^\/board\/posts\/(\d+)(\/.*)?$/);
  if (!match) return missing();
  const postId = Number(match[1]), sub = match[2];
  if (!integer(postId, 1)) return invalid();
  const p = db.posts.find(p => !p.deleted && p.item.postId === postId);
  if (!sub && (method === 'PUT' || method === 'DELETE')) {
    const b = body as (UpdatePostRequest & { gameId?: number }) | undefined;
    const version = method === 'PUT' ? b?.version : readInt(searchParams, 'version', NaN);
    if (!integer(version, 0)) return invalid();
    if (method === 'PUT' && (!b || !enumValue(boardCategories, b.category) || !text(b.title, 100) || !text(b.content, 10000) || 'gameId' in b)) return invalid();
    if (!p) return missing();
    if (!p.mine) return forbidden();
    if (b && (p.item.category === 'RECORD') !== (b.category === 'RECORD')) return invalid();
    if (p.version !== version) return error(409, 'POST_VERSION_CONFLICT', '게시글이 변경되었습니다. 최신 게시글을 확인해 주세요.');
    if (method === 'DELETE') { p.deleted = true; save(); return new Response(null, { status: 204 }); }
    p.content = b!.content.trim(); p.version++;
    Object.assign(p.item, { category: b!.category, title: b!.title.trim(), updatedAt: new Date().toISOString() });
    save(); return json(detail(p));
  }
  if (!p) return missing();
  if (!sub && method === 'GET') {
    const day = new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10);
    const view = `${postId}:${day}`;
    if (authenticated && !db.views.includes(view)) { p.item.viewCount++; db.views.push(view); save(); }
    return json(detail(p), 200, privateHeaders);
  }
  if (sub === '/like' && (method === 'PUT' || method === 'DELETE')) {
    const index = db.likes.indexOf(postId);
    if (method === 'PUT' && index === -1) { db.likes.push(postId); p.item.likeCount++; }
    if (method === 'DELETE' && index !== -1) { db.likes.splice(index, 1); p.item.likeCount--; }
    save(); return new Response(null, { status: 204 });
  }
  if (sub === '/comments' && method === 'GET') {
    const page = readInt(searchParams, 'page', 0), size = readInt(searchParams, 'size', 20);
    if (!integer(page, 0) || !integer(size, 1) || size > 50) return invalid();
    return json({ content: [], page, size, totalElements: 0, totalPages: 0 }, 200, privateHeaders);
  }
  return missing();
}
