import { RecordFilters } from './RecordFilters';
import { MemberOnly } from './Auth';
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { boardApi } from '../api/board';
import { boardCategories, boardSearchTypes, boardSorts } from '../api/types';
import type {
  MyPostListItem,
  PageResponse,
  PostCategory,
  PostListItem,
  PostSearchQuery,
} from '../api/types';

import { BoardFeedback } from './BoardFeedback';

const labels: Record<PostCategory, string> = {
  FREE: '자유',
  TIP: '공략',
  QNA: '질문',
  RECORD: '엔딩 인증',
};
const sorts = { latest: '최신순', popular: '인기순', views: '조회순', comments: '댓글순' };
const searchTypes = { title: '제목', content: '내용', titleContent: '제목+내용', author: '작성자' };
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeZone: 'Asia/Seoul',
});

function readEnum<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((v) => v === value);
}
function readNumber(value: string | null, min: number, max: number, fallback: number) {
  const n = value && /^\d+$/.test(value) ? Number(value) : NaN;
  return Number.isInteger(n) && n >= min && n <= max ? n : fallback;
}
function readQuery(params: URLSearchParams): PostSearchQuery {
  const category = readEnum(boardCategories, params.get('category'));
  return {
    category,
    sort: readEnum(boardSorts, params.get('sort')) ?? 'latest',
    searchType: readEnum(boardSearchTypes, params.get('searchType')) ?? 'titleContent',
    q: params.get('q')?.trim().slice(0, 100) || undefined,
    page: readNumber(params.get('page'), 0, 2_147_483_647, 0),
    size: readNumber(params.get('size'), 1, 50, 20),
    ...(category === 'RECORD' && {
      serviceCode: params.get('serviceCode')?.trim().slice(0, 32) || undefined,
      areaCode: params.get('areaCode')?.trim().slice(0, 32) || undefined,
    }),
  };
}
function writeQuery(query: PostSearchQuery) {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (
      !value ||
      (name === 'size' && value === 20) ||
      (name === 'sort' && value === 'latest') ||
      (name === 'searchType' && value === 'titleContent') ||
      (query.category !== 'RECORD' && (name === 'serviceCode' || name === 'areaCode'))
    )
      continue;
    params.set(name, String(value));
  }
  return params;
}

type ListState =
  | { queryKey: string; data: PageResponse<PostListItem | MyPostListItem> }
  | { queryKey: string; error: unknown };

