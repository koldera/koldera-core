package com.koldera.pdf.inference;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Keeps the descriptions ({@code /Alt}, {@code /ActualText}, {@code /E}) of non-Figure elements of a {@code tagged_unusable}
 * upload when its tags are rebuilt, by moving them onto the marked content the element owns (PDF 32000-1 14.9). The keys go
 * into the sequence's own property list or a new {@code /Span} around an exactly contiguous, balanced range; {@code /Lang}
 * is repeated where it differs. Anything else is {@link Refused} (the rule keeps the tags). Marker-only; checked before writing.
 */
final class DescribedElements {

  /** A description cannot be kept on exactly its content; the rule keeps the tags. */
  static final class Refused extends Exception {
    Refused(String detail) {
      super(detail);
    }
  }

  /**
   * What {@link #carry} kept: {@code described} elements whose /Alt, /ActualText or /E went onto their
   * marked content; {@code elements} all elements whose description (including a /Lang alone) was kept;
   * links whose /Alt went to their annotations, and links whose /Alt could not be carried.
   */
  record Outcome(int described, int elements, int linksToAnnotations, int linksNotCarried) {
    static final Outcome NONE = new Outcome(0, 0, 0, 0);
  }

  private static final COSName STM = COSName.getPDFName("Stm");
  private static final COSName SPAN = COSName.getPDFName("Span");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final List<COSName> DESCRIBING = List.of(COSName.ALT, COSName.ACTUAL_TEXT, COSName.E);
  private static final Set<String> CONTENT = Set.of("Tj", "TJ", "'", "\"", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "sh", "Do", "BI");
  private static final Operator BDC = Operator.getOperator("BDC");
  private static final Operator EMC = Operator.getOperator("EMC");

  private DescribedElements() {}

  /** A marked-content reference: stream id and MCID. */
  private record Ref(int stream, int mcid) {}

  private static final class Element {
    final COSDictionary dict;
    final Element parent;
    final int depth;
    final List<Ref> refs = new ArrayList<>();
    final List<Element> kids = new ArrayList<>();
    final List<COSDictionary> annotations = new ArrayList<>();
    boolean figure;
    boolean link;
    boolean described;
    /** An MCID of this element whose page (or stream) cannot be resolved. */
    boolean unresolved;
    /** An MCID of this element that another element references too. */
    boolean shared;
    /** The nearest non-blank /Lang (own, else an ancestor's) and the element it is set on. */
    COSString lang;
    Element langSource;

    Element(COSDictionary dict, Element parent) {
      this.dict = dict;
      this.parent = parent;
      this.depth = parent == null ? 0 : parent.depth + 1;
    }
  }

  /** A marked-content sequence with an MCID: operand start, BDC and EMC token indexes; its inline properties (else null). */
  private record Seq(int start, int bdc, int emc, int mcid, COSDictionary inline, boolean twoOperands) {}

  /** A new {@code /Span} sequence from token {@code start} to token {@code end} (both inclusive). */
  private record Wrapper(int start, int end, int depth, COSDictionary properties) {}

  /** One content stream to rewrite: the page (or form stream), its tokens and what it gains. */
  private static final class Scan {
    final Object owner;
    final List<Object> tokens;
    final Map<Integer, List<Seq>> byMcid = new HashMap<>();
    final Map<Integer, Integer> mcidOfBdc = new HashMap<>();
    final Map<Seq, COSDictionary> additions = new IdentityHashMap<>();
    final List<Wrapper> wrappers = new ArrayList<>();

    Scan(Object owner, List<Object> tokens) {
      this.owner = owner;
      this.tokens = tokens;
    }

    boolean changed() {
      return !additions.isEmpty() || !wrappers.isEmpty();
    }
  }

  /** Whether every description can be kept; writes nothing. */
  static void check(PDDocument document) throws Refused, IOException {
    plan(document);
  }

  /** Moves the descriptions onto the marked content (see the class comment); refuses, writing nothing, otherwise. */
  static Outcome carry(PDDocument document) throws Refused, IOException {
    Plan plan = plan(document);
    for (Scan scan : plan.scans.values()) {
      if (scan.changed()) {
        write(document, scan);
      }
    }
    plan.contents.forEach((annotation, text) -> annotation.setItem(COSName.CONTENTS, copy(text)));
    return plan.outcome;
  }

