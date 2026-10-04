import type { Page } from "playwright";

/**
 * An <img> without an alt attribute is treated as presentation: Chromium prints alt="" as an Artifact.
 * An alt of only whitespace would print as a Figure with a blank /Alt, so it becomes alt="" too; the
 * author wrote an alt attribute, so no HTML_IMAGE_ALT_MISSING is reported for it.
 */
export async function markImagesWithoutAltDecorative(page: Page): Promise<number> {
  return page.evaluate(() => {
    const images = Array.from(document.querySelectorAll("img")).filter((img) => {
      const alt = img.getAttribute("alt");
      return alt === null || (alt.length > 0 && alt.trim() === "");
    });
    for (const img of images) img.setAttribute("alt", "");
    return images.length;
  });
}

/** What the HTML_IMAGE_ALT_MISSING finding says once the image was printed (preflight alone cannot know that). */
export const PRINTED_AS_DECORATIVE =
  "an <img> has no alt attribute; it was printed as decorative (an artifact, no text alternative). A person must check whether it carries meaning and, if so, add alt text to the source";

/** What it says when the offline preparation left the image out because it could not be loaded (render/offline.ts). */
export const LEFT_OUT_NOT_LOADED =
  "an <img> has no alt attribute and could not be loaded (only inline data: images are rendered); it was left out of the PDF. A person must check whether it carries meaning and, if so, embed it and add alt text to the source";

/** Used only if the page and preflight disagree on how many such images there are (never observed); true for either fate. */
export const PRINTED_OR_LEFT_OUT =
  "an <img> has no alt attribute; it was printed as decorative, or left out if it could not be loaded. A person must check whether it carries meaning and, if so, add alt text to the source";

/**
 * What happened to each <img> preflight reports as HTML_IMAGE_ALT_MISSING (an <img> with a src and no alt), in document
 * order: printed as decorative, left out because it did not load, or not printed at all (outside the content selection).
 */
export type AltMissingFate = "decorative" | "not_loaded" | "not_printed";

const MARK = "data-koldera-alt-missing";

/** Before anything changes the loaded DOM: numbers exactly the images preflight reported (src present, alt absent). */
export async function markAltMissingImages(page: Page): Promise<void> {
  await page.evaluate((mark) => {
    Array.from(document.querySelectorAll("img[src]:not([alt])")).forEach((img, i) => img.setAttribute(mark, String(i)));
  }, MARK);
}

/** Indexes of the marked images still in the document. */
export async function markedImagesPresent(page: Page): Promise<number[]> {
  return page.evaluate((mark) => Array.from(document.querySelectorAll(`img[${mark}]`)).map((img) => Number(img.getAttribute(mark))), MARK);
}

/** Right before printing: removes the numbering again, so the printed DOM carries nothing of ours. */
export async function unmarkAltMissingImages(page: Page): Promise<void> {
  await page.evaluate((mark) => {
    for (const img of Array.from(document.querySelectorAll(`img[${mark}]`))) img.removeAttribute(mark);
  }, MARK);
}

/**
 * Fates in document order from the marked images present after the content selection (`selected`) and right before
 * printing (`printed`); `total` is how many were marked.
 */
export function altMissingFates(total: number, selected: number[], printed: number[]): AltMissingFate[] {
  return Array.from({ length: total }, (_, i) => (printed.includes(i) ? "decorative" : selected.includes(i) ? "not_loaded" : "not_printed"));
}

/**
 * A rendered copy's HTML_IMAGE_ALT_MISSING findings say what happened to each image (the k-th finding is the k-th
 * image in document order: parse5 and Chromium build the same tree from the vetted HTML). An image that was not
 * printed at all keeps the preflight wording. Without fates, or if the counts disagree, a wording true for both.
 */
export function describePrintedAsDecorative<T extends { code: string; message: string }>(diagnostics: T[], fates: AltMissingFate[] | null = null): T[] {
  const count = diagnostics.filter((d) => d.code === "HTML_IMAGE_ALT_MISSING").length;
  const usable = fates !== null && fates.length === count;
  let k = 0;
  return diagnostics.map((d) => {
    if (d.code !== "HTML_IMAGE_ALT_MISSING") return d;
    const fate = usable ? fates[k++] : null;
    if (fate === "not_printed") return d;
    const message = fate === "decorative" ? PRINTED_AS_DECORATIVE : fate === "not_loaded" ? LEFT_OUT_NOT_LOADED : fates === null ? PRINTED_AS_DECORATIVE : PRINTED_OR_LEFT_OUT;
    return { ...d, message };
  });
}
