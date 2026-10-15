/** Cold-start and fallback banners shared by the session lineup and decision detail. */
export const ColdStartNotice = ({
  detailed = false,
}: {
  detailed?: boolean;
}) => (
  <div className="banner banner--neutral" role="status">
    <p className="banner__title">Starting picks</p>
    <p>
      Recommendations are heuristic until you log a few attempts. Not enough
      logged attempts yet for history-based picks, so these come from how well
      each route&apos;s grade fits your goal and starting band on your board and
      angle.
      {detailed ? ' It does not estimate your chance of sending.' : ''}
    </p>
  </div>
);

export const FallbackNotice = () => (
  <div className="banner banner--warning" role="status">
    <p className="banner__title">
      <span className="stamp stamp--warning">Simplified recommendation</span>
    </p>
    <p>
      The full ranking step wasn&apos;t available, so this list uses a simpler
      fallback. No chance-of-success figures are shown.
    </p>
  </div>
);
