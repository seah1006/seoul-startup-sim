import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { boardApi } from '../api/board';
import { api } from '../api/client';
import type { PostListItem, RecordPostLink } from '../api/types';

export function PopularBoardPosts() {
  const [state, setState] = useState<PostListItem[] | 'error'>();
  useEffect(() => {
    const controller = new AbortController();
    void boardApi.posts({ sort: 'popular', size: 3 }, controller.signal).then(
      page => { if (!controller.signal.aborted) setState(page.content.slice(0, 3)); },
      () => { if (!controller.signal.aborted) setState('error'); },
    );
    return () => controller.abort();
  }, []);
  return <article className="card"><p className="eyebrow">COMMUNITY</p><h2>커뮤니티 인기글</h2>
    {state === 'error' ? <p>인기글을 불러오지 못했습니다.</p> : !state ? <p role="status">인기글을 불러오는 중입니다.</p>
      : state.length ? <ul>{state.map(post => <li key={post.postId}><Link to={`/board/posts/${post.postId}`}>{post.title}</Link></li>)}</ul>
        : <p>아직 게시글이 없습니다.</p>}
    <Link className="button" to="/board">커뮤니티 둘러보기</Link>
  </article>;
}

type EndingState = 'available' | 'hidden' | 'unavailable' | RecordPostLink;
export function EndingBoardEntry({ gameId }: { gameId: number }) {
  const [state, setState] = useState<EndingState>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    async function lookup(): Promise<EndingState> {
      const link = await boardApi.recordLink(gameId);
      if (link) return link;
      if (!active) return 'unavailable';
      // TASK-005B unlocks real writing by exposing the owner-only summary.
      const summary = await api.summary(gameId);
      if (summary.gameId !== gameId || !summary.endingType) return 'unavailable';
      for (let page = 0; active; page++) {
        const mine = await boardApi.myPosts({ category: 'RECORD', page, size: 50 });
        const linked = mine.content.find(post => post.record?.gameId === gameId);
        if (linked) return linked.status === 'HIDDEN' ? 'hidden'
          : { postId: linked.postId, postUrl: `/board/posts/${linked.postId}`, title: linked.title, commentCount: linked.commentCount };
        if (page + 1 >= mine.totalPages) return 'available';
      }
      return 'unavailable';
    }
    void lookup().then(
      result => { if (active) setState(result); },
      () => { if (active) setState('unavailable'); },
    );
    return () => { active = false; };
  }, [gameId, revision]);
  if (!state) return <p role="status">엔딩 인증글을 확인하는 중입니다.</p>;
  if (typeof state === 'object') return <Link className="button" to={state.postUrl}>인증글 보기</Link>;
  if (state === 'hidden') return <p>이 게임의 인증글은 숨김 상태입니다. <Link to="/board/me?category=RECORD">내 글에서 확인</Link></p>;
  if (state === 'available') return <Link className="button" to={`/board/write?category=RECORD&gameId=${gameId}`}>엔딩 인증하기</Link>;
  return <p>엔딩 인증을 지금 사용할 수 없습니다. <button type="button" onClick={() => { setState(undefined); setRevision(n => n + 1); }}>다시 확인</button></p>;
}