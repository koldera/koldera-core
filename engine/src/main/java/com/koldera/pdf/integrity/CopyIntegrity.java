package com.koldera.pdf.integrity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * The engine's own check of a saved copy, independent of veraPDF: a strict re-parse of every object, then a structural
 * self-check counted per problem kind (ParentTree consistency, structure tree references and parents, values written as
 * references to missing objects, content operator state, duplicate MCIDs). A copy is compared with its input
 * ({@link #regression}): a problem kind that grew blocks the copy; a defect the input already had never does.
 */
public final class CopyIntegrity {

  public static final String PARENT_TREE_ENTRY_NOT_OWNED = "ParentTree entry points at an element that does not own that MCID";
  public static final String MCID_WITHOUT_PARENT_TREE_ENTRY = "MCID of a structure element has no matching ParentTree entry";
  public static final String CONTENT_MCID_WITHOUT_PARENT_TREE_ENTRY = "marked content has an MCID with no ParentTree entry";
  public static final String PARENT_TREE_ENTRY_WITHOUT_CONTENT = "ParentTree entry for an MCID that is not in the content";
  public static final String CONTENT_MCID_WITHOUT_STRUCT_PARENTS = "stream with MCIDs but no StructParents";
  public static final String DUPLICATE_MCID = "MCID used twice in one content stream";
  public static final String NUMBER_TREE_KEYS_UNSORTED = "ParentTree keys out of order or repeated";
  public static final String NUMBER_TREE_KEY_NOT_INTEGER = "ParentTree key is not a direct integer";
  public static final String NUMBER_TREE_LIMITS = "ParentTree /Limits do not match the keys";
  public static final String NEXT_KEY_TOO_LOW = "ParentTreeNextKey is not above the highest key";
  public static final String STRUCT_PARENTS_SHARED = "StructParents key shared by different objects";
  public static final String OBJR_WITHOUT_PARENT_TREE_ENTRY = "object reference with no matching ParentTree entry";
  public static final String DANGLING_PAGE = "structure refers to a page that is not in the document";
  public static final String DANGLING_STREAM = "marked-content reference names a /Stm that is not a stream";
  public static final String DANGLING_OBJECT = "object reference names no object";
  public static final String MCID_WITHOUT_PAGE = "MCID with no page";
  public static final String WRONG_PARENT_LINK = "structure element /P is not its parent";
  public static final String STRUCTURE_CYCLE = "structure tree contains a cycle";
  public static final String INDIRECT_NUMBER = "reference in place of a direct value at";
  public static final String DANGLING_REFERENCE = "reference to an object that does not exist at";
  public static final String OPERATOR_OUT_OF_STATE = "content operator outside its allowed state (ISO 32000-2 Figure 9)";
  public static final String CONTENT_UNPARSEABLE = "content stream cannot be parsed";
  public static final String SELF_CHECK_INCOMPLETE = "self-check stopped on a malformed object";

  /**
   * @param parseFailure why a strict re-parse failed, or {@code null}
   * @param problems problem kind to count; kinds with no occurrence are absent
   */
  public record Report(String parseFailure, Map<String, Integer> problems) {
    public Report {
      problems = Collections.unmodifiableMap(new TreeMap<>(problems));
    }
  }

  private static final COSName STM = COSName.getPDFName("Stm");
  private static final COSName OBJ = COSName.getPDFName("Obj");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final int MAX_NUMBER_TREE_DEPTH = 32;

  private CopyIntegrity() {}

  // ---- comparison --------------------------------------------------------------------------------

  /** {@code null} when the copy has no problem kind more often than its input; otherwise the reason, content-free. */
  public static String regression(Report before, Report after) {
    if (after.parseFailure() != null) {
      return "copy integrity: " + after.parseFailure();
    }
    List<String> grown = new ArrayList<>();
    for (Map.Entry<String, Integer> e : after.problems().entrySet()) {
      int was = before.problems().getOrDefault(e.getKey(), 0);
      if (e.getValue() > was) {
        grown.add(e.getKey() + " " + was + " -> " + e.getValue());
      }
    }
    return grown.isEmpty() ? null : "copy integrity: " + String.join("; ", grown);
  }

  // ---- strict re-parse ---------------------------------------------------------------------------

  /** Strictly re-parses {@code file} (every object is read), then {@linkplain #inspect(PDDocument) inspects} it. */
  public static Report inspect(Path file) {
    StrictParser parser;
    PDDocument document;
    try {
      parser = new StrictParser(new RandomAccessReadBufferedFile(file.toFile()));
      document = parser.parse();
    } catch (IOException | RuntimeException e) {
      return new Report("the saved copy does not parse strictly (" + e.getClass().getSimpleName() + ")", Map.of());
    }
    try (document) {
      Map<Long, Long> streamLengths = new HashMap<>();
      for (COSObjectKey key : new ArrayList<>(document.getDocument().getXrefTable().keySet())) {
        COSBase object = parser.dereferenceCOSObject(document.getDocument().getObjectFromPool(key));
        if (object instanceof COSStream stream) {
          streamLengths.put(key.getNumber(), stream.getDictionaryObject(COSName.LENGTH) instanceof COSNumber n ? n.longValue() : -1L);
        }
      }
      String syntax = SavedFileSyntax.check(file, streamLengths);
      if (syntax != null) {
        return new Report("the saved copy does not parse strictly (" + syntax + ")", Map.of());
      }
      return inspect(document);
    } catch (IOException | RuntimeException e) {
      return new Report("the saved copy does not parse strictly (" + e.getClass().getSimpleName() + ")", Map.of());
    }
  }

  private static final class StrictParser extends PDFParser {
    StrictParser(RandomAccessRead source) throws IOException {
      super(source);
      setLenient(false);
    }
  }

  // ---- self-check --------------------------------------------------------------------------------

  /** Counts the problems of an already loaded document; reads, never modifies. */
  public static Report inspect(PDDocument document) {
    Map<String, Integer> problems = new HashMap<>();
    try {
      countIndirectScalars(document, problems);
      List<StreamInfo> streams = contentStreams(document, problems);
      COSDictionary structRoot = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
      if (structRoot != null) {
        checkStructure(document, structRoot, streams, problems);
      }
    } catch (RuntimeException e) {
      // A malformed input may stop the walk; the counts so far stand, and a copy whose own walk
      // stops too is not blocked for it. A copy whose walk gets further can only count more.
      add(problems, SELF_CHECK_INCOMPLETE);
    }
    return new Report(null, problems);
  }

  private static void add(Map<String, Integer> problems, String kind) {
    problems.merge(kind, 1, Integer::sum);
  }

  // ---- values ------------------------------------------------------------------------------------

  /** Every object reachable from the trailer; a reference whose target is a number, name, boolean or null is counted by key. */
  private static void countIndirectScalars(PDDocument document, Map<String, Integer> problems) {
    Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<COSObjectKey> existing = document.getDocument().getXrefTable().keySet();
    Deque<Visit> todo = new ArrayDeque<>();
    todo.push(new Visit(document.getDocument().getTrailer(), ""));
    while (!todo.isEmpty()) {
      Visit visit = todo.pop();
      if (!seen.add(visit.node())) {
        continue;
      }
      if (visit.node() instanceof COSDictionary dict) {
        for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
          follow(e.getValue(), "/" + e.getKey().getName(), existing, todo, problems);
        }
      } else if (visit.node() instanceof COSArray array) {
        // array items are labelled with the key that holds the array: /Nums[], /K[], /Widths[]
        for (COSBase item : array) {
          follow(item, visit.label() + "[]", existing, todo, problems);
        }
      }
    }
  }

  /** A dictionary or array still to visit; {@code label} is the key that holds it. */
  private record Visit(COSBase node, String label) {}

  private static void follow(COSBase value, String label, Set<COSObjectKey> existing, Deque<Visit> todo, Map<String, Integer> problems) {
    COSBase target = value;
    if (value instanceof COSObject reference) {
      target = reference.getObject();
      boolean missing = (target == null || target instanceof COSNull) && (reference.getKey() == null || !existing.contains(reference.getKey()));
      if (missing) {
        add(problems, DANGLING_REFERENCE + " " + label);
      } else if (target == null || target instanceof COSNumber || target instanceof COSName || target instanceof COSBoolean || target instanceof COSNull) {
        add(problems, INDIRECT_NUMBER + " " + label);
      }
    }
    if (target instanceof COSArray || target instanceof COSDictionary) {
      todo.push(new Visit(target, label));
    }
  }

  // ---- content streams ---------------------------------------------------------------------------

  /** A page's content or a form reachable from a page: its StructParents key (or -1) and the MCIDs it contains. */
  private record StreamInfo(COSBase owner, COSDictionary page, int structParents, Set<Integer> mcids) {}

  private static List<StreamInfo> contentStreams(PDDocument document, Map<String, Integer> problems) {
    List<StreamInfo> out = new ArrayList<>();
    Set<COSBase> seenForms = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PDPage page : document.getPages()) {
      COSDictionary pageDict = page.getCOSObject();
      if (page.hasContents()) {
        out.add(scan(page, pageDict, pageDict, problems));
      }
      forms(page.getCOSObject().getCOSDictionary(COSName.RESOURCES), pageDict, seenForms, out, problems, 0);
    }
    return out;
  }

  private static void forms(COSDictionary resources, COSDictionary page, Set<COSBase> seen, List<StreamInfo> out, Map<String, Integer> problems, int depth) {
    if (resources == null || depth > 16) {
      return;
    }
    COSDictionary xObjects = resources.getCOSDictionary(COSName.XOBJECT);
    if (xObjects == null) {
      return;
    }
    for (COSName name : xObjects.keySet()) {
      if (xObjects.getDictionaryObject(name) instanceof COSStream stream
          && COSName.FORM.equals(stream.getCOSName(COSName.SUBTYPE))
          && seen.add(stream)) {
        PDFormXObject form = new PDFormXObject(stream);
        out.add(scan(form, stream, page, problems));
        forms(stream.getCOSDictionary(COSName.RESOURCES), page, seen, out, problems, depth + 1);
      }
    }
  }

  private static StreamInfo scan(PDContentStream stream, COSDictionary ownerDict, COSDictionary page, Map<String, Integer> problems) {
    int structParents = ownerDict.getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber n ? n.intValue() : -1;
    Set<Integer> mcids = new HashSet<>();
    List<Object> tokens;
    try {
      tokens = new PDFStreamParser(stream).parse();
    } catch (IOException | RuntimeException e) {
      add(problems, CONTENT_UNPARSEABLE);
      return new StreamInfo(ownerDict, page, structParents, mcids);
    }
    COSDictionary resources = stream.getResources() == null ? null : stream.getResources().getCOSObject();
    OperatorStates states = new OperatorStates();
    List<COSBase> operands = new ArrayList<>();
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      if (!states.accept(op.getName())) {
        add(problems, OPERATOR_OUT_OF_STATE);
      }
      if ("BDC".equals(op.getName())) {
        int mcid = mcid(operands, resources);
        if (mcid >= 0 && !mcids.add(mcid)) {
          add(problems, DUPLICATE_MCID);
        }
      }
      operands.clear();
    }
    return new StreamInfo(ownerDict, page, structParents, mcids);
  }

  private static int mcid(List<COSBase> operands, COSDictionary resources) {
    if (operands.size() != 2) {
      return -1;
    }
    COSDictionary properties = null;
    if (operands.get(1) instanceof COSDictionary inline) {
      properties = inline;
    } else if (operands.get(1) instanceof COSName named && resources != null) {
      COSDictionary all = resources.getCOSDictionary(COSName.PROPERTIES);
      if (all != null && all.getDictionaryObject(named) instanceof COSDictionary dict) {
        properties = dict;
      }
    }
    return properties != null && properties.getDictionaryObject(COSName.MCID) instanceof COSNumber n ? n.intValue() : -1;
  }

  /** ISO 32000-2 Figure 9, as far as a content stream can break it; a stray {@code n} is tolerated. */
  static final class OperatorStates {
    private static final Set<String> PATH_START = Set.of("m", "re");
    private static final Set<String> PATH_CONTINUE = Set.of("l", "c", "v", "y", "h");
    private static final Set<String> PAINT = Set.of("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n");
    private static final Set<String> CLIP = Set.of("W", "W*");
    private static final Set<String> PAGE_ONLY = Set.of("q", "Q", "cm", "Do", "sh", "BI", "d0", "d1");
    private static final Set<String> TEXT_ONLY = Set.of("Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"");

    private enum State { PAGE, TEXT, PATH, CLIP }

    private State state = State.PAGE;

    /** Advances the state; {@code false} when {@code op} is not allowed in the current state. */
    boolean accept(String op) {
      if (PATH_START.contains(op)) {
        if (state == State.PAGE || state == State.PATH) {
          state = State.PATH;
          return true;
        }
        return false;
      }
      if (PATH_CONTINUE.contains(op)) {
        return state == State.PATH;
      }
      if (CLIP.contains(op)) {
        if (state == State.PATH) {
          state = State.CLIP;
          return true;
        }
        return false;
      }
      if (PAINT.contains(op)) {
        if (state == State.PATH || state == State.CLIP) {
          state = State.PAGE;
          return true;
        }
        return "n".equals(op) && state == State.PAGE;
      }
      if ("BT".equals(op)) {
        if (state == State.PAGE) {
          state = State.TEXT;
          return true;
        }
        return false;
      }
      if ("ET".equals(op)) {
        if (state == State.TEXT) {
          state = State.PAGE;
          return true;
        }
        return false;
      }
      if (PAGE_ONLY.contains(op)) {
        return state == State.PAGE;
      }
      if (TEXT_ONLY.contains(op)) {
        return state == State.TEXT;
      }
      // general and text state, colour, marked content, compatibility: page level or text object
      return state == State.PAGE || state == State.TEXT;
    }
  }

  // ---- structure ---------------------------------------------------------------------------------

  private record Owned(int key, int mcid) {}

  private static void checkStructure(PDDocument document, COSDictionary structRoot, List<StreamInfo> streams, Map<String, Integer> problems) {
    Set<COSBase> pages = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PDPage page : document.getPages()) {
      pages.add(page.getCOSObject());
    }

    // ParentTree
    Map<Integer, COSBase> parentTree = new HashMap<>();
    COSBase treeNode = structRoot.getDictionaryObject(COSName.PARENT_TREE);
    List<Integer> keysInOrder = new ArrayList<>();
    if (treeNode instanceof COSDictionary tree) {
      readNumberTree(tree, parentTree, keysInOrder, problems, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    for (int i = 1; i < keysInOrder.size(); i++) {
      if (keysInOrder.get(i) <= keysInOrder.get(i - 1)) {
        add(problems, NUMBER_TREE_KEYS_UNSORTED);
      }
    }
    if (structRoot.getDictionaryObject(COSName.PARENT_TREE_NEXT_KEY) instanceof COSNumber next && !keysInOrder.isEmpty()
        && next.intValue() <= Collections.max(keysInOrder)) {
      add(problems, NEXT_KEY_TOO_LOW);
    }

    // StructParents owners: page and form streams, annotations
    Map<Integer, Set<COSBase>> owners = new HashMap<>();
    Map<COSBase, Integer> keyOfStream = new IdentityHashMap<>();
    for (StreamInfo stream : streams) {
      if (stream.structParents() >= 0) {
        owners.computeIfAbsent(stream.structParents(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(stream.owner());
        keyOfStream.put(stream.owner(), stream.structParents());
      }
    }
    for (PDPage page : document.getPages()) {
      if (page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots) {
        for (COSBase a : annots) {
          COSBase annot = a instanceof COSObject o ? o.getObject() : a;
          if (annot instanceof COSDictionary dict && dict.getDictionaryObject(COSName.STRUCT_PARENT) instanceof COSNumber n) {
            owners.computeIfAbsent(n.intValue(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(dict);
          }
        }
      }
    }
    for (Set<COSBase> sharing : owners.values()) {
      if (sharing.size() > 1) {
        add(problems, STRUCT_PARENTS_SHARED);
      }
    }

    // Structure tree walk: who owns which (key, mcid)
    Map<Owned, List<COSDictionary>> owned = new HashMap<>();
    Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    walkKids(structRoot, structRoot, null, pages, keyOfStream, parentTree, owned, visited, problems, 0);

    // entries -> owners
    for (Map.Entry<Integer, COSBase> entry : parentTree.entrySet()) {
      if (!(resolve(entry.getValue()) instanceof COSArray slots)) {
        continue;
      }
      for (int i = 0; i < slots.size(); i++) {
        if (resolve(slots.get(i)) instanceof COSDictionary element) {
          List<COSDictionary> claimants = owned.getOrDefault(new Owned(entry.getKey(), i), List.of());
          if (claimants.stream().noneMatch(c -> c == element)) {
            add(problems, PARENT_TREE_ENTRY_NOT_OWNED);
          }
        }
      }
    }
    // owners -> entries
    for (Map.Entry<Owned, List<COSDictionary>> e : owned.entrySet()) {
      COSBase slot = slot(parentTree, e.getKey());
      for (COSDictionary element : e.getValue()) {
        if (slot != element) {
          add(problems, MCID_WITHOUT_PARENT_TREE_ENTRY);
        }
      }
    }
    // content <-> entries
    for (StreamInfo stream : streams) {
      if (stream.structParents() < 0) {
        if (!stream.mcids().isEmpty()) {
          add(problems, CONTENT_MCID_WITHOUT_STRUCT_PARENTS);
        }
        continue;
      }
      for (int mcid : stream.mcids()) {
        if (!(slot(parentTree, new Owned(stream.structParents(), mcid)) instanceof COSDictionary)) {
          add(problems, CONTENT_MCID_WITHOUT_PARENT_TREE_ENTRY);
        }
      }
      if (resolve(parentTree.get(stream.structParents())) instanceof COSArray slots) {
        for (int i = 0; i < slots.size(); i++) {
          if (resolve(slots.get(i)) instanceof COSDictionary && !stream.mcids().contains(i)) {
            add(problems, PARENT_TREE_ENTRY_WITHOUT_CONTENT);
          }
        }
      }
    }
  }

  private static COSBase slot(Map<Integer, COSBase> parentTree, Owned at) {
    if (resolve(parentTree.get(at.key())) instanceof COSArray slots && at.mcid() >= 0 && at.mcid() < slots.size()) {
      return resolve(slots.get(at.mcid()));
    }
    return null;
  }

  private static void readNumberTree(
      COSDictionary node, Map<Integer, COSBase> out, List<Integer> keysInOrder, Map<String, Integer> problems, int depth, Set<COSBase> seen) {
    if (depth > MAX_NUMBER_TREE_DEPTH || !seen.add(node)) {
      add(problems, STRUCTURE_CYCLE);
      return;
    }
    int firstIndex = keysInOrder.size();
    if (node.getDictionaryObject(COSName.NUMS) instanceof COSArray nums) {
      for (int i = 0; i + 1 < nums.size(); i += 2) {
        COSBase rawKey = nums.get(i);
        if (!(rawKey instanceof COSInteger)) {
          add(problems, NUMBER_TREE_KEY_NOT_INTEGER);
        }
        if (resolve(rawKey) instanceof COSNumber key) {
          keysInOrder.add(key.intValue());
          out.put(key.intValue(), nums.get(i + 1));
        }
      }
    }
    if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
      for (COSBase kid : kids) {
        if (resolve(kid) instanceof COSDictionary child) {
          readNumberTree(child, out, keysInOrder, problems, depth + 1, seen);
        }
      }
    }
    if (depth > 0 && node.getDictionaryObject(COSName.LIMITS) instanceof COSArray limits && keysInOrder.size() > firstIndex) {
      List<Integer> mine = keysInOrder.subList(firstIndex, keysInOrder.size());
      boolean right = limits.size() == 2
          && limits.getObject(0) instanceof COSNumber lo && lo.intValue() == Collections.min(mine)
          && limits.getObject(1) instanceof COSNumber hi && hi.intValue() == Collections.max(mine);
      if (!right) {
        add(problems, NUMBER_TREE_LIMITS);
      }
    }
  }

  private static void walkKids(
      COSDictionary parent, COSDictionary structRoot, COSDictionary inheritedPage, Set<COSBase> pages, Map<COSBase, Integer> keyOfStream,
      Map<Integer, COSBase> parentTree, Map<Owned, List<COSDictionary>> owned, Set<COSBase> visited, Map<String, Integer> problems, int depth) {
    COSBase k = parent.getDictionaryObject(COSName.K);
    List<COSBase> kids = new ArrayList<>();
    if (k instanceof COSArray array) {
      for (COSBase kid : array) {
        kids.add(kid);
      }
    } else if (k != null) {
      kids.add(parent.getItem(COSName.K));
    }
    COSDictionary page = inheritedPage;
    if (parent != structRoot) {
      COSBase pg = parent.getDictionaryObject(COSName.PG);
      if (pg != null) {
        if (pages.contains(pg)) {
          page = (COSDictionary) pg;
        } else {
          add(problems, DANGLING_PAGE);
          page = null;
        }
      }
    }
    for (COSBase rawKid : kids) {
      COSBase kid = resolve(rawKid);
      if (parent == structRoot) {
        if (kid instanceof COSDictionary element) {
          element(element, parent, structRoot, null, pages, keyOfStream, parentTree, owned, visited, problems, depth);
        }
        continue;
      }
      if (kid instanceof COSNumber mcid) {
        claim(page, -1, mcid.intValue(), parent, keyOfStream, owned, problems);
      } else if (kid instanceof COSDictionary dict) {
        COSName type = dict.getCOSName(COSName.TYPE);
        if (COSName.MCR.equals(type)) {
          COSDictionary mcrPage = page;
          COSBase pg = dict.getDictionaryObject(COSName.PG);
          if (pg != null) {
            if (pages.contains(pg)) {
              mcrPage = (COSDictionary) pg;
            } else {
              add(problems, DANGLING_PAGE);
              continue;
            }
          }
          COSBase stm = dict.getDictionaryObject(STM);
          int streamKey = -1;
          if (stm != null) {
            if (!(stm instanceof COSStream)) {
              add(problems, DANGLING_STREAM);
              continue;
            }
            streamKey = keyOfStream.getOrDefault(stm, ((COSStream) stm).getInt(COSName.STRUCT_PARENTS));
            if (streamKey < 0) {
              add(problems, MCID_WITHOUT_PARENT_TREE_ENTRY);
              continue;
            }
          }
          if (dict.getDictionaryObject(COSName.MCID) instanceof COSNumber mcid) {
            claim(mcrPage, streamKey, mcid.intValue(), parent, keyOfStream, owned, problems);
          }
        } else if (OBJR.equals(type)) {
          COSBase obj = dict.getDictionaryObject(OBJ);
          if (!(obj instanceof COSDictionary target)) {
            add(problems, DANGLING_OBJECT);
            continue;
          }
          COSBase pg = dict.getDictionaryObject(COSName.PG);
          if (pg != null && !pages.contains(pg)) {
            add(problems, DANGLING_PAGE);
          }
          int key = target.getDictionaryObject(COSName.STRUCT_PARENT) instanceof COSNumber n ? n.intValue() : -1;
          if (key < 0 || resolve(parentTree.get(key)) != parent) {
            add(problems, OBJR_WITHOUT_PARENT_TREE_ENTRY);
          }
        } else {
          element(dict, parent, structRoot, page, pages, keyOfStream, parentTree, owned, visited, problems, depth);
        }
      }
    }
  }

  private static void element(
      COSDictionary element, COSDictionary parent, COSDictionary structRoot, COSDictionary page, Set<COSBase> pages, Map<COSBase, Integer> keyOfStream,
      Map<Integer, COSBase> parentTree, Map<Owned, List<COSDictionary>> owned, Set<COSBase> visited, Map<String, Integer> problems, int depth) {
    if (!visited.add(element) || depth > 512) {
      add(problems, STRUCTURE_CYCLE);
      return;
    }
    if (element.getDictionaryObject(COSName.P) != parent) {
      add(problems, WRONG_PARENT_LINK);
    }
    walkKids(element, structRoot, page, pages, keyOfStream, parentTree, owned, visited, problems, depth + 1);
  }

  /** {@code streamKey} -1: the MCID is in the page's own content stream. */
  private static void claim(
      COSDictionary page, int streamKey, int mcid, COSDictionary element, Map<COSBase, Integer> keyOfStream, Map<Owned, List<COSDictionary>> owned,
      Map<String, Integer> problems) {
    int key = streamKey;
    if (key < 0) {
      if (page == null) {
        add(problems, MCID_WITHOUT_PAGE);
        return;
      }
      key = keyOfStream.getOrDefault(page, page.getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber n ? n.intValue() : -1);
      if (key < 0) {
        add(problems, MCID_WITHOUT_PARENT_TREE_ENTRY);
        return;
      }
    }
    owned.computeIfAbsent(new Owned(key, mcid), x -> new ArrayList<>()).add(element);
  }

  private static COSBase resolve(COSBase base) {
    return base instanceof COSObject o ? o.getObject() : base;
  }
}