export default function BoardList({ mine = false }: { mine?: boolean }) {
  const [revision, setRevision] = useState(0);
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const serialized = writeQuery(query).toString();
  const queryKey = JSON.stringify([mine, revision, serialized]);
  const formKey = writeQuery({ ...query, page: undefined, sort: undefined }).toString();
  const [state, setState] = useState<ListState>();
  useEffect(() => {
    const controller = new AbortController();
    const query = readQuery(new URLSearchParams(serialized));
    (mine ? boardApi.myPosts(query) : boardApi.posts(query, controller.signal)).then(
      (data) => {
        if (!controller.signal.aborted) setState({ queryKey, data });
      },
      (error) => {
        if (!controller.signal.aborted) setState({ queryKey, error });
      },
    );
    return () => controller.abort();
  }, [mine, queryKey, serialized]);
  const current = state?.queryKey === queryKey ? state : undefined;
  const data = current && 'data' in current ? current.data : undefined;
  const change = (patch: PostSearchQuery) => setParams(writeQuery({ ...query, page: 0, ...patch }));
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    change({
      q: String(form.get('q') ?? '').trim(),
      searchType: readEnum(boardSearchTypes, String(form.get('searchType'))),
      serviceCode: String(form.get('serviceCode') ?? '').trim(),
      areaCode: String(form.get('areaCode') ?? '').trim(),
    });
  }
  const tabs: (PostCategory | undefined)[] = [undefined, ...boardCategories];
  const page = query.page ?? 0;
  return (
    <section className="board" aria-labelledby="board-title">
      <h1 id="board-title">{mine ? '내 글' : '사장님 커뮤니티'}</h1>
      <nav className="board-toolbar" aria-label="게시판 메뉴">
        <MemberOnly>
          <Link className="button" to="/board/write">
            글 쓰기
          </Link>
        </MemberOnly>
        {mine ? (
          <Link className="button" to="/board">
            전체 게시판
          </Link>
        ) : (
          <Link className="button" to="/board/me">
            내 글
          </Link>
        )}
      </nav>
      <nav className="choices board-tabs" aria-label="게시판 카테고리">
        {tabs.map((category) => (
          <Link
            key={category ?? 'all'}
            className={`button${query.category === category ? ' selected' : ''}`}
            aria-current={query.category === category ? 'page' : undefined}
            to={{ search: writeQuery({ ...query, category, page: 0 }).toString() }}
          >
            {category ? labels[category] : '전체'}
          </Link>
        ))}
      </nav>
      <div className="card board-controls">
        <label>
          정렬
          <select
            value={query.sort}
            onChange={(e) => change({ sort: readEnum(boardSorts, e.target.value) })}
          >
            {boardSorts.map((sort) => (
              <option key={sort} value={sort}>
                {sorts[sort]}
              </option>
            ))}
          </select>
        </label>
        <form key={formKey} className="board-search" onSubmit={search} aria-label="게시글 검색">
          <label>
            검색 종류
            <select name="searchType" defaultValue={query.searchType}>
              {boardSearchTypes.map((type) => (
                <option key={type} value={type}>
                  {searchTypes[type]}
                </option>
              ))}
            </select>
          </label>
          <label>
            검색어
            <input name="q" type="search" maxLength={100} defaultValue={query.q} />
          </label>
          {query.category === 'RECORD' && (
            <RecordFilters serviceCode={query.serviceCode} areaCode={query.areaCode} />
          )}
          <button type="submit">검색</button>
        </form>
      </div>
      {!current && <p role="status">게시글을 불러오는 중입니다.</p>}
      {current && 'error' in current && (
        <BoardFeedback
          error={current.error}
          onGuest={mine ? () => setRevision((n) => n + 1) : undefined}
        />
      )}
      {data && (
        <>
          <p className="board-count">게시글 {data.totalElements.toLocaleString('ko-KR')}건</p>
          {!data.content.length ? (
            data.totalElements > 0 ? (
              <div className="card">
                <p>이 페이지에는 글이 없습니다.</p>
                <button type="button" onClick={() => change({ page: 0 })}>
                  첫 페이지로
                </button>
              </div>
            ) : (
              <p className="card">조건에 맞는 게시글이 없습니다.</p>
            )
          ) : (
            <ul className="card board-posts" aria-label="게시글 목록">
              {data.content.map((p) => {
                const hidden = 'status' in p && p.status === 'HIDDEN';
                return (
                  <li key={p.postId}>
                    <div>
                      <span className="badge">{labels[p.category]}</span>{' '}
                      {p.isPinned && <span className="badge board-pinned">고정</span>}
                    </div>
                    {hidden ? (
                      <p className="board-post-title">
                        {p.title} <span className="badge">숨김</span>
                      </p>
                    ) : (
                      <Link className="board-post-title" to={`/board/posts/${p.postId}`}>
                        {p.title}
                      </Link>
                    )}
                    {!hidden && p.record && (
                      <p className="board-record">
                        {p.record.serviceName} · {p.record.areaName}
                      </p>
                    )}
                    {!hidden && (
                      <div className="board-meta">
                        <span>{p.authorNickname}</span>
                        <time dateTime={p.createdAt}>
                          {dateFormat.format(new Date(p.createdAt))}
                        </time>
                        <span>조회 {p.viewCount}</span>
                        <span>좋아요 {p.likeCount}</span>
                        <span>댓글 {p.commentCount}</span>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {(page > 0 || data.totalPages > 1) && (
            <nav className="board-pagination" aria-label="게시글 페이지">
              <button
                type="button"
                onClick={() =>
                  change({ page: Math.max(0, Math.min(page - 1, data.totalPages - 1)) })
                }
                disabled={page === 0}
              >
                이전
              </button>
              <span>
                {page + 1} / {Math.max(1, data.totalPages)}
              </span>
              <button
                type="button"
                onClick={() => change({ page: page + 1 })}
                disabled={page + 1 >= data.totalPages}
              >
                다음
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  );
}
