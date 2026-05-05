// Mirrors com.levelsweep.calendar.domain.MarketDay and MarketEvent:
//   GET /calendar/today           → MarketDay
//   GET /calendar/{date}          → MarketDay
//   GET /calendar/blackout-dates  → { from, to, count, events: MarketEvent[] }

export interface MarketDay {
  readonly date: string;
  readonly isTradingDay: boolean;
  readonly isHoliday: boolean;
  readonly holidayName: string | null;
  readonly isHalfDay: boolean;
  readonly isFomcDay: boolean;
  readonly eventNames: ReadonlyArray<string>;
}

export type EventType = 'HOLIDAY' | 'EARLY_CLOSE' | 'FOMC_MEETING' | 'FOMC_MINUTES' | string;

export interface MarketEvent {
  readonly date: string;
  readonly name: string;
  readonly type: EventType;
}

export interface BlackoutResponse {
  readonly from: string;
  readonly to: string;
  readonly count: number;
  readonly events: ReadonlyArray<MarketEvent>;
}
