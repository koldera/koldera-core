import type { Page } from "playwright";

/**
 * Chromium drops a table cell that paints nothing from the tag tree, which makes the table irregular.
 * Every empty `td`/`th` gets an out-of-flow no-break space that Chromium tags without moving the
 * layout; it is removed again wherever the cell's box or the properties `:empty` rules can change
 * differ, and `empty-cells: hide` cells are skipped. Returns how many cells kept a filler.
 */
export async function keepEmptyCells(page: Page): Promise<number> {
  return page.evaluate(() => {
    // Content Chromium paints: any character outside the HTML whitespace set (a no-break space counts), or an embedded object.
    const hasText = (cell: Element) => /[^ \t\n\f\r]/.test(cell.textContent ?? "");
    const embedded = "img,svg,canvas,video,audio,iframe,object,embed,input,select,textarea,button,math,picture,meter,progress";
    const properties = [
      "display", "visibility", "opacity", "background-color", "background-image", "color", "width", "height",
      "padding-top", "padding-right", "padding-bottom", "padding-left",
      "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
      "border-top-style", "border-right-style", "border-bottom-style", "border-left-style",
      "border-top-color", "border-right-color", "border-bottom-color", "border-left-color", "outline-style", "box-shadow",
    ];
    // The cell's own look, which only rules on the cell itself (such as :empty) change, and its box, which any cell can move.
    const look = (cell: HTMLElement): string => {
      const style = getComputedStyle(cell);
      const pseudo = (which: string) => {
        const p = getComputedStyle(cell, which);
        return `${p.content}|${p.display}`;
      };
      return [...properties.filter((name) => name !== "width" && name !== "height").map((name) => style.getPropertyValue(name)), pseudo("::before"), pseudo("::after")].join(";");
    };
    const box = (cell: HTMLElement): string => {
      const r = cell.getBoundingClientRect();
      return [r.x, r.y, r.width, r.height, getComputedStyle(cell).width, getComputedStyle(cell).height].join(";");
    };
    const candidates: { cell: HTMLElement; look: string; box: string; filler: HTMLElement | null }[] = [];
    for (const cell of Array.from(document.querySelectorAll<HTMLElement>("td, th"))) {
      if (hasText(cell) || cell.querySelector(embedded) !== null) continue;
      const style = getComputedStyle(cell);
      const table = cell.closest("table");
      if (style.emptyCells === "hide" && (!table || getComputedStyle(table).borderCollapse !== "collapse")) continue;
      candidates.push({ cell, look: look(cell), box: box(cell), filler: null });
    }
    for (const c of candidates) {
      const filler = document.createElement("span");
      filler.style.position = "absolute";
      filler.textContent = "\u00a0";
      c.cell.appendChild(filler);
      c.filler = filler;
    }
    // First the fillers that change their own cell's look (author rules for empty cells), then any that still move a box.
    for (const c of candidates) {
      if (c.filler !== null && look(c.cell) !== c.look) {
        c.filler.remove();
        c.filler = null;
      }
    }
    for (let round = 0; round < 10; round++) {
      const changed = candidates.filter((c) => c.filler !== null && (look(c.cell) !== c.look || box(c.cell) !== c.box));
      if (changed.length === 0) break;
      for (const c of changed) {
        c.filler!.remove();
        c.filler = null;
      }
    }
    return candidates.filter((c) => c.filler !== null).length;
  });
}

/**
 * Chromium writes a cell's `rowspan` into the PDF's `/RowSpan` as authored, although HTML clamps it to
 * the rows left in the row group and reads `rowspan="0"` as "to the end of the group", so the table
 * becomes irregular. Each such attribute is set to the span the browser already lays out, so nothing
 * moves. Returns how many cells were changed.
 */
export async function clampRowSpans(page: Page): Promise<number> {
  return page.evaluate(() => {
    let changed = 0;
    for (const cell of Array.from(document.querySelectorAll<HTMLTableCellElement>("td[rowspan], th[rowspan]"))) {
      const row = cell.parentElement;
      const group = row?.parentElement;
      if (!(row instanceof HTMLTableRowElement) || !group) continue;
      const rows = Array.from(group.children).filter((r) => r instanceof HTMLTableRowElement);
      const left = rows.length - rows.indexOf(row);
      const written = cell.rowSpan;
      const effective = written === 0 ? left : Math.min(written, left);
      if (effective >= 1 && effective !== written) {
        cell.rowSpan = effective;
        changed += 1;
      }
    }
    return changed;
  });
}
