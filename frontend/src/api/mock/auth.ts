export type UserMe = {
  userId: number;
  loginId: string | null;
  nickname: string;
  role: 'GUEST' | 'MEMBER';
  createdAt: string;
};
type Member = {
  user: UserMe;
  password: string;
  failures: number;
  firstFailure: number;
  lockedUntil: number;
  deleted?: boolean;
};
type Db = { members: Member[]; sessions: Record<string, number>; next: number };
const key = 'sss-mock-auth-v1';
function load(): Db {
  return JSON.parse(localStorage.getItem(key) || '{"members":[],"sessions":{},"next":2}');
}
function save(db: Db) {
  localStorage.setItem(key, JSON.stringify(db));
}
function fail(status: number, code: string, message: string): never {
  throw Object.assign(new Error(message), { status, code });
}
export function mockUser(token: string | null): UserMe | undefined {
  if (token === 'mock-guest')
    return {
      userId: 1,
      loginId: null,
      nickname: '연습 사장님',
      role: 'GUEST',
      createdAt: '2026-11-18T00:00:00Z',
    };
  const db = load();
  return db.members.find((m) => !m.deleted && m.user.userId === db.sessions[token || ''])?.user;
}
export function mockAuth(
  path: string,
  method: string,
  token: string | null,
  body?: unknown,
): unknown {
  const db = load(),
    b = (body || {}) as Record<string, string>,
    user = mockUser(token),
    member = db.members.find((m) => m.user.userId === user?.userId);
  const issue = (m: Member) => {
    const next = 'mock-member-' + m.user.userId + '-' + db.next++;
    db.sessions[next] = m.user.userId;
    save(db);
    return { token: next, user: m.user };
  };
  if (path === '/auth/signup') {
    if (
      !/^[a-z0-9_]{4,20}$/.test(b.loginId) ||
      !validPassword(b.password) ||
      b.nickname.trim().length < 2 ||
      b.nickname.trim().length > 12
    )
      fail(400, 'VALIDATION_FAILED', '입력 형식을 확인해 주세요.');
    if (db.members.some((m) => m.user.loginId === b.loginId))
      fail(409, 'LOGIN_ID_TAKEN', '이미 사용 중인 아이디예요.');
    if (db.members.some((m) => !m.deleted && m.user.nickname === b.nickname.trim()))
      fail(409, 'NICKNAME_TAKEN', '이미 사용 중인 닉네임이에요.');
    const m: Member = {
      user: {
        userId: user?.role === 'GUEST' ? user.userId : db.next++,
        loginId: b.loginId,
        nickname: b.nickname.trim(),
        role: 'MEMBER',
        createdAt: new Date().toISOString(),
      },
      password: b.password,
      failures: 0,
      firstFailure: 0,
      lockedUntil: 0,
    };
    db.members.push(m);
    if (token) delete db.sessions[token];
    return issue(m);
  }
  if (path === '/auth/login') {
    const m = db.members.find((m) => !m.deleted && m.user.loginId === b.loginId);
    if (m && m.lockedUntil > Date.now())
      fail(423, 'ACCOUNT_LOCKED', '로그인이 15분 동안 잠겼어요. 잠시 후 다시 시도해 주세요.');
    if (!m || m.password !== b.password) {
      if (m) {
        if (Date.now() - m.firstFailure > 900000) {
          m.failures = 0;
          m.firstFailure = Date.now();
        }
        m.failures++;
        if (m.failures >= 5) m.lockedUntil = Date.now() + 900000;
        save(db);
        if (m.lockedUntil > Date.now()) fail(423, 'ACCOUNT_LOCKED', '로그인이 15분 동안 잠겼어요.');
      }
      fail(401, 'INVALID_CREDENTIALS', '아이디 또는 비밀번호를 확인해 주세요.');
    }
    m.failures = 0;
    return issue(m);
  }
  if (!user) fail(401, 'UNAUTHORIZED', '로그인이 필요해요');
  if (path === '/auth/logout') {
    delete db.sessions[token!];
    save(db);
    return null;
  }
  if (method === 'GET') return user;
  if (!member) fail(403, 'MEMBER_REQUIRED', '로그인이 필요해요');
  if (method === 'PATCH') {
    if (b.nickname.trim().length < 2 || b.nickname.trim().length > 12)
      fail(400, 'VALIDATION_FAILED', '닉네임은 2~12자예요.');
    if (db.members.some((m) => !m.deleted && m !== member && m.user.nickname === b.nickname.trim()))
      fail(409, 'NICKNAME_TAKEN', '이미 사용 중인 닉네임이에요.');
    member.user.nickname = b.nickname.trim();
    save(db);
    return member.user;
  }
  if (member.password !== (b.currentPassword || b.password))
    fail(401, 'INVALID_CREDENTIALS', '비밀번호를 확인해 주세요.');
  if (method === 'PUT') {
    if (!validPassword(b.newPassword))
      fail(400, 'VALIDATION_FAILED', '비밀번호는 8~64자, 영문과 숫자를 포함해 주세요.');
    member.password = b.newPassword;
    for (const t in db.sessions)
      if (t !== token && db.sessions[t] === user.userId) delete db.sessions[t];
  }
  if (method === 'DELETE') {
    member.deleted = true;
    member.user.nickname = '탈퇴한 사장님';
    for (const t in db.sessions) if (db.sessions[t] === user.userId) delete db.sessions[t];
  }
  save(db);
  return null;
}
export function validPassword(value: string) {
  return (
    typeof value === 'string' &&
    value.length >= 8 &&
    value.length <= 64 &&
    /[a-zA-Z]/.test(value) &&
    /[0-9]/.test(value)
  );
}
