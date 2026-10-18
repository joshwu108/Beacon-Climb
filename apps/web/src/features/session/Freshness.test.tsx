import { render, screen } from '@testing-library/react';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { formatDateTime } from '../../lib/format';
import { Freshness } from './Freshness';

const AS_OF = '2026-10-14T13:59:00Z';

describe('Freshness', () => {
  // Node re-reads TZ on assignment, so the local clock is pinned to UTC here.
  const originalTz = process.env.TZ;
  beforeAll(() => {
    process.env.TZ = 'UTC';
  });
  afterAll(() => {
    process.env.TZ = originalTz;
  });

  it('STREAM: shows the local 24 h clock, a "live" suffix and a label with the full date-time and source', () => {
    render(<Freshness featureAsOf={AS_OF} source="STREAM" />);
    const badge = screen.getByRole('note', {
      name: `Features as of ${formatDateTime(AS_OF)}, from the live stream.`,
    });
    expect(badge).toHaveTextContent('features as of 13:59');
    expect(badge).toHaveTextContent(/live$/);
    expect(badge).not.toHaveTextContent(/recomputed/);
    expect(badge.querySelector('time')).toHaveAttribute('dateTime', AS_OF);
  });

  it('SYNC: same clock, a "recomputed" suffix and a label naming the on-request recompute', () => {
    render(<Freshness featureAsOf={AS_OF} source="SYNC" />);
    const badge = screen.getByRole('note', {
      name: `Features as of ${formatDateTime(AS_OF)}, recomputed on request.`,
    });
    expect(badge).toHaveTextContent('features as of 13:59');
    expect(badge).toHaveTextContent(/recomputed$/);
    expect(badge).not.toHaveTextContent(/live/);
  });

  it('the two sources differ by glyph and suffix, not only by colour', () => {
    const { unmount } = render(
      <Freshness featureAsOf={AS_OF} source="STREAM" />,
    );
    const stream = screen.getByRole('note');
    const streamGlyph = stream.querySelector('svg')?.getAttribute('data-glyph');
    unmount();
    render(<Freshness featureAsOf={AS_OF} source="SYNC" />);
    const sync = screen.getByRole('note');
    expect(sync.querySelector('svg')?.getAttribute('data-glyph')).not.toBe(
      streamGlyph,
    );
    expect(sync.textContent).not.toBe(stream.textContent);
  });

  it('no feature_as_of (cold start): says "no attempts yet" and still names the source', () => {
    render(<Freshness featureAsOf={null} source="SYNC" />);
    const badge = screen.getByRole('note', {
      name: 'No attempts yet, so no feature snapshot; recomputed on request.',
    });
    expect(badge).toHaveTextContent('no attempts yet');
    expect(badge.querySelector('time')).toBeNull();
  });

  it('formats the clock as zero-padded 24 h local time', () => {
    const { rerender } = render(
      <Freshness featureAsOf="2026-10-14T00:05:00Z" source="STREAM" />,
    );
    expect(screen.getByRole('note')).toHaveTextContent('features as of 00:05');
    rerender(<Freshness featureAsOf="2026-10-14T18:30:00Z" source="STREAM" />);
    expect(screen.getByRole('note')).toHaveTextContent('features as of 18:30');
    expect(screen.getByRole('note')).not.toHaveTextContent(/PM/);
  });

  it('an unparseable timestamp is shown raw rather than as "Invalid Date"', () => {
    render(<Freshness featureAsOf="not-a-date" source="SYNC" />);
    expect(screen.getByRole('note')).toHaveTextContent(
      'features as of not-a-date',
    );
  });
});
