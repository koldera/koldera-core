package com.koldera.pdf.repair;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.generated.FigureBBox;
import com.koldera.pdf.generated.HeadingLevels;
import com.koldera.pdf.generated.ListNormalizer;
import com.koldera.pdf.generated.StructTree;
import com.koldera.pdf.generated.TableNormalizer;
import com.koldera.pdf.generated.TablePadding;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * {@code PDF_TAGGED_STRUCTURE_REPAIR_V1} (mode {@code fix}, tagged uploads whose tags this run kept): repairs the producer's tree
 * with the normalizers that act only on shapes they recognize: orphan list items, {@code LBody}, row spans and short rows, row
 * groups, Figure boxes, empty elements, heading levels. Chromium-specific steps are not used (they would guess). Only structure
 * dictionaries change. Role-mapped heading types are listed in the reason instead of renumbered.
 */
public final class TaggedStructureRepairRule implements RemediationRule {

  static final String UNTAGGED = "document is not tagged";
  static final String REBUILT = "the tags were rebuilt from the page content in this run";
  static final String HEADINGS_PREFIX = "; headings renumbered: ";
  static final String NOT_RENUMBERED_PREFIX = "; headings not renumbered: role-mapped heading types (";

  private PDDocument inspectedDocument;
  private COSDictionary inspectedRoot;

  /**
   * The heading-level changes an <em>applied</em> {@code PDF_TAGGED_STRUCTURE_REPAIR_V1} action made
   * (for example {@code "H2→H1 ×1, H4→H2 ×1"}), read back from its reason; empty for any other action
   * or when no level changed.
   */
  public static Optional<String> headingsRenumbered(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    int at = action.reason().indexOf(HEADINGS_PREFIX);
    if (at < 0) {
      return Optional.empty();
    }
    String mapping = action.reason().substring(at + HEADINGS_PREFIX.length());
    int end = mapping.indexOf("; ");
    return Optional.of(end >= 0 ? mapping.substring(0, end) : mapping);
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    inspectedDocument = null;
    inspectedRoot = null;
    if (!participates(request)) {
      return RuleDecision.skip("the tag tree is only repaired in fix for uploaded PDFs", null, null);
    }
    PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return RuleDecision.skip(UNTAGGED, null, null);
    }
    inspectedDocument = document;
    inspectedRoot = root.getCOSObject();
    return RuleDecision.apply("tagged upload; lists, tables, figure boxes, empty elements and heading levels are repaired after this run's structure steps", null, null);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    COSDictionary root = inspectedDocument == document ? inspectedRoot : null;
    inspectedDocument = null;
    inspectedRoot = null;
    if (!decision.applicable() || root == null) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDStructureTreeRoot current = document.getDocumentCatalog().getStructureTreeRoot();
    if (current == null || current.getCOSObject() != root) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, REBUILT, null, null);
    }
    int lists;
    int listItems;
    int rowSpans;
    int cells;
    int tables;
    int boxes;
    int empty;
    HeadingLevels.Outcome headings;
    SortedSet<String> mappedHeadings;
    try {
      // Structure dictionaries only. An exception part-way leaves the document half changed and returns FAILED:
      // PdfProcessor never saves a copy when any action FAILED (and every rerun reloads the original bytes).
      lists = ListNormalizer.wrapOrphans(document);
      listItems = ListNormalizer.normalize(document);
      rowSpans = TablePadding.clampRowSpans(document);
      cells = TablePadding.apply(document);
      tables = TableNormalizer.normalize(document);
      boxes = FigureBBox.apply(document);
      empty = EmptyElements.remove(document);
      mappedHeadings = roleMappedHeadingTypes(document);
      headings = mappedHeadings.isEmpty() ? HeadingLevels.renumber(document) : new HeadingLevels.Outcome(0, null);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not repair the tag tree (" + e.getClass().getSimpleName() + ")", null, null);
    }
    String reason = "list items " + listItems + ", lists " + lists + ", empty cells " + cells + ", row spans " + rowSpans + ", tables " + tables
        + ", figure boxes " + boxes + ", empty elements " + empty + ", headings " + headings.headings()
        + (headings.mapping() != null ? HEADINGS_PREFIX + headings.mapping() : "")
        + (mappedHeadings.isEmpty() ? "" : NOT_RENUMBERED_PREFIX + String.join(", ", mappedHeadings) + ")");
    boolean changed = lists + listItems + rowSpans + cells + tables + boxes + empty + headings.headings() > 0;
    return new WorkerResult.Action(id(), changed ? ActionOutcome.APPLIED : ActionOutcome.SKIPPED, reason, null, null);
  }

  /**
   * The element types in the tree whose /RoleMap chain (at most {@link #MAX_ROLE_CHAIN} steps) reaches {@code H} or
   * {@code H1}-{@code H6}, sorted. {@link HeadingLevels} sees raw types only, so renumbering next to such a type would
   * demote headings against one it cannot see.
   */
  static SortedSet<String> roleMappedHeadingTypes(PDDocument document) {
    SortedSet<String> out = new TreeSet<>();
    PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null || !(root.getCOSObject().getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary roleMap)) {
      return out;
    }
    Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Frame> stack = new ArrayDeque<>(); // iterative walk: no recursion depth to overflow
    stack.push(new Frame(root.getCOSObject(), 0));
    while (!stack.isEmpty()) {
      Frame frame = stack.pop();
      COSDictionary node = frame.node();
      if (frame.depth() > MAX_TREE_DEPTH) {
        continue;
      }
      for (COSBase entry : StructTree.entries(node)) {
        if (StructTree.resolve(entry) instanceof COSDictionary kid && kid.getDictionaryObject(COSName.S) instanceof COSName s && visited.add(kid)) {
          if (!isHeading(s.getName()) && mapsToHeading(roleMap, s.getName())) {
            out.add(s.getName());
          }
          stack.push(new Frame(kid, frame.depth() + 1));
        }
      }
    }
    return out;
  }

  private record Frame(COSDictionary node, int depth) {}

  private static final int MAX_ROLE_CHAIN = 16;
  private static final int MAX_TREE_DEPTH = 256;

  private static boolean mapsToHeading(COSDictionary roleMap, String type) {
    Set<String> seen = new HashSet<>();
    String current = type;
    for (int i = 0; i < MAX_ROLE_CHAIN && current != null && seen.add(current); i++) {
      current = roleMap.getDictionaryObject(COSName.getPDFName(current)) instanceof COSName mapped ? mapped.getName() : null;
      if (current != null && isHeading(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isHeading(String type) {
    return "H".equals(type) || type.matches("H[1-6]");
  }
}
