// Helpers around US equity Regular Trading Hours (RTH 09:30–16:00 ET).
// Kept on the client because the dashboard's "today" widget needs a
// continuous countdown — the calendar API only tells us if a date is a
// trading day; the per-second "open in 02:34:11" tick is purely UI.

const ET_PARTS = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

interface EtNow {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
  date: Date;
}

export function nowEt(now: Date = new Date()): EtNow {
  const parts = ET_PARTS.formatToParts(now);
  const get = (t: string): number => Number(parts.find((p) => p.type === t)?.value ?? '0');
  return {
    year: get('year'),
    month: get('month'),
    day: get('day'),
    hour: get('hour'),
    minute: get('minute'),
    second: get('second'),
    date: now,
  };
}

const RTH_OPEN_MIN = 9 * 60 + 30;
const RTH_CLOSE_MIN = 16 * 60;

export interface RthStatus {
  readonly state: 'PRE' | 'RTH' | 'POST' | 'CLOSED_WEEKEND';
  readonly etLabel: string;
  readonly minutesToNext: number;
  readonly nextLabel: string;
}

export function rthStatus(et: EtNow = nowEt()): RthStatus {
  const totalMin = et.hour * 60 + et.minute;
  const isWeekend = isWeekendEt(et);
  const etLabel = `${pad(et.hour)}:${pad(et.minute)}:${pad(et.second)} ET`;
  if (isWeekend) {
    return {
      state: 'CLOSED_WEEKEND',
      etLabel,
      minutesToNext: -1,
      nextLabel: 'Weekend — markets closed',
    };
  }
  if (totalMin < RTH_OPEN_MIN) {
    return {
      state: 'PRE',
      etLabel,
      minutesToNext: RTH_OPEN_MIN - totalMin,
      nextLabel: 'RTH opens 09:30 ET',
    };
  }
  if (totalMin < RTH_CLOSE_MIN) {
    return {
      state: 'RTH',
      etLabel,
      minutesToNext: RTH_CLOSE_MIN - totalMin,
      nextLabel: 'RTH closes 16:00 ET',
    };
  }
  return {
    state: 'POST',
    etLabel,
    minutesToNext: -1,
    nextLabel: 'After-hours — RTH closed',
  };
}

function isWeekendEt(et: EtNow): boolean {
  // Construct a UTC date with the ET wall-clock components; getUTCDay() then
  // matches the ET day-of-week without re-introducing the local zone offset.
  const d = new Date(Date.UTC(et.year, et.month - 1, et.day));
  const dow = d.getUTCDay();
  return dow === 0 || dow === 6;
}

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}
