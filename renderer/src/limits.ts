/**
 * Request limits are optional: an absent limit is not enforced. A time budget that is absent is
 * `Infinity` here; Playwright expresses "no timeout" as `0`.
 */
export function navigationTimeout(budgetMs: number): number {
  return Number.isFinite(budgetMs) ? budgetMs : 0;
}

/** Time budget in milliseconds for an optional `timeoutSeconds`; `Infinity` when absent. */
export function budgetFromSeconds(timeoutSeconds: number | undefined): number {
  return timeoutSeconds === undefined ? Infinity : timeoutSeconds * 1000;
}

/** An optional numeric limit; `Infinity` when absent. */
export function limitOf(value: number | undefined): number {
  return value ?? Infinity;
}

/** Visual tolerance of the source-order fix when the request names none (fraction of differing content pixels). */
export const DEFAULT_MAX_VISUAL_DIFF_RATIO = 0.02;

/** Copies an object without its `undefined` properties (keeps `exactOptionalPropertyTypes` honest). */
export function definedOnly<T extends object>(value: T): { [K in keyof T]?: Exclude<T[K], undefined> } {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as { [K in keyof T]?: Exclude<T[K], undefined> };
}
