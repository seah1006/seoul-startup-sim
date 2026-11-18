import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { boardApi } from '../api/board';
import { api, ApiError, guestStorageKey, useMock } from '../api/client';
import type { GameSummary, PostDetail, UpdatePostRequest } from '../api/types';
import { BoardFeedback } from './BoardFeedback';
import { BoardRecordCard } from './BoardRecordCard';

const categories = { FREE: '자유', TIP: '공략', QNA: '질문' } as const;
const positiveId = (raw: string) =>
  /^\d+$/.test(raw) && Number.isSafeInteger(Number(raw)) && Number(raw) > 0;
const codeIs = (error: unknown, code: string) => error instanceof ApiError && error.code === code;

type EditorProps = { post?: PostDetail; summary?: GameSummary; onRefresh?: () => void };
function Editor({ post, summary, onRefresh }: EditorProps) {
  const navigate = useNavigate();
  const isRecord = Boolean(summary || post?.category === 'RECORD');
  const [category, setCategory] = useState<keyof typeof categories>(
    post?.category !== 'RECORD' ? (post?.category ?? 'FREE') : 'FREE',
  );
  const [title, setTitle] = useState(post?.title ?? '');
  const [content, setContent] = useState(post?.content ?? '');
  const [validation, setValidation] = useState('');
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const conflict = codeIs(error, 'POST_VERSION_CONFLICT');
  const record = summary ?? post?.record;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (lock.current || conflict) return;
    setError(undefined);
    const nextTitle = title.trim(),
      nextContent = content.trim();
    if (!nextTitle || title.length > 100 || !nextContent || content.length > 10000) {
      setValidation(
        !nextTitle || title.length > 100
          ? '제목은 1~100자로 입력해 주세요.'
          : '본문은 1~10000자로 입력해 주세요.',
      );
      return;
    }
    setValidation('');
    const fields = { title: nextTitle, content: nextContent };
    lock.current = true;
    setBusy(true);
    try {
      const updated: UpdatePostRequest['category'] = isRecord ? 'RECORD' : category;
      const result = post
        ? await boardApi.updatePost(post.postId, {
            ...fields,
            category: updated,
            version: post.version,
          })
        : await boardApi.createPost(
            summary
              ? { ...fields, category: 'RECORD', gameId: summary.gameId }
              : { ...fields, category },
          );
      navigate(`/board/posts/${result.postId}`, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }

  return (
    <form className="card board-editor" noValidate onSubmit={submit}>
      {isRecord ? (
        <p>
          카테고리: <strong>엔딩 인증</strong>
        </p>
      ) : (
        <label>
          카테고리
          <select
            value={category}
            disabled={busy}
            onChange={(e) => setCategory(e.target.value as typeof category)}
          >
            {Object.entries(categories).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      )}
      {record && <BoardRecordCard record={record} preview />}
      {summary && (
        <p className="notice">
          카드 값과 이름이 공개됩니다. {useMock && '현재 화면은 연습 데이터입니다.'}
        </p>
      )}
      <label>
        제목
        <input
          maxLength={100}
          required
          value={title}
          disabled={busy}
          onChange={(e) => setTitle(e.target.value)}
          aria-describedby="board-title-length"
        />
        <small id="board-title-length">{title.length}/100자</small>
      </label>
      <label>
        본문
        <textarea
          maxLength={10000}
          required
          value={content}
          disabled={busy}
          onChange={(e) => setContent(e.target.value)}
          aria-describedby="board-content-length"
        />
        <small id="board-content-length">{content.length}/10000자</small>
      </label>
      {validation && (
        <p role="alert" className="error">
          {validation}
        </p>
      )}
      <BoardFeedback
        error={error}
        onGuest={() => setError(undefined)}
        onRefresh={conflict ? onRefresh : undefined}
      />
      {codeIs(error, 'RECORD_ALREADY_POSTED') && (
        <Link to="/board/me?category=RECORD">내 글에서 기존 인증글 확인</Link>
      )}
      <div className="choices">
        <button type="submit" className="primary" disabled={busy || conflict}>
          {busy ? '저장 중…' : post ? '수정 저장' : '글 등록'}
        </button>
        <Link className="button" to={post ? `/board/posts/${post.postId}` : '/board'}>
          취소
        </Link>
      </div>
    </form>
  );
}

function RecordEditor({ gameId }: { gameId: number }) {
  const [state, setState] = useState<{ summary: GameSummary } | { error: unknown }>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void api.summary(gameId).then(
      (summary) => {
        if (active) setState({ summary });
      },
      (error) => {
        if (active) setState({ error });
      },
    );
    return () => {
      active = false;
    };
  }, [gameId, revision]);
  if (!state) return <p role="status">엔딩 요약을 불러오는 중입니다.</p>;
  if ('summary' in state) return <Editor summary={state.summary} />;
  const error = state.error;
  if (error instanceof ApiError && [404, 409].includes(error.status)) {
    return (
      <p className="card" role="alert">
        {error.status === 404 ? '인증할 게임을 찾을 수 없어요' : '아직 끝나지 않은 게임이에요'}{' '}
        <Link to="/board">게시판으로 돌아가기</Link>
      </p>
    );
  }
  return (
    <BoardFeedback
      error={error}
      onGuest={() => {
        setState(undefined);
        setRevision((n) => n + 1);
      }}
    />
  );
}

export function BoardWrite() {
  const [params] = useSearchParams();
  const category = params.get('category');
  const gameId = params.get('gameId');
  const record = category === 'RECORD' || (category === null && gameId !== null);
  return (
    <section className="board-detail">
      <Link to="/board">← 게시판</Link>
      <h1>{record ? '엔딩 인증글 쓰기' : '게시글 쓰기'}</h1>
      {record ? (
        gameId && positiveId(gameId) ? (
          <RecordEditor gameId={Number(gameId)} key={gameId} />
        ) : (
          <p role="alert" className="error">
            인증할 게임을 확인할 수 없습니다. 엔딩 화면에서 시작해 주세요.
          </p>
        )
      ) : (
        <Editor />
      )}
    </section>
  );
}

function EditLoader({ postId }: { postId: number }) {
  const [state, setState] = useState<{ post: PostDetail } | { error: unknown }>();
  const [revision, setRevision] = useState(0);
  const reload = () => {
    setState(undefined);
    setRevision((value) => value + 1);
  };
  useEffect(() => {
    let active = true;
    void boardApi.post(postId).then(
      (post) => {
        if (!active) return;
        if (post.isMine) setState({ post });
        else
          setState({
            error: localStorage.getItem(guestStorageKey)
              ? new ApiError(403, '작성자만 게시글을 수정할 수 있습니다.')
              : new ApiError(401, '게스트 인증이 필요합니다.'),
          });
      },
      (error) => {
        if (active) setState({ error });
      },
    );
    return () => {
      active = false;
    };
  }, [postId, revision]);
  if (!state) return <p role="status">게시글을 불러오는 중입니다.</p>;
  return 'post' in state ? (
    <Editor key={revision} post={state.post} onRefresh={reload} />
  ) : (
    <BoardFeedback error={state.error} onGuest={reload} />
  );
}

export function BoardEdit() {
  const { postId = '' } = useParams();
  return (
    <section className="board-detail">
      <Link to={positiveId(postId) ? `/board/posts/${postId}` : '/board'}>← 돌아가기</Link>
      <h1>게시글 수정</h1>
      {positiveId(postId) ? (
        <EditLoader key={postId} postId={Number(postId)} />
      ) : (
        <p role="alert" className="error">
          게시글을 찾을 수 없습니다.
        </p>
      )}
    </section>
  );
}
