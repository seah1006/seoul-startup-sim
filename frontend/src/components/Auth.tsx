import { useEffect, useState } from 'react';
import type { ReactNode, FormEvent } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { authApi, clearAuth } from '../api/auth';
import type { UserMe } from '../api/auth';
import { guestStorageKey, ApiError } from '../api/client';
import { validPassword } from '../api/mock/auth';
export function useUser() {
  const [user, setUser] = useState<UserMe>();
  useEffect(() => {
    let active = true;
    const load = () => {
      if (!localStorage.getItem(guestStorageKey)) {
        setUser(undefined);
        return;
      }
      void authApi.me().then(
        (u) => {
          if (active) setUser(u);
        },
        (e) => {
          if (active) {
            setUser(undefined);
            if (e.status === 401) clearAuth();
          }
        },
      );
    };
    load();
    window.addEventListener('authchange', load);
    return () => {
      active = false;
      window.removeEventListener('authchange', load);
    };
  }, []);
  return user;
}
export function JoinPrompt({ next }: { next?: string }) {
  const l = useLocation();
  const target = encodeURIComponent(next || l.pathname + l.search);
  return (
    <div className="notice join-prompt">
      <p>로그인이 필요해요</p>
      <Link className="button" to={'/login?next=' + target}>
        로그인하고 참여하기
      </Link>{' '}
      <Link className="button" to={'/signup?next=' + target}>
        가입하고 기록 이어가기
      </Link>
    </div>
  );
}
export function MemberOnly({ children }: { children: ReactNode }) {
  return useUser()?.role === 'MEMBER' ? children : <JoinPrompt />;
}
export function AuthHeader() {
  const u = useUser(),
    nav = useNavigate(),
    l = useLocation();
  useEffect(() => {
    const fn = () => nav('/login?next=' + encodeURIComponent(l.pathname + l.search));
    window.addEventListener('authrequired', fn);
    return () => window.removeEventListener('authrequired', fn);
  }, [nav, l]);
  return u?.role === 'MEMBER' ? (
    <>
      <span>{u.nickname}</span>
      <Link to="/me">내 계정</Link>
      <button onClick={() => void authApi.logout().then(() => nav('/'))}>로그아웃</button>
    </>
  ) : (
    <>
      <Link to={'/login?next=' + encodeURIComponent(l.pathname + l.search)}>로그인</Link>
      <Link to={'/signup?next=' + encodeURIComponent(l.pathname + l.search)}>가입</Link>
    </>
  );
}
export function Login({ signup = false }: { signup?: boolean }) {
  const [params] = useSearchParams(),
    nav = useNavigate(),
    u = useUser();
  const [error, setError] = useState(''),
    [field, setField] = useState(''),
    [busy, setBusy] = useState(false);
  const next = params.get('next');
  const target =
    next?.startsWith('/') &&
    !next.startsWith('//') &&
    !next.startsWith('/login') &&
    !next.startsWith('/signup')
      ? next
      : '/';
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const b = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>;
    setError('');
    setField('');
    if (signup) {
      if (!/^[a-z0-9_]{4,20}$/.test(b.loginId)) {
        setError('아이디는 4~20자 영문 소문자·숫자·밑줄만 가능해요.');
        return;
      }
      if (b.nickname.trim().length < 2 || b.nickname.trim().length > 12) {
        setError('닉네임은 2~12자로 입력해 주세요.');
        return;
      }
      if (!validPassword(b.password)) {
        setError('비밀번호는 8~64자, 영문과 숫자를 포함해 주세요.');
        return;
      }
      if (b.password !== b.confirm) {
        setError('비밀번호 확인이 일치하지 않아요.');
        return;
      }
    }
    setBusy(true);
    try {
      await authApi.enter(signup, b);
      nav(target, { replace: true });
    } catch (e) {
      const a = e as ApiError;
      setField(
        a.code === 'LOGIN_ID_TAKEN' ? 'loginId' : a.code === 'NICKNAME_TAKEN' ? 'nickname' : '',
      );
      setError(
        a.status === 423
          ? '로그인이 15분 동안 잠겼어요. 잠시 후 다시 시도해 주세요.'
          : a.code === 'INVALID_CREDENTIALS'
            ? '아이디 또는 비밀번호를 확인해 주세요.'
            : a.message,
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="card">
      <h1>{signup ? '회원 가입' : '로그인'}</h1>
      {signup && u?.role === 'GUEST' && <p className="notice">지금 게임 기록을 그대로 가져가요</p>}
      <form onSubmit={submit}>
        <label>
          아이디
          <input
            name="loginId"
            autoComplete="username"
            required
            aria-invalid={field === 'loginId'}
          />
        </label>
        {signup && (
          <label>
            닉네임
            <input name="nickname" required maxLength={12} aria-invalid={field === 'nickname'} />
          </label>
        )}
        <label>
          비밀번호
          <input
            name="password"
            type="password"
            autoComplete={signup ? 'new-password' : 'current-password'}
            required
            maxLength={64}
          />
        </label>
        {signup && (
          <label>
            비밀번호 확인
            <input name="confirm" type="password" autoComplete="new-password" required />
          </label>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button className="primary" disabled={busy}>
          {signup ? '가입하기' : '로그인'}
        </button>
      </form>
      <Link to={(signup ? '/login' : '/signup') + '?next=' + encodeURIComponent(target)}>
        {signup ? '로그인으로' : '회원 가입'}
      </Link>
    </section>
  );
}
export function MyAccount() {
  const u = useUser(),
    nav = useNavigate();
  const [notice, setNotice] = useState(''),
    [error, setError] = useState(''),
    [confirm, setConfirm] = useState(false),
    [busy, setBusy] = useState(false);
  async function act(e: FormEvent<HTMLFormElement>, kind: string) {
    e.preventDefault();
    const b = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>;
    setError('');
    setNotice('');
    if (kind === 'password' && (!validPassword(b.newPassword) || b.newPassword !== b.confirm)) {
      setError('새 비밀번호는 8~64자 영문·숫자 포함, 확인과 일치해야 해요.');
      return;
    }
    setBusy(true);
    try {
      if (kind === 'nickname') {
        await authApi.save(b.nickname);
        window.dispatchEvent(new Event('authchange'));
        setNotice('닉네임을 바꿨어요.');
      }
      if (kind === 'password') {
        await authApi.password(b.currentPassword, b.newPassword);
        setNotice('다른 기기에서 로그아웃됐어요');
      }
      if (kind === 'delete') {
        await authApi.remove(b.password);
        nav('/');
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (u?.role !== 'MEMBER') return <JoinPrompt />;
  return (
    <section className="card">
      <h1>내 계정</h1>
      <p>아이디: {u.loginId}</p>
      <p>가입일: {new Date(u.createdAt).toLocaleDateString('ko-KR')}</p>
      <form onSubmit={(e) => void act(e, 'nickname')}>
        <label>
          닉네임
          <input name="nickname" defaultValue={u.nickname} minLength={2} maxLength={12} required />
        </label>
        <button disabled={busy}>닉네임 수정</button>
      </form>
      <h2>비밀번호 변경</h2>
      <form onSubmit={(e) => void act(e, 'password')}>
        {[
          ['currentPassword', '현재 비밀번호'],
          ['newPassword', '새 비밀번호'],
          ['confirm', '새 비밀번호 확인'],
        ].map(([name, label]) => (
          <label key={name}>
            {label}
            <input
              name={name}
              type="password"
              required
              autoComplete={name === 'currentPassword' ? 'current-password' : 'new-password'}
            />
          </label>
        ))}
        <button disabled={busy}>비밀번호 변경</button>
      </form>
      <button onClick={() => setConfirm(true)}>회원 탈퇴</button>
      {confirm && (
        <div role="dialog" aria-modal="true" aria-label="회원 탈퇴 확인">
          <h2>정말 탈퇴할까요?</h2>
          <p>탈퇴하면 로그인할 수 없으며 글과 기록은 익명으로 남아요.</p>
          <form onSubmit={(e) => void act(e, 'delete')}>
            <label>
              비밀번호 확인
              <input name="password" type="password" required autoComplete="current-password" />
            </label>
            <button disabled={busy}>탈퇴 확인</button>
            <button type="button" onClick={() => setConfirm(false)}>
              취소
            </button>
          </form>
        </div>
      )}
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
