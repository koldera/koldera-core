package com.koldera.pdf.generated;

import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

/**
 * Tracks the current page-level MCID through nested {@code BDC}/{@code EMC} and form XObjects (an MCID inside a form
 * belongs to the form's own content, so entering a form suspends tracking). Shared by {@link FigureBBox}'s painter and
 * {@link LinkContents}'s text stripper via {@link #begin}/{@link #end} and {@link #enterForm}/{@link #exitForm}.
 */
final class MarkedContentScope {

  /** Stands in for a sequence without a page-level MCID ({@link ArrayDeque} does not hold null). */
  private static final int NO_MCID = -1;

  private final Deque<Integer> marked = new ArrayDeque<>();
  private int formDepth;

  /** Pushes the MCID of {@code properties} if this is a page-level marked content sequence, else a marker for a non-MCID one. */
  void begin(COSDictionary properties) {
    boolean pageLevel = formDepth == 0 && properties != null && properties.containsKey(COSName.MCID);
    marked.push(pageLevel ? properties.getInt(COSName.MCID) : NO_MCID);
  }

  void end() {
    marked.poll();
  }

  void enterForm() {
    formDepth++;
  }

  void exitForm() {
    formDepth--;
  }

  /** The innermost page-level MCID currently open, or null when there is none (inside a form, or no MCID sequence open). */
  Integer current() {
    for (Integer mcid : marked) {
      if (mcid != NO_MCID) {
        return mcid;
      }
    }
    return null;
  }
}
