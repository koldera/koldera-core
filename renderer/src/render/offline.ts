import type { Page } from "playwright";
import type { Diagnostic } from "../contracts/types.js";
import { DOCUMENT_URL } from "./isolated.js";
import { CONTENT_LANGUAGE_TAG } from "./language.js";

/** What the offline preparation changed on the loaded page before printing. */
export interface OfflineOutcome {
  /** Links whose target is not part of this document (relative/render-host addresses, fragments without a target). */
  linksRemoved: number;
  /** Images that did not load (anything but a working inline data: image). */
  imagesNotLoaded: number;
}

/**
 * Everything except the document is refused, so relative addresses and non-inline images never
 * resolve. Before printing: links to the render host lose their `href`, images that did not load
 * become a span with their alt text (or go), and a blank `<html lang>` takes the content-language
 * meta tag, else `lang=""`. Nothing is fetched or guessed; changes are counted.
 */
export async function prepareOffline(page: Page): Promise<OfflineOutcome> {
  return page.evaluate(({ documentUrl, languageTag }) => {
    const host = new URL(documentUrl).host;
    // The source's own declaration, which Chromium honours as the document language. Same rule as
    // contentLanguageTag() in language.ts (used by preflight); page.evaluate cannot import it, so only the pattern is passed.
    const tag = new RegExp(languageTag);
    let pragmaLanguage: string | null = null;
    for (const meta of Array.from(document.querySelectorAll("meta[http-equiv][content]"))) {
      if ((meta.getAttribute("http-equiv") ?? "").trim().toLowerCase() !== "content-language") continue;
      const value = (meta.getAttribute("content") ?? "").trim();
      pragmaLanguage = !value.includes(",") && tag.test(value) ? value : null;
    }
    let linksRemoved = 0;
    for (const link of Array.from(document.querySelectorAll("a[href], area[href]"))) {
      const raw = (link.getAttribute("href") ?? "").trim();
      let dead: boolean;
      if (raw.startsWith("#")) {
        let id = raw.slice(1);
        try {
          id = decodeURIComponent(id);
        } catch {
          // keep the raw fragment
        }
        dead = id.length === 0 || (document.getElementById(id) === null && document.getElementsByName(id).length === 0);
      } else {
        try {
          dead = new URL(raw, documentUrl).host === host;
        } catch {
          dead = true;
        }
      }
      if (dead) {
        link.removeAttribute("href");
        linksRemoved += 1;
      }
    }

    let imagesNotLoaded = 0;
    for (const img of Array.from(document.querySelectorAll("img"))) {
      const src = (img.getAttribute("src") ?? "").trim();
      const loaded = /^data:/i.test(src) && !(img.complete && img.naturalWidth === 0);
      if (loaded) continue;
      imagesNotLoaded += 1;
      const alt = (img.getAttribute("alt") ?? "").trim();
      if (alt.length === 0) {
        img.remove();
        continue;
      }
      const span = document.createElement("span");
      span.style.display = "inline";
      span.textContent = alt;
      img.replaceWith(span);
    }

    const root = document.documentElement;
    if ((root.getAttribute("lang") ?? "").trim().length === 0) {
      root.setAttribute("lang", pragmaLanguage ?? "");
    }
    return { linksRemoved, imagesNotLoaded };
  }, { documentUrl: DOCUMENT_URL, languageTag: CONTENT_LANGUAGE_TAG.source });
}

/** The advisory diagnostics for what {@link prepareOffline} changed; none when nothing changed. */
export function offlineDiagnostics(outcome: OfflineOutcome): Diagnostic[] {
  const out: Diagnostic[] = [];
  const { linksRemoved: links, imagesNotLoaded: images } = outcome;
  if (links > 0) {
    out.push({
      code: "HTML_RELATIVE_LINK_REMOVED",
      message: `${links} link${links === 1 ? "" : "s"} pointed to a page or anchor that is not part of this document (a relative address, or a fragment without a target); the link text was kept and the link removed. Use absolute https:// addresses for links to other pages`,
      blocking: false,
      line: null,
      element: "a",
    });
  }
  if (images > 0) {
    out.push({
      code: "HTML_IMAGE_NOT_LOADED",
      message: `${images} image${images === 1 ? "" : "s"} could not be loaded (only inline data: images are rendered); each was replaced by its alt text, or left out when it had none. Embed the images as data: URLs to include them`,
      blocking: false,
      line: null,
      element: "img",
    });
  }
  return out;
}