  private record Plan(Map<Integer, Scan> scans, Map<COSDictionary, COSString> contents, Outcome outcome) {}

  private static Plan plan(PDDocument document) throws Refused, IOException {
    Map<Integer, Scan> scans = new LinkedHashMap<>();
    Map<COSDictionary, COSString> contents = new IdentityHashMap<>();
    var root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return new Plan(scans, contents, Outcome.NONE);
    }
    COSDictionary roleMap = DescribedFigures.roleMap(root.getCOSObject());
    Map<COSDictionary, Integer> pageIndex = new IdentityHashMap<>();
    int p = 0;
    for (PDPage page : document.getPages()) {
      pageIndex.put(page.getCOSObject(), p++);
    }
    Streams streams = new Streams(document, pageIndex);
    List<Element> elements = tree(root.getCOSObject(), roleMap, streams);
    Languages languages = new Languages(document.getDocumentCatalog().getLanguage());

    // Described elements, deepest first: the innermost takes a sequence's own property list.
    List<Element> described = new ArrayList<>();
    for (Element e : elements) {
      if (!e.figure && DESCRIBING.stream().anyMatch(key -> nonBlank(e.dict.getDictionaryObject(key)))) {
        e.described = true;
        described.add(e);
      }
    }
    described.sort(Comparator.comparingInt((Element e) -> e.depth).reversed());
    Set<Element> kept = Collections.newSetFromMap(new IdentityHashMap<>());
    int carried = 0;
    int linksToAnnotations = 0;
    int linksNotCarried = 0;
    for (Element e : described) {
      List<Element> subtree = subtree(e);
      List<Ref> refs = new ArrayList<>();
      for (Element member : subtree) {
        if (member.unresolved) {
          throw new Refused("a described element's content has an MCID whose page cannot be resolved");
        }
        if (member.shared) {
          throw new Refused("an MCID of a described element's content is referenced by two elements");
        }
        refs.addAll(member.refs);
      }
      if (refs.isEmpty()) {
        if (!e.link) {
          throw new Refused("a described element has no marked content");
        }
        // The annotation stays and is re-tagged by the link rule: it can take the text as its /Contents.
        COSString text = firstDescription(e.dict);
        boolean given = false;
        for (Element member : subtree) {
          for (COSDictionary annotation : member.annotations) {
            if (!nonBlank(annotation.getDictionaryObject(COSName.CONTENTS)) && !contents.containsKey(annotation)) {
              contents.put(annotation, text);
              given = true;
            }
          }
        }
        if (given) {
          linksToAnnotations++;
        } else {
          linksNotCarried++;
        }
        continue;
      }
      int stream = refs.get(0).stream();
      Set<Integer> mcids = new HashSet<>();
      for (Ref ref : refs) {
        if (ref.stream() != stream) {
          throw new Refused("a described element's content lies in more than one content stream");
        }
        mcids.add(ref.mcid());
      }
      Scan scan = scan(scans, streams, stream);
      List<Seq> seqs = new ArrayList<>();
      for (int mcid : mcids) {
        List<Seq> found = scan.byMcid.get(mcid);
        if (found == null || found.size() != 1) {
          throw new Refused(found == null ? "a described element's marked content cannot be found" : "an MCID is used twice in one stream");
        }
        seqs.add(found.get(0));
      }
      COSDictionary keys = new COSDictionary();
      keys.setDirect(true);
      for (COSName key : DESCRIBING) {
        if (nonBlank(e.dict.getDictionaryObject(key))) {
          keys.setItem(key, copy((COSString) e.dict.getDictionaryObject(key)));
        }
      }
      if (e.lang != null && !Languages.same(e.lang.getString(), languages.enclosing(e))) {
        keys.setItem(COSName.LANG, copy(e.lang));
      }
      Seq single = seqs.size() == 1 ? seqs.get(0) : null;
      if (single != null && single.inline() != null && !scan.additions.containsKey(single) && none(single.inline(), keys)) {
        String problem = rangeProblem(scan, single.start(), single.emc(), mcids);
        if (problem != null) {
          throw new Refused(problem);
        }
        scan.additions.put(single, keys);
      } else {
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (Seq seq : seqs) {
          if (!seq.twoOperands()) {
            throw new Refused("a marked-content operator has unexpected operands");
          }
          start = Math.min(start, seq.start());
          end = Math.max(end, seq.emc());
        }
        String problem = rangeProblem(scan, start, end, mcids);
        if (problem != null) {
          throw new Refused(problem);
        }
        Wrapper wrapper = new Wrapper(start, end, e.depth, keys);
        if (crosses(scan.wrappers, wrapper)) {
          throw new Refused("two described elements' content overlaps");
        }
        scan.wrappers.add(wrapper);
      }
      kept.add(e);
      carried++;
    }

