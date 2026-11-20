import { MemberOnly } from './Auth';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { boardApi } from '../api/board';
import type { PostDetail } from '../api/types';
import { BoardFeedback, ReportDialog } from './BoardFeedback';
import type { ReportTarget } from './BoardFeedback';
import { BoardComments } from './BoardComments';
import { BoardRecordCard } from './BoardRecordCard';

const category = { FREE: '자유', TIP: '공략', QNA: '질문', RECORD: '엔딩 인증' };
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

function PostView({ postId }: { postId: number }) {
  const navigate = useNavigate();
  const [post, setPost] = useState<PostDetail>();
  const [loadError, setLoadError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [report, setReport] = useState<ReportTarget>();
  const [reported, setReported] = useState(false);
  const lock = useRef(false);
  const refresh = () => {
    setActionError(null);
    setLoadError(null);
    setLoading(true);
    setConfirmDelete(false);
    setRevision((v) => v + 1);
  };
  useEffect(() => {
    let active = true;
    void boardApi.post(postId).then(
      (data) => {
        if (active) {
          setPost(data);
          setLoading(false);
        }
      },
      (e) => {
        if (active) {
          setLoadError(e);
          setLoading(false);
        }
      },
    );
    return () => {
      active = false;
    };
  }, [postId, revision]);

  async function like(current: PostDetail) {
    if (lock.current || loading) return;
    lock.current = true;
    setBusy(true);
    setActionError(null);
    const liked = !current.likedByMe;
    setPost(
      (p) =>
        p && { ...p, likedByMe: liked, likeCount: Math.max(0, p.likeCount + (liked ? 1 : -1)) },
    );
    try {
      await boardApi.like(postId, liked);
    } catch (e) {
      setPost((p) => p && { ...p, likedByMe: current.likedByMe, likeCount: current.likeCount });
      setActionError(e);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function remove(current: PostDetail) {
    if (lock.current || loading) return;
    lock.current = true;
    setBusy(true);
    setActionError(null);
    try {
      await boardApi.deletePost(postId, current.version);
      navigate('/board', { replace: true });
    } catch (e) {
      setConfirmDelete(false);
      setActionError(e);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  function openReport(target: ReportTarget) {
    setReported(false);
    setReport(target);
  }

  return (
    <div className="board-detail">
      <Link to="/board">게시판 목록</Link>
      <BoardFeedback error={loadError} onGuest={refresh} />
      {!post && loading && <p role="status">게시글을 불러오는 중…</p>}
      {post && !loadError && (
        <>
          <article className="card">
            <p>
              {category[post.category]}
              {post.isPinned ? ' · 고정' : ''}
            </p>
            <h1>{post.title}</h1>
            <p>
              {post.authorNickname} ·{' '}
              <time dateTime={post.createdAt}>{dateFormat.format(new Date(post.createdAt))}</time> ·
              조회 {post.viewCount}
            </p>
            <p className="board-plain-text" data-testid="post-content">
              {post.content}
            </p>
            {post.record && <BoardRecordCard record={post.record} />}
            <MemberOnly>
              <div className="choices">
                <button
                  type="button"
                  aria-pressed={post.likedByMe}
                  disabled={busy || loading}
                  onClick={() => void like(post)}
                >
                  좋아요 {post.likeCount}
                </button>
                {post.isMine ? (
                  <>
                    <Link to={`/board/posts/${postId}/edit`}>수정</Link>
                    <button
                      type="button"
                      disabled={busy || loading}
                      onClick={() => setConfirmDelete(true)}
                    >
                      게시글 삭제
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    onClick={() => openReport({ targetType: 'POST', targetId: postId })}
                  >
                    게시글 신고
                  </button>
                )}
              </div>
            </MemberOnly>
            {confirmDelete && (
              <div className="choices">
                <p>게시글을 삭제할까요?</p>
                <button type="button" disabled={busy || loading} onClick={() => void remove(post)}>
                  게시글 삭제 확인
                </button>
                <button type="button" disabled={busy} onClick={() => setConfirmDelete(false)}>
                  취소
                </button>
              </div>
            )}
            <BoardFeedback error={actionError} onGuest={refresh} onRefresh={refresh} />
          </article>
          {reported && <p role="status">신고가 접수되었습니다.</p>}
          <BoardComments
            postId={postId}
            count={post.commentCount}
            authRevision={revision}
            onGuest={refresh}
            onReport={(targetId) => openReport({ targetType: 'COMMENT', targetId })}
            onCountChange={(delta) =>
              setPost((p) => p && { ...p, commentCount: Math.max(0, p.commentCount + delta) })
            }
          />
        </>
      )}
      {report && (
        <ReportDialog
          target={report}
          onGuest={refresh}
          onClose={() => setReport(undefined)}
          onSuccess={() => {
            setReport(undefined);
            setReported(true);
          }}
        />
      )}
    </div>
  );
}

export default function BoardDetail() {
  const { postId } = useParams();
  const id = Number(postId);
  if (!postId || !/^\d+$/.test(postId) || !Number.isSafeInteger(id) || id < 1) {
    return (
      <>
        <p role="alert">게시글 주소가 올바르지 않습니다.</p>
        <Link to="/board">게시판 목록</Link>
      </>
    );
  }
  return <PostView key={id} postId={id} />;
}
