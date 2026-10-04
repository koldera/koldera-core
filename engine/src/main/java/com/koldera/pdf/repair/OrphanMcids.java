package com.koldera.pdf.repair;

import com.koldera.pdf.repair.MarkedSequences.Seq;
import com.koldera.pdf.structure.NumberTreeView;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Marked content in a page stream of a tagged upload whose MCID no ParentTree slot references belongs to no element. A
 * sequence showing no text becomes an artifact (only its opening marker changes); one showing text is left and counted,
 * never attached to an element on a guess. Nested, unbalanced or unmappable cases are left as they are.
 */
public final class OrphanMcids {

  private OrphanMcids() {}

  public record Outcome(int markedArtifact, int textLeft) {}

  public static Outcome repair(PDDocument doc) throws IOException {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return new Outcome(0, 0);
    }
    Map<Integer, COSBase> numbers = NumberTreeView.read(root.getCOSObject().getDictionaryObject(COSName.PARENT_TREE));
    int marked = 0;
    int textLeft = 0;
    for (PDPage page : doc.getPages()) {
      MarkedSequences scan = MarkedSequences.of(page);
      if (!scan.balanced) {
        continue;
      }
      COSArray slots = MarkedSequences.parentTreeArray(page, numbers);
      if (slots == null && page.getCOSObject().containsKey(COSName.STRUCT_PARENTS)) {
        continue; // fail closed: the page names a ParentTree entry that cannot be read, so nothing is known to be unreferenced
      }
      Map<Integer, List<Object>> openings = new HashMap<>();
      for (Seq seq : scan.seqs) {
        if (seq.mcid < 0 || MarkedSequences.slot(slots, seq.mcid) != null || seq.hasAncestor(Seq::tagged) || seq.hasDescendant(s -> s.mcid >= 0)) {
          continue;
        }
        if (seq.showsText) {
          textLeft++;
          continue;
        }
        openings.put(seq.operandStart, artifactOpening(seq));
        marked += 1;
      }
      if (openings.isEmpty()) {
        continue;
      }
      List<Object> out = new ArrayList<>(scan.tokens.size());
      int skipUntil = -1;
      for (int i = 0; i < scan.tokens.size(); i++) {
        List<Object> opening = openings.get(i);
        if (opening != null) {
          out.addAll(opening);
          skipUntil = openEnd(scan, i);
        }
        if (i > skipUntil) {
          out.add(scan.tokens.get(i));
        }
      }
      PDStream replacement = new PDStream(doc);
      try (OutputStream os = replacement.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(os).writeTokens(out);
      }
      page.setContents(replacement);
    }
    return new Outcome(marked, textLeft);
  }

  /** The index of the opening operator of the sequence whose operands start at {@code operandStart}. */
  private static int openEnd(MarkedSequences scan, int operandStart) {
    for (Seq seq : scan.seqs) {
      if (seq.operandStart == operandStart) {
        return seq.open;
      }
    }
    throw new IllegalStateException("no sequence opens at " + operandStart);
  }

  private static List<Object> artifactOpening(Seq seq) {
    if (seq.artifact() && seq.properties instanceof COSDictionary properties && properties.size() > 1) {
      COSDictionary kept = new COSDictionary();
      properties.entrySet().stream().filter(e -> !COSName.MCID.equals(e.getKey())).forEach(e -> kept.setItem(e.getKey(), e.getValue()));
      return List.of(COSName.ARTIFACT, kept, Operator.getOperator("BDC"));
    }
    return List.of(COSName.ARTIFACT, Operator.getOperator("BMC"));
  }
}