    // /Lang alone: every sequence whose owner's language differs from the language it is enclosed in.
    for (Element e : elements) {
      if (e.lang == null || Languages.same(e.lang.getString(), languages.enclosingContent(e))) {
        continue;
      }
      for (Ref ref : e.refs) {
        Scan scan;
        try {
          scan = scan(scans, streams, ref.stream());
        } catch (Refused unreadable) {
          continue; // a language is never a reason to keep the tags
        }
        for (Seq seq : scan.byMcid.getOrDefault(ref.mcid(), List.of())) {
          COSDictionary pending = scan.additions.get(seq);
          if (pending != null && pending.containsKey(COSName.LANG) || seq.inline() != null && seq.inline().containsKey(COSName.LANG)
              || rangeProblem(scan, seq.start(), seq.emc(), Set.of(seq.mcid())) != null) {
            continue; // a sequence that crosses a boundary would, once kept, stop the rebuild: the language is dropped
          }
          if (seq.inline() != null) {
            if (pending == null) {
              pending = new COSDictionary();
              pending.setDirect(true);
              scan.additions.put(seq, pending);
            }
            pending.setItem(COSName.LANG, copy(e.lang));
            kept.add(e.langSource);
            continue;
          }
          COSDictionary keys = new COSDictionary();
          keys.setDirect(true);
          keys.setItem(COSName.LANG, copy(e.lang));
          Wrapper wrapper = new Wrapper(seq.start(), seq.emc(), Integer.MAX_VALUE, keys);
          if (seq.twoOperands() && !crosses(scan.wrappers, wrapper)) {
            scan.wrappers.add(wrapper);
            kept.add(e.langSource);
          }
        }
      }
    }
    return new Plan(scans, contents, new Outcome(carried, kept.size(), linksToAnnotations, linksNotCarried));
  }

  /**
   * The language marked content is enclosed in once the descriptions are kept: that of the innermost
   * described element around it (its element language when it has one, else what encloses it), else
   * the catalog's.
   */
  private static final class Languages {
    private final String catalog;
    private final Map<Element, String> effective = new IdentityHashMap<>();

    Languages(String catalog) {
      this.catalog = catalog == null ? "" : catalog;
    }

    /** The language around a described element's own new sequence: its nearest described ancestor's, else the catalog's. */
    String enclosing(Element e) {
      return innermostDescribed(e.parent);
    }

    /** The language the marked content of {@code owner}'s MCIDs is enclosed in (the innermost described ancestor-or-self). */
    String enclosingContent(Element owner) {
      return innermostDescribed(owner);
    }

    private String innermostDescribed(Element from) {
      for (Element a = from; a != null; a = a.parent) {
        if (a.described) {
          return effective(a);
        }
      }
      return catalog;
    }

    private String effective(Element d) {
      String known = effective.get(d);
      if (known == null) {
        known = d.lang != null ? d.lang.getString() : enclosing(d);
        effective.put(d, known);
      }
      return known;
    }

    static boolean same(String a, String b) {
      return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }
  }

  /** The structure tree as elements (parents before children), with their own MCIDs and effective /Lang; iterative, cycle-safe. */
  private static List<Element> tree(COSDictionary root, COSDictionary roleMap, Streams streams) {
    List<Element> out = new ArrayList<>();
    Map<Ref, Element> ownerOf = new HashMap<>();
    Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Object[]> todo = new ArrayDeque<>();
    todo.push(new Object[] {root.getItem(COSName.K), null, null});
    while (!todo.isEmpty()) {
      Object[] frame = todo.pop();
      COSBase node = resolve((COSBase) frame[0]);
      COSDictionary pg = (COSDictionary) frame[1];
      Element parent = (Element) frame[2];
      if (node == null) {
        continue;
      }
      if (node instanceof COSInteger mcid) {
        if (parent != null) {
          own(parent, streams.page(pg), mcid.intValue(), ownerOf);
        }
        continue;
      }
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof COSArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
          todo.push(new Object[] {array.get(i), pg, parent});
        }
      } else if (node instanceof COSDictionary dict) {
        if (OBJR.equals(dict.getCOSName(COSName.TYPE))) {
          if (parent != null && dict.getDictionaryObject(COSName.OBJ) instanceof COSDictionary annotation) {
            parent.annotations.add(annotation);
          }
        } else if (dict.getDictionaryObject(COSName.S) instanceof COSName type) {
          Element element = new Element(dict, parent);
          element.figure = DescribedFigures.mapsToFigure(type.getName(), roleMap);
          element.link = maps(type.getName(), "Link", roleMap);
          if (dict.getDictionaryObject(COSName.LANG) instanceof COSString lang && !lang.getString().isBlank()) {
            element.lang = lang;
            element.langSource = element;
          } else if (parent != null) {
            element.lang = parent.lang;
            element.langSource = parent.langSource;
          }
          if (parent != null) {
            parent.kids.add(element);
          }
          out.add(element);
          COSDictionary ownPg = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary page ? page : pg;
          COSBase k = dict.getItem(COSName.K);
          if (k != null) {
            todo.push(new Object[] {k, ownPg, element});
          }
        } else if (dict.getDictionaryObject(COSName.MCID) instanceof COSInteger mcid && parent != null) {
          COSDictionary page = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary own ? own : pg;
          Integer stream = dict.getDictionaryObject(STM) instanceof COSStream form
              ? (streams.page(page) == null ? null : streams.form(form, page))
              : streams.page(page);
          own(parent, stream, mcid.intValue(), ownerOf);
        }
      }
    }
    return out;
  }

  /** Records {@code mcid} of {@code stream} (null: unresolvable) as {@code element}'s own, flagging unresolved and shared MCIDs. */
  private static void own(Element element, Integer stream, int mcid, Map<Ref, Element> ownerOf) {
    if (stream == null) {
      element.unresolved = true;
      return;
    }
    Ref ref = new Ref(stream, mcid);
    Element before = ownerOf.putIfAbsent(ref, element);
    if (before != null && before != element) {
      before.shared = true;
      element.shared = true;
    }
    element.refs.add(ref);
  }

  /** {@code element} and every descendant, in tree order. */
  private static List<Element> subtree(Element element) {
    List<Element> out = new ArrayList<>();
    Deque<Element> todo = new ArrayDeque<>();
    todo.push(element);
    Set<Element> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while (!todo.isEmpty()) {
      Element next = todo.pop();
      if (!seen.add(next)) {
        continue;
      }
      out.add(next);
      for (int i = next.kids.size() - 1; i >= 0; i--) {
        todo.push(next.kids.get(i));
      }
    }
    return out;
  }

  /** The element's /Alt, else /ActualText, else /E. */
  private static COSString firstDescription(COSDictionary dict) {
    for (COSName key : DESCRIBING) {
      if (dict.getDictionaryObject(key) instanceof COSString text && !text.getString().isBlank()) {
        return text;
      }
    }
    throw new IllegalStateException("not a described element");
  }

  /**
   * Why tokens {@code start}..{@code end} cannot be wrapped as the content of the MCIDs {@code mcids}
   * (null when they can): unbalanced marked content, {@code q}/{@code Q} or {@code BT}/{@code ET}, another
   * MCID, or content outside the element's sequences.
   */
  private static String rangeProblem(Scan scan, int start, int end, Set<Integer> mcids) {
    int q = 0;
    int bt = 0;
    int own = 0;
    Deque<Boolean> open = new ArrayDeque<>();
    for (int i = start; i <= end; i++) {
      if (!(scan.tokens.get(i) instanceof Operator op)) {
        continue;
      }
      String name = op.getName();
      switch (name) {
        case "q" -> q++;
        case "Q" -> q--;
        case "BT" -> bt++;
        case "ET" -> bt--;
        case "BDC", "BMC" -> {
          int mcid = "BDC".equals(name) ? scan.mcidOfBdc.getOrDefault(i, -1) : -1;
          if (mcid >= 0 && !mcids.contains(mcid)) {
            return "another element's content lies between a described element's content";
          }
          boolean mine = mcid >= 0;
          open.push(mine);
          own += mine ? 1 : 0;
        }
        case "EMC" -> {
          if (open.isEmpty()) {
            return "a described element's content is not balanced in marked content";
          }
          own -= open.pop() ? 1 : 0;
        }
        default -> {
          if (CONTENT.contains(name) && own == 0) {
            return "a described element's content is not contiguous";
          }
        }
      }
      if (q < 0 || bt < 0) {
        return "a described element's content crosses a graphics state or text object boundary";
      }
    }
    return q == 0 && bt == 0 && open.isEmpty() ? null : "a described element's content crosses a graphics state or text object boundary";
  }

  /** Whether {@code candidate} partially overlaps one of {@code wrappers} (nested or disjoint is fine). */
  private static boolean crosses(List<Wrapper> wrappers, Wrapper candidate) {
    for (Wrapper w : wrappers) {
      boolean disjoint = candidate.end() < w.start() || w.end() < candidate.start();
      boolean nested = (candidate.start() >= w.start() && candidate.end() <= w.end()) || (w.start() >= candidate.start() && w.end() <= candidate.end());
      if (!disjoint && !nested) {
        return true;
      }
    }
    return false;
  }

  /** The content streams the tree refers to, by id: a page (by its index) or a form XObject (with the resources to read it). */
  private static final class Streams {
    final PDDocument document;
    final Map<COSDictionary, Integer> pageIndex;
    final Map<COSStream, Integer> formIds = new IdentityHashMap<>();
    final List<Object[]> forms = new ArrayList<>();

    Streams(PDDocument document, Map<COSDictionary, Integer> pageIndex) {
      this.document = document;
      this.pageIndex = pageIndex;
    }

    /** Pages are ids 0..n-1; null when the dictionary is not a page of the document. */
    Integer page(COSDictionary page) {
      return page == null ? null : pageIndex.get(page);
    }

    /** Forms are ids from n on; the page is kept for its resources (a form without its own). */
    Integer form(COSStream form, COSDictionary page) {
      Integer known = formIds.get(form);
      if (known != null) {
        return known;
      }
      int id = pageIndex.size() + forms.size();
      formIds.put(form, id);
      forms.add(new Object[] {form, page});
      return id;
    }
  }

  private static Scan scan(Map<Integer, Scan> scans, Streams streams, int id) throws Refused, IOException {
    Scan known = scans.get(id);
    if (known != null) {
      return known;
    }
    Scan scan;
    PDResources resources;
    if (id < streams.pageIndex.size()) {
      PDPage page = streams.document.getPage(id);
      resources = page.getResources();
      scan = new Scan(page, page.hasContents() ? new PDFStreamParser(page).parse() : new ArrayList<>());
    } else {
      Object[] entry = streams.forms.get(id - streams.pageIndex.size());
      COSStream form = (COSStream) entry[0];
      COSDictionary own = form.getCOSDictionary(COSName.RESOURCES);
      Integer page = streams.page((COSDictionary) entry[1]);
      resources = own != null ? new PDResources(own) : page != null ? streams.document.getPage(page).getResources() : null;
      scan = new Scan(form, new PDFStreamParser(new PDFormXObject(form)).parse());
    }
    Deque<Object[]> open = new ArrayDeque<>();
    int operandStart = 0;
    for (int i = 0; i < scan.tokens.size(); i++) {
      if (!(scan.tokens.get(i) instanceof Operator op)) {
        continue;
      }
      List<Object> operands = scan.tokens.subList(operandStart, i);
      switch (op.getName()) {
        case "BDC" -> {
          COSDictionary properties = operands.size() == 2 ? properties(operands.get(1), resources) : null;
          int mcid = properties != null && properties.getDictionaryObject(COSName.MCID) instanceof COSInteger m ? m.intValue() : -1;
          if (mcid >= 0) {
            scan.mcidOfBdc.put(i, mcid);
          }
          COSDictionary inline = operands.size() == 2 && operands.get(1) instanceof COSDictionary d ? d : null;
          open.push(new Object[] {operandStart, i, mcid, inline, operands.size() == 2});
        }
        case "BMC" -> open.push(new Object[] {operandStart, i, -1, null, false});
        case "EMC" -> {
          if (open.isEmpty()) {
            throw new Refused("marked content is not balanced");
          }
          Object[] begin = open.pop();
          int mcid = (Integer) begin[2];
          if (mcid >= 0) {
            scan.byMcid.computeIfAbsent(mcid, k -> new ArrayList<>())
                .add(new Seq((Integer) begin[0], (Integer) begin[1], i, mcid, (COSDictionary) begin[3], (Boolean) begin[4]));
          }
        }
        default -> {
          // other operators are not marked content
        }
      }
      operandStart = i + 1;
    }
    if (!open.isEmpty()) {
      throw new Refused("marked content is not balanced");
    }
    scans.put(id, scan);
    return scan;
  }

  private static void write(PDDocument document, Scan scan) throws IOException {
    for (Map.Entry<Seq, COSDictionary> addition : scan.additions.entrySet()) {
      for (COSName key : addition.getValue().keySet()) {
        addition.getKey().inline().setItem(key, addition.getValue().getItem(key));
      }
    }
    Map<Integer, List<Wrapper>> opens = new HashMap<>();
    Map<Integer, Integer> closes = new HashMap<>();
    for (Wrapper w : scan.wrappers) {
      opens.computeIfAbsent(w.start(), k -> new ArrayList<>()).add(w);
      closes.merge(w.end(), 1, Integer::sum);
    }
    // At one position the outer sequence opens first: the longer one, then the shallower element.
    opens.values().forEach(list -> list.sort(Comparator.comparingInt(Wrapper::end).reversed().thenComparingInt(Wrapper::depth)));
    List<Object> out = new ArrayList<>(scan.tokens.size() + 4 * scan.wrappers.size());
    for (int i = 0; i < scan.tokens.size(); i++) {
      for (Wrapper w : opens.getOrDefault(i, List.of())) {
        out.add(SPAN);
        out.add(w.properties());
        out.add(BDC);
      }
      out.add(scan.tokens.get(i));
      for (int n = closes.getOrDefault(i, 0); n > 0; n--) {
        out.add(EMC);
      }
    }
    if (scan.owner instanceof PDPage page) {
      PDStream replacement = new PDStream(document);
      try (OutputStream os = replacement.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(os).writeTokens(out);
      }
      page.setContents(replacement);
    } else if (scan.owner instanceof COSStream form) {
      form.removeItem(COSName.DECODE_PARMS);
      form.removeItem(COSName.DP);
      form.removeItem(COSName.FILTER);
      try (OutputStream os = form.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(os).writeTokens(out);
      }
    }
  }

  /** Whether {@code properties} has none of {@code keys}' keys. */
  private static boolean none(COSDictionary properties, COSDictionary keys) {
    for (COSName key : keys.keySet()) {
      if (properties.containsKey(key)) {
        return false;
      }
    }
    return true;
  }

  private static boolean maps(String type, String target, COSDictionary roleMap) {
    Set<String> chain = new HashSet<>();
    String current = type;
    while (current != null && chain.add(current)) {
      if (target.equals(current)) {
        return true;
      }
      COSName mapped = roleMap == null ? null : roleMap.getCOSName(COSName.getPDFName(current));
      current = mapped == null ? null : mapped.getName();
    }
    return false;
  }

  private static COSDictionary properties(Object operand, PDResources resources) {
    if (operand instanceof COSDictionary inline) {
      return inline;
    }
    if (operand instanceof COSName named && resources != null) {
      COSDictionary all = resources.getCOSObject().getCOSDictionary(COSName.PROPERTIES);
      if (all != null && all.getDictionaryObject(named) instanceof COSDictionary dict) {
        return dict;
      }
    }
    return null;
  }

  private static COSString copy(COSString value) {
    return new COSString(value.getBytes());
  }

  private static boolean nonBlank(COSBase value) {
    return value instanceof COSString text && !text.getString().isBlank();
  }

  private static COSBase resolve(COSBase value) {
    return value instanceof COSObject ref ? ref.getObject() : value;
  }
}
