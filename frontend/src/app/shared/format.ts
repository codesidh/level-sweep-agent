// Number / instant formatters used across the operator UI. Kept pure (no
// Angular DI) so they can be reused inside computed signals without resolving
// services per call.

const ET_FMT = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const UTC_FMT = new Intl.DateTimeFormat('en-US', {
  timeZone: 'UTC',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const DATE_FMT_ET = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

export function formatPrice(v: unknown): string {
  const n = toNumber(v);
  if (n === null) return '—';
  return n.toLocaleString(undefined, { minimumFractionDigits: 5, maximumFractionDigits: 5 });
}

export function formatRatio(v: unknown): string {
  const n = toNumber(v);
  if (n === null) return '—';
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function formatRMultiple(v: unknown): string {
  const n = toNumber(v);
  if (n === null) return '—';
  const sign = n > 0 ? '+' : '';
  return `${sign}${n.toLocaleString(undefined, { minimumFractionDigits: 4, maximumFractionDigits: 4 })}R`;
}

export function formatPct(v: unknown, decimals = 2): string {
  const n = toNumber(v);
  if (n === null) return '—';
  return `${n.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}%`;
}

export function formatUsd(v: unknown, decimals = 2): string {
  const n = toNumber(v);
  if (n === null) return '—';
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`;
}

export function formatInt(v: unknown): string {
  const n = toNumber(v);
  if (n === null) return '—';
  return Math.round(n).toLocaleString();
}

export function formatInstantUtc(v: unknown): string {
  const d = toDate(v);
  if (!d) return '—';
  // Intl produces "MM/DD/YYYY, HH:mm:ss"; reshape to "yyyy-MM-dd HH:mm:ss UTC"
  // so the operator's eye lands on date-then-time consistently.
  const parts = UTC_FMT.formatToParts(d);
  const get = (t: string): string => parts.find((p) => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')} UTC`;
}

export function formatInstantEt(v: unknown): string {
  const d = toDate(v);
  if (!d) return '—';
  const parts = ET_FMT.formatToParts(d);
  const get = (t: string): string => parts.find((p) => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')} ET`;
}

export function formatDateEt(v: unknown): string {
  const d = toDate(v);
  if (!d) return '—';
  const parts = DATE_FMT_ET.formatToParts(d);
  const get = (t: string): string => parts.find((p) => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

function toNumber(v: unknown): number | null {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string') {
    const trimmed = v.trim();
    if (trimmed.length === 0) return null;
    const n = Number(trimmed);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function toDate(v: unknown): Date | null {
  if (v === null || v === undefined) return null;
  if (v instanceof Date) return Number.isFinite(v.getTime()) ? v : null;
  if (typeof v === 'string' || typeof v === 'number') {
    const d = new Date(v);
    return Number.isFinite(d.getTime()) ? d : null;
  }
  return null;
}
