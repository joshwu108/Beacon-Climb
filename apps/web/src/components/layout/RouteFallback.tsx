/** Minimal, fixed-height fallback while a lazy route chunk loads: no layout shift, announced politely. */
export const RouteFallback = () => (
  <div className="page" aria-busy="true">
    <p role="status" className="visually-hidden">
      Loading
    </p>
    <span
      className="skeleton"
      style={{ width: '40%', height: '2.5rem' }}
      aria-hidden="true"
    />
    <span className="skeleton" style={{ width: '70%' }} aria-hidden="true" />
  </div>
);
