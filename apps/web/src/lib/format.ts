/** Local date and time for an RFC 3339 timestamp; the raw value stays in a `<time>` dateTime. */
export const formatDateTime = (iso: string): string => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return iso;
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(ms));
};

export const plural = (n: number, unit: string): string =>
  `${n} ${unit}${n === 1 ? '' : 's'}`;
