import { MemberOnly } from './Auth';
import { useEffect, useRef, useState } from 'react';
import { boardApi } from '../api/board';
import type { CommentItem, PageResponse } from '../api/types';
import { BoardFeedback } from './BoardFeedback';

const size = 20;
type Editor = { kind: 'edit' | 'delete' | 'reply'; id: number };
type PageResult = { key: string; data?: PageResponse<CommentItem>; error?: unknown };

function useCommentPage(
  postId: number,
  parentId: number | null | undefined,
  page: number,
  revision: string,
) {
  const [result, setResult] = useState<PageResult>();
  const key = `${postId}:${parentId}:${page}:${revision}`;
  useEffect(() => {
    if (parentId === undefined) return;
    let active = true;
    const options = { page, size };
    const request =
      parentId === null
        ? boardApi.comments(postId, options)
        : boardApi.replies(postId, parentId, options);
    void request.then(
      (data) => {
        if (active) setResult({ key, data });
      },
      (error) => {
        if (active) setResult({ key, error });
      },
    );
    return () => {
      active = false;
    };
  }, [postId, parentId, page, key]);
  return result?.key === key ? result : undefined;
}

function Pager({
  label,
  page,
  totalPages,
  disabled,
  onChange,
}: {
  label: string;
  page: number;
  totalPages: number;
  disabled: boolean;
  onChange: (p: number) => void;
}) {
  if (Math.max(page + 1, totalPages) < 2) return null;
  return (
    <nav className="choices" aria-label={label}>
      <button type="button" disabled={disabled || !page} onClick={() => onChange(page - 1)}>
        이전
      </button>
      <span>
        {page + 1} / {Math.max(1, totalPages)}
      </span>
      <button
        type="button"
        disabled={disabled || page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        다음
      </button>
    </nav>
  );
}

export function BoardComments({
  postId,
  count,
  authRevision,
  onGuest,
  onReport,
  onCountChange,
}: {
  postId: number;
  count: number;
  authRevision: number;
  onGuest: () => void;
  onReport: (id: number) => void;
  onCountChange: (delta: number) => void;
}) {
  const [page, setPage] = useState(0);
  const [thread, setThread] = useState<number>();
  const [replyPage, setReplyPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [rootDraft, setRootDraft] = useState('');
  const [draft, setDraft] = useState('');
  const [editor, setEditor] = useState<Editor>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const key = `${authRevision}:${revision}`;
  const roots = useCommentPage(postId, null, page, key);
  const replies = useCommentPage(postId, thread, replyPage, key);

  function closeEditor() {
    setEditor(undefined);
    setDraft('');
    setError(null);
  }
  function reload() {
    closeEditor();
    setRevision((v) => v + 1);
  }
  function showThread(id: number | undefined) {
    closeEditor();
    setThread(id);
    setReplyPage(0);
  }
  function turnPage(next: number) {
    showThread(undefined);
    setPage(next);
  }
  function start(kind: Editor['kind'], c: CommentItem) {
    setEditor({ kind, id: c.commentId });
    setDraft(kind === 'edit' ? c.content : '');
    setError(null);
  }
  async function mutate(action: () => Promise<unknown>, done: () => void) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
      done();
      reload();
    } catch (e) {
      setError(e);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  function add(parent?: CommentItem) {
    const content = (parent ? draft : rootDraft).trim();
    if (!content || content.length > 1000) return;
    void mutate(
      () => boardApi.createComment(postId, { content, parentId: parent?.commentId }),
      () => {
        onCountChange(1);
        if (parent) {
          setThread(parent.commentId);
          setReplyPage(Math.floor(parent.replyCount / size));
        } else {
          setRootDraft('');
          turnPage(Math.floor((roots?.data?.totalElements ?? 0) / size));
        }
      },
    );
  }
  function remove(c: CommentItem) {
    if (c.version === null) return;
    const version = c.version;
    void mutate(
      () => boardApi.deleteComment(postId, c.commentId, version),
      () => {
        onCountChange(-1);
        if (
          c.parentId === null &&
          c.replyCount === 0 &&
          roots?.data?.content.length === 1 &&
          page > 0
        )
          turnPage(page - 1);
        if (c.parentId !== null && replies?.data?.content.length === 1 && replyPage > 0)
          setReplyPage(replyPage - 1);
        if (
          c.parentId !== null &&
          roots?.data?.content.some(
            (root) => root.commentId === c.parentId && root.isPlaceholder && root.replyCount === 1,
          )
        ) {
          showThread(undefined);
          if (roots.data.content.length === 1 && page > 0) turnPage(page - 1);
        }
      },
    );
  }
  function renderComment(c: CommentItem) {
    const editing = editor?.id === c.commentId ? editor.kind : undefined;
    return (
      <article aria-label={c.parentId === null ? '댓글' : '대댓글'} className="board-comment">
        {c.authorNickname && (
          <p>
            <strong>{c.authorNickname}</strong>
          </p>
        )}
        <p className="board-plain-text">{c.content}</p>
        <div className="choices">
          <MemberOnly>
            {!c.isPlaceholder && c.parentId === null && (
              <button type="button" disabled={busy} onClick={() => start('reply', c)}>
                답글 쓰기
              </button>
            )}
            {!c.isPlaceholder &&
              (c.isMine ? (
                <>
                  <button type="button" disabled={busy} onClick={() => start('edit', c)}>
                    댓글 수정
                  </button>
                  <button type="button" disabled={busy} onClick={() => start('delete', c)}>
                    댓글 삭제
                  </button>
                </>
              ) : (
                <button type="button" disabled={busy} onClick={() => onReport(c.commentId)}>
                  댓글 신고
                </button>
              ))}
          </MemberOnly>
          {c.replyCount > 0 && (
            <button
              type="button"
              disabled={busy}
              aria-expanded={thread === c.commentId}
              onClick={() => showThread(thread === c.commentId ? undefined : c.commentId)}
            >
              {thread === c.commentId ? '답글 접기' : `답글 ${c.replyCount}개 보기`}
            </button>
          )}
        </div>
        {(editing === 'edit' || editing === 'reply') && (
          <MemberOnly>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (editing === 'reply') add(c);
                else if (c.version !== null && draft.trim()) {
                  const version = c.version;
                  void mutate(
                    () =>
                      boardApi.updateComment(postId, c.commentId, {
                        content: draft.trim(),
                        version,
                      }),
                    () => {},
                  );
                }
              }}
            >
              <label>
                {editing === 'edit' ? '수정할 댓글' : '답글 내용'}
                <textarea
                  value={draft}
                  maxLength={1000}
                  required
                  disabled={busy}
                  onChange={(e) => setDraft(e.target.value)}
                />
              </label>
              <div className="choices">
                <button type="submit" disabled={busy || !draft.trim()}>
                  {editing === 'edit' ? '수정 저장' : '답글 등록'}
                </button>
                <button type="button" disabled={busy} onClick={closeEditor}>
                  취소
                </button>
              </div>
            </form>
          </MemberOnly>
        )}
        {editing === 'delete' && (
          <div className="choices">
            <p>댓글을 삭제할까요?</p>
            <button type="button" disabled={busy} onClick={() => remove(c)}>
              댓글 삭제 확인
            </button>
            <button type="button" disabled={busy} onClick={closeEditor}>
              취소
            </button>
          </div>
        )}
        {editing && <BoardFeedback error={error} onGuest={onGuest} onRefresh={reload} />}
      </article>
    );
  }

  return (
    <section className="card" aria-label="댓글 영역">
      <h2>댓글 {count}</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          closeEditor();
          add();
        }}
      >
        <label>
          댓글 내용
          <textarea
            value={rootDraft}
            required
            maxLength={1000}
            disabled={busy}
            onChange={(e) => setRootDraft(e.target.value)}
          />
        </label>
        <button
          className="primary"
          type="submit"
          disabled={busy || !roots?.data || !rootDraft.trim()}
        >
          댓글 등록
        </button>
      </form>
      {!editor && <BoardFeedback error={error} onGuest={onGuest} />}
      {!roots && <p role="status">댓글을 불러오는 중…</p>}
      <BoardFeedback error={roots?.error} onGuest={onGuest} />
      {roots?.data?.content.length === 0 &&
        (page === 0 ? (
          <p>아직 댓글이 없습니다.</p>
        ) : (
          <button onClick={() => turnPage(0)}>댓글 첫 페이지로</button>
        ))}
      {roots?.data && (
        <ul className="board-comments" aria-label="댓글 목록">
          {roots.data.content.map((c) => (
            <li key={c.commentId}>
              {renderComment(c)}
              {thread === c.commentId && c.replyCount > 0 && (
                <section className="board-replies" aria-label="대댓글 영역">
                  {!replies && <p role="status">답글을 불러오는 중…</p>}
                  <BoardFeedback error={replies?.error} onGuest={onGuest} />
                  {replies?.data && (
                    <>
                      <ul className="board-comments" aria-label="대댓글 목록">
                        {replies.data.content.map((reply) => (
                          <li key={reply.commentId}>{renderComment(reply)}</li>
                        ))}
                      </ul>
                      <Pager
                        label="대댓글 페이지"
                        page={replyPage}
                        totalPages={replies.data.totalPages}
                        disabled={busy}
                        onChange={(p) => {
                          closeEditor();
                          setReplyPage(p);
                        }}
                      />
                    </>
                  )}
                </section>
              )}
            </li>
          ))}
        </ul>
      )}
      {roots?.data && (
        <Pager
          label="댓글 페이지"
          page={page}
          totalPages={roots.data.totalPages}
          disabled={busy}
          onChange={turnPage}
        />
      )}
    </section>
  );
}
