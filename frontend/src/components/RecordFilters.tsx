import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Industry, Area } from '../api/types';
export function RecordFilters({
  serviceCode,
  areaCode,
}: {
  serviceCode?: string;
  areaCode?: string;
}) {
  const [industries, setIndustries] = useState<Industry[]>([]),
    [q, setQ] = useState(''),
    [areas, setAreas] = useState<Area[]>([]),
    [selected, setSelected] = useState(areaCode || ''),
    [industry, setIndustry] = useState(serviceCode || ''),
    [error, setError] = useState('');
  useEffect(() => {
    void api.industries().then(setIndustries, () => setError('업종 목록을 불러오지 못했어요.'));
  }, []);
  useEffect(() => {
    let active = true;
    void api.search(q).then(
      (a) => {
        if (active) setAreas(a);
      },
      () => {
        if (active) setError('상권 검색에 실패했어요.');
      },
    );
    return () => {
      active = false;
    };
  }, [q]);
  return (
    <>
      <label>
        업종
        <select name="serviceCode" value={industry} onChange={(e) => setIndustry(e.target.value)}>
          <option value="">전체 업종</option>
          {industries.map((i) => (
            <option key={i.serviceCode} value={i.serviceCode}>
              {i.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        상권 이름 검색
        <input
          type="search"
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setSelected('');
          }}
          placeholder="예: 망원"
        />
      </label>
      <input type="hidden" name="areaCode" value={selected} />
      {selected && (
        <button
          type="button"
          onClick={() => {
            setSelected('');
            setQ('');
          }}
        >
          상권 선택 해제
        </button>
      )}
      {areas.length > 0 && (
        <ul aria-label="상권 검색 결과">
          {areas.map((a) => (
            <li key={a.areaCode}>
              <button
                type="button"
                aria-pressed={selected === a.areaCode}
                onClick={() => {
                  setSelected(a.areaCode);
                  setQ(a.name);
                }}
              >
                {a.name} · {a.districtName}
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && <p role="alert">{error}</p>}
    </>
  );
}
