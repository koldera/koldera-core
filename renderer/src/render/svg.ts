import type { Page } from "playwright";
import type { Diagnostic } from "../contracts/types.js";

/** How many inline `<svg>` elements got an accessible name from their own markup, and how many stayed unnamed. */
export interface SvgOutcome {
  named: number;
  unnamed: number;
}

const MAX_NAME_LENGTH = 500;

/**
 * An inline `<svg>` named by its own `<title>`, `aria-labelledby` or `aria-label` gets `role="img"`
 * (when it has none) so Chromium tags it as a Figure with that name as `/Alt`. An unnamed `<svg>`
 * with no text of its own is marked `aria-hidden="true"` and reported for review. Nested, already
 * hidden or differently-roled `<svg>` elements are left as authored.
 */
export async function nameInlineSvgs(page: Page): Promise<SvgOutcome> {
  return page.evaluate((maxLength) => {
    function collapsed(text: string): string {
      return text.replace(/\s+/g, " ").trim();
    }
    function isAriaHidden(el: Element): boolean {
      for (let node: Element | null = el; node; node = node.parentElement) {
        if (node.getAttribute("aria-hidden") === "true") return true;
      }
      return false;
    }
    function hasSvgAncestor(el: Element): boolean {
      for (let node = el.parentElement; node; node = node.parentElement) {
        if (node.tagName.toLowerCase() === "svg") return true;
      }
      return false;
    }

    let named = 0;
    let unnamed = 0;
    const svgs = Array.from(document.querySelectorAll("svg")).filter((svg) => !hasSvgAncestor(svg));
    for (const svg of svgs) {
      if (isAriaHidden(svg)) continue;
      const role = svg.getAttribute("role");
      if (role !== null && role.trim().length > 0 && role.trim().toLowerCase() !== "img") continue;

      const setRoleImg = () => {
        if (svg.getAttribute("role") === null) svg.setAttribute("role", "img");
      };

      const ariaLabel = (svg.getAttribute("aria-label") ?? "").trim();
      if (ariaLabel.length > 0) {
        setRoleImg();
        named++;
        continue;
      }

      const labelledBy = (svg.getAttribute("aria-labelledby") ?? "").trim();
      if (labelledBy.length > 0) {
        const ids = labelledBy.split(/\s+/).filter((id) => id.length > 0);
        const parts = ids
          .map((id) => document.getElementById(id))
          .filter((el): el is HTMLElement => el !== null)
          .map((el) => el.textContent ?? "");
        const joined = collapsed(parts.join(" ")).slice(0, maxLength);
        if (joined.length > 0) {
          svg.setAttribute("aria-label", joined);
          setRoleImg();
          named++;
          continue;
        }
      }

      const titleChild = Array.from(svg.children).find((c) => c.tagName.toLowerCase() === "title");
      if (titleChild) {
        const text = collapsed(titleChild.textContent ?? "").slice(0, maxLength);
        if (text.length > 0) {
          svg.setAttribute("aria-label", text);
          setRoleImg();
          named++;
          continue;
        }
      }

      const hasOwnText = Array.from(svg.querySelectorAll("text, foreignObject")).some((el) => collapsed(el.textContent ?? "").length > 0);
      if (hasOwnText) continue;

      svg.setAttribute("aria-hidden", "true");
      unnamed++;
    }
    return { named, unnamed };
  }, MAX_NAME_LENGTH);
}

/** The advisory diagnostic for {@link nameInlineSvgs}'s unnamed count; none when every inline svg was named or skipped. */
export function svgDiagnostics(outcome: SvgOutcome): Diagnostic[] {
  if (outcome.unnamed === 0) return [];
  const n = outcome.unnamed;
  return [
    {
      code: "HTML_SVG_NAME_MISSING",
      message: `${n} inline <svg> graphic${n === 1 ? "" : "s"} ha${n === 1 ? "s" : "ve"} no accessible name (no <title>, aria-label or aria-labelledby) and paint${n === 1 ? "s" : ""} no text; printed as decorative (an artifact, no text alternative). A person must check whether it carries meaning and, if so, name it in the source with a <title> child or aria-label`,
      blocking: false,
      line: null,
      element: "svg",
    },
  ];
}
