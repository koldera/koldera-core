package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * {@code PDF_ROLE_MAP_V1}: PDF/UA-1 (7.1-5) requires non-standard structure types to be role-mapped. For each PDF 2.0 type in
 * {@link #PDF_2_0_EQUIVALENTS} (e.g. {@code Strong}, {@code Aside}) without an entry, the PDF 1.7 equivalent is added. Existing
 * entries are never replaced, unlisted types are counted but not guessed, {@code FENote} needs an /ID on every element,
 * {@code Title} maps to {@code H1} only in generated PDFs, cycles, deep trees and namespaced trees are left alone.
 */
public final class RoleMapRule implements RemediationRule {

  /** ISO 32000-1:2008 14.8.4 standard structure types. */
  public static final Set<String> STANDARD_TYPES = Set.of(
      "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index", "NonStruct", "Private",
      "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody",
      "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
      "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
      "Figure", "Formula", "Form");

  /**
   * PDF 2.0 (ISO 32000-2) standard types that PDF 1.7 lacks, and the PDF 1.7 type with the same
   * role: inline emphasis and subscripts are spans, a document fragment and an aside are generic
   * divisions (an aside is not mapped to Note, which PDF/UA-1 would then require an /ID for), a title
   * is the top-level heading, and a footnote or endnote is a Note (see the /ID condition above).
   */
  public static final Map<String, String> PDF_2_0_EQUIVALENTS = Map.of(
      "Strong", "Span",
      "Em", "Span",
      "Sub", "Span",
      "Aside", "Div",
      "DocumentFragment", "Div",
      "Title", "H1",
      "FENote", "Note");

  private static final COSName NAMESPACES = COSName.getPDFName("Namespaces");
  private static final COSName NS = COSName.getPDFName("NS");
  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_ROLE_MAP_V1;
  }

  /** {@code fix} only; the deprecated modes keep their behaviour and analyze changes nothing. */
  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("role mapping only runs in fix", null, null);
    }
    Plan plan = plan(document, request.isHtmlGenerated());
    if (plan.refusal() != null) {
      return RuleDecision.skip(plan.refusal(), plan.before(), null);
    }
    if (plan.additions().isEmpty()) {
      return RuleDecision.skip(nothingToMap(plan), plan.before(), null);
    }
    return RuleDecision.apply("non-standard structure types without a role mapping: " + plan.additions().size(), plan.before(), mappings(plan.additions()));
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    // Planned again on the document as it is now: a rule applied earlier in this run may have changed the tree.
    // Only a generated PDF's inspection can have planned Title→H1, so the decision says which kind of PDF this is.
    Plan plan = plan(document, decision.after() != null && decision.after().contains("Title→"));
    if (plan.refusal() != null || plan.additions().isEmpty()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, plan.refusal() != null ? plan.refusal() : nothingToMap(plan), decision.before(), null);
    }
    COSDictionary root = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    COSDictionary roleMap = root.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary existing ? existing : null;
    boolean existed = roleMap != null;
    if (roleMap == null) {
      roleMap = new COSDictionary();
      root.setItem(COSName.ROLE_MAP, roleMap);
    }
    for (Map.Entry<String, String> add : plan.additions().entrySet()) {
      roleMap.setItem(COSName.getPDFName(add.getKey()), COSName.getPDFName(add.getValue()));
    }
    int n = plan.additions().size();
    StringBuilder reason = new StringBuilder("mapped ").append(n)
        .append(n == 1 ? " non-standard structure type to its standard PDF 1.7 equivalent: " : " non-standard structure types to their standard PDF 1.7 equivalents: ")
        .append(mappings(plan.additions()));
    if (existed) {
      reason.append("; existing mappings kept");
    }
    reason.append(leftUnmapped(plan));
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason.toString(), plan.before(), mappings(plan.additions()));
  }

  /** What the rule would add, or why it refuses; {@code unknown} counts types left unmapped for lack of an equivalent. */
  private record Plan(String refusal, String before, Map<String, String> additions, int unknown, List<String> notes) {}

  private static Plan plan(PDDocument document, boolean generated) {
    PDStructureTreeRoot treeRoot = document.getDocumentCatalog().getStructureTreeRoot();
    if (treeRoot == null) {
      return new Plan("document is not tagged; there are no structure types to map", null, Map.of(), 0, List.of());
    }
    COSDictionary root = treeRoot.getCOSObject();
    COSBase rawMap = root.getDictionaryObject(COSName.ROLE_MAP);
    String before = rawMap == null ? "RoleMap absent" : rawMap instanceof COSDictionary d ? "RoleMap has " + d.size() + (d.size() == 1 ? " entry" : " entries") : "RoleMap malformed";
    if (rawMap != null && !(rawMap instanceof COSDictionary)) {
      return new Plan("the existing /RoleMap is not a dictionary; it is left for a person to look at", before, Map.of(), 0, List.of());
    }
    if (root.containsKey(NAMESPACES)) {
      return new Plan("the structure tree uses namespaces (PDF 2.0); its role mapping is left unchanged", before, Map.of(), 0, List.of());
    }
    Map<String, List<COSDictionary>> byType = new LinkedHashMap<>();
    Walk walk = collect(root, byType, new IdentityHashMap<>(), 0);
    if (walk == Walk.NAMESPACE) {
      return new Plan("the structure tree uses namespaces (PDF 2.0); its role mapping is left unchanged", before, Map.of(), 0, List.of());
    }
    if (walk == Walk.TOO_DEEP) {
      return new Plan("the structure tree is too deep to walk; its role mapping is left unchanged", before, Map.of(), 0, List.of());
    }
    COSDictionary roleMap = (COSDictionary) rawMap;
    Map<String, String> additions = new TreeMap<>();
    int unknown = 0;
    List<String> notes = new ArrayList<>();
    for (Map.Entry<String, List<COSDictionary>> used : byType.entrySet()) {
      String type = used.getKey();
      if (STANDARD_TYPES.contains(type) || (roleMap != null && roleMap.containsKey(COSName.getPDFName(type)))) {
        continue;
      }
      String target = PDF_2_0_EQUIVALENTS.get(type);
      if (target == null) {
        unknown++;
      } else if ("Note".equals(target) && !used.getValue().stream().allMatch(e -> e.getDictionaryObject(COSName.ID) != null)) {
        notes.add(type + " is left unmapped: a Note needs an /ID on every element");
      } else if ("Title".equals(type) && !generated) {
        notes.add("Title is left unmapped in an uploaded PDF: an added H1 could break its heading order");
      } else if (leadsBack(roleMap, target, type)) {
        notes.add(type + " is left unmapped: the existing role map already leads from " + target + " back to it");
      } else {
        additions.put(type, target);
      }
    }
    return new Plan(null, before, additions, unknown, notes);
  }

  /** True when following the existing role map from {@code from} reaches {@code to} (the addition would close a cycle). */
  private static boolean leadsBack(COSDictionary roleMap, String from, String to) {
    String current = from;
    for (int i = 0; roleMap != null && i < 64; i++) {
      if (current.equals(to)) {
        return true;
      }
      if (!(roleMap.getDictionaryObject(COSName.getPDFName(current)) instanceof COSName next)) {
        return false;
      }
      current = next.getName();
    }
    return roleMap != null;
  }

  private enum Walk { OK, NAMESPACE, TOO_DEEP }

  /** Collects the elements under {@code node} by /S; stops at an element that declares a namespace or past 512 levels. */
  private static Walk collect(COSDictionary node, Map<String, List<COSDictionary>> byType, Map<COSDictionary, Boolean> seen, int depth) {
    if (depth > 512) {
      return Walk.TOO_DEEP;
    }
    for (COSBase entry : kids(node)) {
      COSBase kid = entry instanceof COSObject ref ? ref.getObject() : entry;
      if (!(kid instanceof COSDictionary element) || seen.put(element, Boolean.TRUE) != null) {
        continue;
      }
      if (MCR.equals(element.getCOSName(COSName.TYPE)) || OBJR.equals(element.getCOSName(COSName.TYPE)) || !(element.getDictionaryObject(COSName.S) instanceof COSName s)) {
        continue;
      }
      if (element.containsKey(NS)) {
        return Walk.NAMESPACE;
      }
      byType.computeIfAbsent(s.getName(), k -> new ArrayList<>()).add(element);
      Walk below = collect(element, byType, seen, depth + 1);
      if (below != Walk.OK) {
        return below;
      }
    }
    return Walk.OK;
  }

  private static List<COSBase> kids(COSDictionary node) {
    COSBase k = node.getDictionaryObject(COSName.K);
    List<COSBase> out = new ArrayList<>();
    if (k instanceof COSArray array) {
      array.forEach(out::add);
    } else if (k != null) {
      out.add(node.getItem(COSName.K));
    }
    return out;
  }

  private static String mappings(Map<String, String> additions) {
    List<String> out = new ArrayList<>();
    additions.forEach((from, to) -> out.add(from + "→" + to));
    return String.join(", ", out);
  }

  private static String nothingToMap(Plan plan) {
    String left = leftUnmapped(plan);
    return left.isEmpty() ? "every structure type is standard or already mapped; nothing to map" : "nothing to map" + left;
  }

  private static String leftUnmapped(Plan plan) {
    StringBuilder out = new StringBuilder();
    for (String note : new TreeSet<>(plan.notes())) {
      out.append("; ").append(note);
    }
    if (plan.unknown() > 0) {
      out.append("; ").append(plan.unknown())
          .append(plan.unknown() == 1 ? " non-standard type has no known standard equivalent and is left unmapped" : " non-standard types have no known standard equivalent and are left unmapped");
    }
    return out.toString();
  }
}
