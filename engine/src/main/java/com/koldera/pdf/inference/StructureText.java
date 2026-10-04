package com.koldera.pdf.inference;

import com.koldera.pdf.generated.LinkContents;
import com.koldera.pdf.generated.StructTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * The text of a structure element: the page-level MCIDs of the element and its descendants in /K order
 * (integer kids with the nearest /Pg, MCRs without /Stm), each MCID's text as {@link
 * LinkContents#textByMcid} reads it, joined by one space and whitespace-collapsed. MCIDs inside form
 * XObjects are not read. Read-only.
 */
public final class StructureText {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final int MAX_DEPTH = 256;

  private final PDDocument doc;
  private final Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
  private final Map<Integer, Map<Integer, String>> textByPage = new HashMap<>();

  /** A reader that caches each page's MCID texts across elements. */
  public StructureText(PDDocument doc) {
    this.doc = doc;
    int n = 1;
    for (PDPage page : doc.getPages()) {
      pageNumbers.put(page.getCOSObject(), n++);
    }
  }

  /** The normalized text of {@code element}'s MCIDs. */
  public static String textOf(PDDocument doc, PDStructureElement element) throws IOException {
    return new StructureText(doc).text(element.getCOSObject());
  }

  /** The normalized text of the element dictionary's MCIDs (this reader's page cache). */
  public String text(COSDictionary element) throws IOException {
    List<String> parts = new ArrayList<>();
    collect(element, element.getItem(COSName.PG), parts, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    return PageLayout.normalize(String.join(" ", parts));
  }

  private void collect(COSDictionary node, COSBase page, List<String> parts, int depth, Set<COSDictionary> visited) throws IOException {
    if (depth > MAX_DEPTH || !visited.add(node)) {
      return;
    }
    COSBase ownPage = node.containsKey(COSName.PG) ? node.getItem(COSName.PG) : page;
    for (COSBase entry : StructTree.entries(node)) {
      COSBase kid = StructTree.resolve(entry);
      if (kid instanceof COSInteger mcid) {
        add(ownPage, mcid.intValue(), parts);
      } else if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE))) {
        if (!d.containsKey(STM)) {
          add(d.containsKey(COSName.PG) ? d.getItem(COSName.PG) : ownPage, d.getInt(COSName.MCID), parts);
        }
      } else if (kid instanceof COSDictionary d && d.containsKey(COSName.S)) {
        collect(d, ownPage, parts, depth + 1, visited);
      }
    }
  }

  private void add(COSBase page, int mcid, List<String> parts) throws IOException {
    if (!(StructTree.resolve(page) instanceof COSDictionary pageDict)) {
      return;
    }
    Integer number = pageNumbers.get(pageDict);
    if (number == null) {
      return;
    }
    Map<Integer, String> byMcid = textByPage.get(number);
    if (byMcid == null) {
      byMcid = LinkContents.textByMcid(doc, number);
      textByPage.put(number, byMcid);
    }
    String text = byMcid.get(mcid);
    if (text != null && !text.isBlank()) {
      parts.add(text);
    }
  }
}
