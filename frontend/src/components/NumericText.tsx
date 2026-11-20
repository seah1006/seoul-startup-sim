/** Keep unit labels and whitespace in the body font; only numeric tokens are monospaced. */
export function NumericText({ value }: { value: string | number }) {
  return (
    <>
      {String(value)
        .split(/([+-]?\d[\d,.]*)/g)
        .map((part, index) =>
          /\d/.test(part) ? (
            <span className="num" key={index}>
              {part}
            </span>
          ) : (
            part
          ),
        )}
    </>
  );
}
