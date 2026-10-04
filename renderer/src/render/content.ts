import type { Page } from "playwright";

export interface ContentSelectionOutcome {
  /** Outermost elements matching the selector, now the only children of <body>, in source order. */
  kept: number;
  /** Top-level children of <body> that were not (and did not contain) a match and were left out. */
  dropped: number;
}

export class ContentSelectorInvalid extends Error {
  constructor() {
    super("content selector is not a valid CSS selector");
    this.name = "ContentSelectorInvalid";
  }
}

export class ContentNotFound extends Error {
  constructor() {
    super("no element matches the content selector");
    this.name = "ContentNotFound";
  }
}

/**
 * Reduces the loaded document to the outermost elements matching `selector`, placed directly under
 * `<body>` in source order; `<head>` (styles, title, language) is untouched. A nested match inside
 * another match stays where it is. Nothing is reordered or invented: the caller reports what was
 * kept and dropped, and no match is an error rather than a silent whole-page render.
 */
export async function selectContent(page: Page, selector: string): Promise<ContentSelectionOutcome> {
  const outcome = await page.evaluate((sel) => {
    let matches: Element[];
    try {
      matches = Array.from(document.querySelectorAll(sel));
    } catch {
      return { error: "invalid" as const };
    }
    const outermost = matches.filter((el) => !matches.some((other) => other !== el && other.contains(el)));
    if (outermost.length === 0) {
      return { error: "none" as const };
    }
    const before = Array.from(document.body.children);
    const dropped = before.filter((child) => !outermost.some((el) => child === el || child.contains(el))).length;
    document.body.replaceChildren(...outermost);
    return { error: null, kept: outermost.length, dropped };
  }, selector);
  if (outcome.error === "invalid") throw new ContentSelectorInvalid();
  if (outcome.error === "none") throw new ContentNotFound();
  return { kept: outcome.kept, dropped: outcome.dropped };
}
