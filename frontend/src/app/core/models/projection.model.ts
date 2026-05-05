// Mirrors com.levelsweep.projection.domain.ProjectionRequest / ProjectionResult
// and the ProjectionRunDocument Mongo shape. The BFF forwards bytes — keep
// field names and casing identical to the Java records.

export interface ProjectionRequest {
  readonly tenantId: string;
  readonly startingEquity: number;
  readonly winRatePct: number;
  readonly lossPct: number;
  readonly sessionsPerWeek: number;
  readonly horizonWeeks: number;
  readonly positionSizePct: number;
  readonly simulations: number;
  readonly seed?: number | null;
}

export interface ProjectionResult {
  readonly p10: number;
  readonly p25: number;
  readonly p50: number;
  readonly p75: number;
  readonly p90: number;
  readonly mean: number;
  readonly ruinProbability: number;
  readonly simulationsRun: number;
  readonly requestHash: string;
}

export interface ProjectionRunDocument {
  readonly tenantId: string;
  readonly requestHash: string;
  readonly request: ProjectionRequest;
  readonly result: ProjectionResult;
  readonly runAt: string;
}
