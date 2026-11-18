import { JoinPrompt } from './Auth';
import { useEffect, useId, useRef, useState } from 'react';
import { boardApi } from '../api/board';
import { ApiError, restartGuest } from '../api/client';
import type { CreateReportRequest } from '../api/types';

function message(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.code === 'MEMBER_REQUIRED') return '로그인이 필요해요';
    if (error.status === 429) return '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
    if (error.code === 'POST_VERSION_CONFLICT')
      return '게시글이 다른 곳에서 수정되었습니다. 최신 게시글을 불러와 주세요.';
    if (error.code === 'COMMENT_VERSION_CONFLICT')
      return '댓글이 다른 곳에서 수정되었습니다. 최신 댓글을 불러와 주세요.';
    return error.message;
  }
  return '서버 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

export function BoardFeedback({
  error,
  onGuest,
  onRefresh,
}: {
  error: unknown;
  onGuest?: () => void;
  onRefresh?: () => void;
}) {
  const [guestError, setGuestError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  if (!error) return null;
  if (error instanceof ApiError && (error.status === 401 || error.code === 'MEMBER_REQUIRED'))
    return <JoinPrompt />;
  return (
    <div>
      <p role="alert" className="error">
        {message(error)}
      </p>
      {onGuest && error instanceof ApiError && error.status === 401 && (
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setGuestError(null);
            try {
              await restartGuest();
              onGuest();
            } catch (e) {
              setGuestError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          게스트로 시작
        </button>
      )}
      {guestError != null && (
        <p role="alert" className="error">
          {message(guestError)}
        </p>
      )}
      {onRefresh && error instanceof ApiError && error.status === 409 && (
        <button type="button" onClick={onRefresh}>
          최신 내용 불러오기
        </button>
      )}
    </div>
  );
}

export type ReportTarget = Pick<CreateReportRequest, 'targetType' | 'targetId'>;
const reasons = {
  SPAM: '광고·스팸',
  ABUSE: '욕설·괴롭힘',
  ADULT: '성인 콘텐츠',
  ETC: '기타',
} as const;
export function ReportDialog({
  target,
  onClose,
  onSuccess,
  onGuest,
}: {
  target: ReportTarget;
  onClose: () => void;
  onSuccess: () => void;
  onGuest: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const lock = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [reason, setReason] = useState<CreateReportRequest['reason']>('SPAM');
  const [detail, setDetail] = useState('');
  useEffect(() => {
    const node = dialog.current;
    node?.showModal();
    return () => {
      if (node?.open) node.close();
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      aria-labelledby={heading}
      onCancel={(e) => {
        e.preventDefault();
        if (!lock.current) onClose();
      }}
    >
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (lock.current || (reason === 'ETC' && !detail.trim())) return;
          lock.current = true;
          setBusy(true);
          setError(null);
          try {
            await boardApi.report({ ...target, reason, detail: detail.trim() || undefined });
            onSuccess();
          } catch (e) {
            setError(e);
          } finally {
            lock.current = false;
            setBusy(false);
          }
        }}
      >
        <h2 id={heading}>{target.targetType === 'POST' ? '게시글' : '댓글'} 신고</h2>
        <label>
          신고 사유
          <select
            disabled={busy}
            value={reason}
            onChange={(e) => setReason(e.target.value as typeof reason)}
          >
            {Object.entries(reasons).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          상세 설명 ({reason === 'ETC' ? '필수' : '선택'})
          <textarea
            maxLength={500}
            value={detail}
            disabled={busy}
            required={reason === 'ETC'}
            onChange={(e) => setDetail(e.target.value)}
          />
        </label>
        <BoardFeedback
          error={error}
          onGuest={() => {
            setError(null);
            onGuest();
          }}
        />
        <div className="choices">
          <button
            type="submit"
            className="primary"
            disabled={busy || (reason === 'ETC' && !detail.trim())}
          >
            신고 접수
          </button>
          <button type="button" disabled={busy} onClick={onClose}>
            취소
          </button>
        </div>
      </form>
    </dialog>
  );
}
