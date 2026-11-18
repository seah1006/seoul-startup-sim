import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, useMock } from '../api/client';
import { backgrounds, statLabels } from '../api/types';
import type { Background, Candidate, Stat } from '../api/types';
export default function NewOwner() {
  const nav = useNavigate();
  const [name, setName] = useState('');
  const [background, setBackground] = useState<Background>('OFFICE_WORKER');
  const [seed, setSeed] = useState(123);
  const [list, setList] = useState<Candidate[]>([]);
  const [choice, setChoice] = useState('c1');
  const [focus, setFocus] = useState<Stat[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    setList([]);
    api
      .candidates(background, seed)
      .then((r) => {
        if (active) setList(r.candidates);
      })
      .catch((e) => setError(e.message));
    return () => {
      active = false;
    };
  }, [background, seed]);
  async function submit() {
    setBusy(true);
    setError('');
    try {
      const g = await api.create({
        ownerName: name.trim(),
        background,
        candidateId: choice,
        candidateSeed: seed,
        focusStats: focus,
      });
      localStorage.setItem(useMock ? 'sss-last-mock' : 'sss-last-real', String(g.gameId));
      nav(`/games/${g.gameId}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <p className="eyebrow">STEP 01 / YOUR OWNER</p>
      <h1>어떤 사장님이 될까요?</h1>
      <section className="card">
        <label>
          사장님 이름
          <input
            value={name}
            maxLength={30}
            onChange={(e) => setName(e.target.value)}
            placeholder="이름을 알려주세요"
          />
        </label>
        <h2>나의 출발점</h2>
        <div className="choices">
          {Object.entries(backgrounds).map(([key, label]) => (
            <button
              className={background === key ? 'selected' : ''}
              onClick={() => setBackground(key as Background)}
              key={key}
            >
              {label}
            </button>
          ))}
        </div>
      </section>
      <div className="section-title">
        <h2>같은 가능성, 다른 강점</h2>
        <button onClick={() => setSeed((s) => s + 1)}>다른 후보 보기</button>
      </div>
      <div className="grid three">
        {list.map((c, i) => (
          <button
            aria-pressed={choice === c.candidateId}
            className={`card candidate ${choice === c.candidateId ? 'selected' : ''}`}
            onClick={() => setChoice(c.candidateId)}
            key={c.candidateId}
          >
            <h3>
              후보 0{i + 1} <small>합계 {c.total}</small>
            </h3>
            {Object.entries(c.stats).map(([s, v]) => (
              <div className="stat" key={s}>
                <span>{statLabels[s as Stat]}</span>
                <meter min="0" max="100" value={v} />
                <b>{v}</b>
              </div>
            ))}
          </button>
        ))}
      </div>
      <section className="card">
        <h2>
          집중해서 키울 능력치 <small>{focus.length}/2</small>
        </h2>
        <div className="choices">
          {Object.entries(statLabels).map(([s, l]) => (
            <button
              key={s}
              aria-pressed={focus.includes(s as Stat)}
              disabled={focus.length === 2 && !focus.includes(s as Stat)}
              className={focus.includes(s as Stat) ? 'selected' : ''}
              onClick={() =>
                setFocus((f) =>
                  f.includes(s as Stat) ? f.filter((x) => x !== s) : [...f, s as Stat],
                )
              }
            >
              {l}
            </button>
          ))}
        </div>
      </section>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <button className="primary" disabled={!name.trim() || !list.length || busy} onClick={submit}>
        {busy ? '준비 중…' : '확인하고 동네 찾기 →'}
      </button>
    </>
  );
}
