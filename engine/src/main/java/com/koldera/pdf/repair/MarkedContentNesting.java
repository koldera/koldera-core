package com.koldera.pdf.repair;

import com.koldera.pdf.generated.StructTree;
import com.koldera.pdf.repair.MarkedSequences.Seq;
import com.koldera.pdf.structure.NumberTreeView;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * veraPDF 7.1-1/7.1-2 in a tagged upload: an {@code /Artifact} sequence inside tagged content is split out of its host (the
 * rest gets the stream's next free MCID, added to the same element and ParentTree), and a tagged sequence inside an artifact
 * splits the artifact around it, or drops its markers when the MCID is unreferenced. Only when markers share the text
 * object and q/Q depth; any other nesting is left and counted. Only marker operators are inserted, moved or removed.
 */
public final class MarkedContentNesting {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName STM = COSName.getPDFName("Stm");

  private MarkedContentNesting() {}

  public record Outcome(int artifactsSplitOutOfTags, int artifactsSplitAroundTags, int streamsLeftAlone, int unreferencedMarkersRemoved) {}

  public static Outcome repair(PDDocument doc) throws IOException {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return new Outcome(0, 0, 0, 0);
    }
    Map<Integer, COSBase> numbers = NumberTreeView.read(root.getCOSObject().getDictionaryObject(COSName.PARENT_TREE));
    int out = 0;
    int around = 0;
    int alone = 0;
    int unreferenced = 0;
    Set<COSStream> forms = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PDPage page : doc.getPages()) {
      MarkedSequences scan = MarkedSequences.of(page);
      alone += nestedForms(scan.forms, page.getResources(), forms, 0);
      if (scan.seqs.stream().noneMatch(MarkedContentNesting::conflicting)) {
        continue;
      }
      COSArray slots = MarkedSequences.parentTreeArray(page, numbers);
      // Fail closed: an unbalanced stream, or a page whose ParentTree entry it names cannot be read.
      Edits edits = !scan.balanced || (slots == null && page.getCOSObject().containsKey(COSName.STRUCT_PARENTS)) ? null : plan(scan, page, slots);
      if (edits == null) {
        alone++;
        continue;
      }
      PDStream replacement = new PDStream(doc);
      try (OutputStream os = replacement.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(os).writeTokens(edits.rewrite(scan.tokens));
      }
      page.setContents(replacement);
      edits.tree.forEach(Runnable::run);
      out += edits.splitOut;
      around += edits.splitAround;
      unreferenced += edits.unreferenced;
    }
    return new Outcome(out, around, alone, unreferenced);
  }

  /** An artifact inside tagged content, or tagged content inside an artifact. */
  private static boolean conflicting(Seq seq) {
    return (seq.artifact() && seq.hasAncestor(Seq::tagged)) || (seq.tagged() && seq.hasAncestor(Seq::artifact));
  }

  /** Form XObject streams (reached from {@code drawn}, each once) that hold nesting: counted, never rewritten. */
  private static int nestedForms(List<PDFormXObject> drawn, PDResources parent, Set<COSStream> seen, int depth) throws IOException {
    int count = 0;
    for (PDFormXObject form : drawn) {
      if (depth >= MarkedSequences.MAX_FORM_DEPTH || !seen.add(form.getCOSObject())) {
        continue;
      }
      PDResources resources = form.getResources() != null ? form.getResources() : parent;
      MarkedSequences scan = MarkedSequences.of(new PDFStreamParser(form).parse(), resources);
      if (scan.seqs.stream().anyMatch(MarkedContentNesting::conflicting)) {
        count++;
      }
      count += nestedForms(scan.forms, resources, seen, depth + 1);
    }
    return count;
  }

  /** Token edits for one stream and the tree changes that go with them. */
  private static final class Edits {
    final Map<Integer, List<Object>> before = new HashMap<>();
    final Map<Integer, List<Object>> after = new HashMap<>();
    final Set<Integer> dropped = new HashSet<>();
    final List<Runnable> tree = new ArrayList<>();
    int splitOut;
    int splitAround;
    int unreferenced;

    void insertBefore(int index, List<Object> tokens) {
      before.computeIfAbsent(index, k -> new ArrayList<>()).addAll(tokens);
    }

    void insertAfter(int index, List<Object> tokens) {
      after.computeIfAbsent(index, k -> new ArrayList<>()).addAll(tokens);
    }

    void dropOpening(Seq seq) {
      for (int i = seq.operandStart; i <= seq.open; i++) {
        dropped.add(i);
      }
    }

    List<Object> rewrite(List<Object> tokens) {
      List<Object> out = new ArrayList<>(tokens.size() + 16);
      for (int i = 0; i < tokens.size(); i++) {
        out.addAll(before.getOrDefault(i, List.of()));
        if (!dropped.contains(i)) {
          out.add(tokens.get(i));
        }
        out.addAll(after.getOrDefault(i, List.of()));
      }
      return out;
    }
  }

  /** The edits for a stream whose every nesting has one of the two repairable shapes; null when any does not. */
  private static Edits plan(MarkedSequences scan, PDPage page, COSArray slots) {
    Edits edits = new Edits();
    int nextMcid = Math.max(scan.maxMcid(), slots == null ? -1 : slots.size() - 1) + 1;
    for (Seq seq : scan.seqs) {
      if (!conflicting(seq)) {
        continue;
      }
      Seq host = seq.parent;
      if (host == null || host.children.size() != 1 || seq.hasDescendant(s -> s.mcid >= 0) || host.hasAncestor(s -> s.artifact() || s.mcid >= 0)
          || !sameNesting(host, seq)) {
        return null;
      }
      boolean beforeEmpty = !scan.hasOperator(host.open + 1, seq.operandStart);
      boolean afterEmpty = !scan.hasOperator(seq.close + 1, host.close);
      if (seq.artifact() && host.tagged() && seq.mcid < 0) {
        // 7.1-1: the artifact leaves the tagged sequence.
        if (beforeEmpty && afterEmpty) {
          return null;
        }
        if (beforeEmpty) {
          edits.dropOpening(host);
          edits.insertAfter(seq.close, opening(scan, host));
        } else if (afterEmpty) {
          edits.insertBefore(seq.operandStart, List.of(Operator.getOperator("EMC")));
          edits.dropped.add(host.close);
        } else {
          if (!(host.properties instanceof COSDictionary properties)) {
            return null;
          }
          Runnable treeChange = addMcid(page, slots, host.mcid, nextMcid);
          if (treeChange == null) {
            return null;
          }
          COSDictionary copy = new COSDictionary();
          properties.entrySet().forEach(e -> copy.setItem(e.getKey(), e.getValue()));
          copy.setInt(COSName.MCID, nextMcid);
          nextMcid++;
          edits.insertBefore(seq.operandStart, List.of(Operator.getOperator("EMC")));
          edits.insertAfter(seq.close, List.of(host.tag, copy, Operator.getOperator("BDC")));
          edits.tree.add(treeChange);
        }
        edits.splitOut++;
      } else if (seq.tagged() && host.artifact() && host.mcid < 0) {
        // 7.1-2: the tagged sequence leaves the artifact, or loses its marker when nothing references it.
        if (MarkedSequences.slot(slots, seq.mcid) == null) {
          edits.dropOpening(seq);
          edits.dropped.add(seq.close);
          edits.unreferenced++;
          continue;
        }
        if (beforeEmpty && afterEmpty) {
          edits.dropOpening(host);
          edits.dropped.add(host.close);
        } else if (beforeEmpty) {
          edits.dropOpening(host);
          edits.insertAfter(seq.close, opening(scan, host));
        } else if (afterEmpty) {
          edits.insertBefore(seq.operandStart, List.of(Operator.getOperator("EMC")));
          edits.dropped.add(host.close);
        } else {
          edits.insertBefore(seq.operandStart, List.of(Operator.getOperator("EMC")));
          edits.insertAfter(seq.close, opening(scan, host));
        }
        edits.splitAround++;
      } else {
        return null;
      }
    }
    return edits;
  }

  /** Same text object (or outside all) and same q/Q depth at the host's and the inner sequence's markers: moving them crosses neither. */
  private static boolean sameNesting(Seq host, Seq inner) {
    return host.close >= 0 && inner.close >= 0 && host.textAtOpen == inner.textAtOpen && inner.textAtOpen == inner.textAtClose
        && inner.textAtClose == host.textAtClose && host.qAtOpen == inner.qAtOpen && inner.qAtOpen == inner.qAtClose && inner.qAtClose == host.qAtClose;
  }

  /** A copy of a sequence's opening tokens (operands and operator). */
  private static List<Object> opening(MarkedSequences scan, Seq seq) {
    return new ArrayList<>(scan.tokens.subList(seq.operandStart, seq.open + 1));
  }

  /**
   * The tree change that lists {@code added} in the element at slot {@code mcid} right after {@code mcid} and names that
   * element at ParentTree slot {@code added}; null when they cannot be found. Nothing changes until the returned change runs.
   */
  private static Runnable addMcid(PDPage page, COSArray slots, int mcid, int added) {
    COSDictionary element = MarkedSequences.slot(slots, mcid);
    if (element == null || entryFor(element, page.getCOSObject(), mcid, added) == null) {
      return null;
    }
    return () -> {
      List<COSBase> current = StructTree.entries(element);
      Entry found = entryFor(element, page.getCOSObject(), mcid, added);
      if (found == null) {
        throw new IllegalStateException("the element's entry for MCID " + mcid + " disappeared");
      }
      COSArray k = new COSArray();
      for (int j = 0; j < current.size(); j++) {
        k.add(current.get(j));
        if (j == found.index()) {
          k.add(found.added());
        }
      }
      element.setItem(COSName.K, k);
      while (slots.size() <= added) {
        slots.add(COSNull.NULL);
      }
      slots.set(added, slots.get(mcid)); // the same (indirect) reference as slot m
    };
  }

  /** The position of {@code mcid}'s entry in the element's current /K and the entry to add after it for {@code added}; none when it is listed more than once. */
  private record Entry(int index, COSBase added) {}

  private static Entry entryFor(COSDictionary element, COSDictionary pageDict, int mcid, int added) {
    List<COSBase> entries = StructTree.entries(element);
    COSBase elementPage = element.getDictionaryObject(COSName.PG);
    Entry found = null;
    for (int i = 0; i < entries.size(); i++) {
      COSBase entry = StructTree.resolve(entries.get(i));
      Entry match = null;
      if (entry instanceof COSInteger n && n.intValue() == mcid && (elementPage == null || elementPage == pageDict)) {
        match = new Entry(i, COSInteger.get(added));
      }
      if (entry instanceof COSDictionary mcr && MCR.equals(mcr.getCOSName(COSName.TYPE)) && mcr.getInt(COSName.MCID, -1) == mcid && !mcr.containsKey(STM)) {
        COSBase mcrPage = mcr.getDictionaryObject(COSName.PG);
        COSBase owner = mcrPage != null ? mcrPage : elementPage;
        if (owner != null && owner != pageDict) {
          continue;
        }
        COSDictionary copy = new COSDictionary();
        copy.setItem(COSName.TYPE, MCR);
        if (mcr.containsKey(COSName.PG)) {
          copy.setItem(COSName.PG, mcr.getItem(COSName.PG));
        }
        copy.setInt(COSName.MCID, added);
        match = new Entry(i, copy);
      }
      if (match != null) {
        if (found != null) {
          return null; // the MCID is listed twice: which one the new MCID follows would be a guess
        }
        found = match;
      }
    }
    return found;
  }
}
