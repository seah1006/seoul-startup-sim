import { afterEach, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { NumericText } from '../components/NumericText';
afterEach(cleanup);
it('숫자만 고정폭 요소로 감싸고 단위와 공백은 본문에 남긴다', () => {
  const { container } = render(<NumericText value="-5,820만 원 · 경영 53 (+3) · 4,130명" />);
  expect(container.textContent).toBe('-5,820만 원 · 경영 53 (+3) · 4,130명');
  expect([...container.querySelectorAll('.num')].map((el) => el.textContent)).toEqual([
    '-5,820',
    '53',
    '+3',
    '4,130',
  ]);
});
