package com.koldera.pdf.generated;

import com.koldera.pdf.structure.NumberTreeView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Small structure-tree editing helpers for PDFs our renderer generated. The one editing operation,
 * {@link #wrap}, moves existing kids under a new element: marked-content references and object
 * references are kept as they are (only their holder changes), and the /ParentTree entries of the
 * moved MCIDs and OBJRs are pointed at the new holder so the tree stays consistent.
 */
public final class StructTree {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final COSName ID_TREE = COSName.getPDFName("IDTree");

  private StructTree() {}

  /** Kids of an element as PDFBox models them: elements, Integer MCIDs, MCR and OBJR objects. */
  public static List<Object> kids(PDStructureElement element) {
    return element.getKids();
  }

  /** True when the element has kids and every kid is a structure element of this type. */
  public static boolean allChildrenOfType(PDStructureElement element, String type) {
    List<Object> kids = kids(element);
    return !kids.isEmpty() && kids.stream().allMatch(k -> k instanceof PDStructureElement e && type.equals(e.getStructureType()));
  }

  /** Every structure element of this type, in pre-order; an element listed under several parents (or twice) is visited once. */
  public static List<PDStructureElement> elementsOfType(PDDocument doc, String type) {
    List<PDStructureElement> out = new ArrayList<>();
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root != null) {
      collect(root, type, out, 0, visitedSet());
    }
    return out;
  }

  /** An identity set for walks that must visit each shared COS object once (a hostile /K can list one element 2^n times). */
  static Set<COSDictionary> visitedSet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }

  private static void collect(PDStructureNode node, String type, List<PDStructureElement> out, int depth, Set<COSDictionary> visited) {
    if (depth > 256) {
      return;
    }
    for (Object kid : node.getKids()) {
      if (kid instanceof PDStructureElement el && visited.add(el.getCOSObject())) {
        if (type.equals(el.getStructureType())) {
          out.add(el);
        }
        collect(el, type, out, depth + 1, visited);
      }
    }
  }

  /**
   * Moves {@code kids} (all of them kids of {@code parent}, given in their /K order) into a new
   * element of {@code type}, which takes the place of the first moved kid in the parent's /K. The
   * new element gets the parent's /Pg, so integer MCIDs it receives keep their page.
   */
  public static PDStructureElement wrap(PDStructureElement parent, List<Object> kids, String type) {
    if (kids.isEmpty()) {
      throw new IllegalArgumentException("nothing to wrap");
    }
    COSDictionary parentDict = parent.getCOSObject();
    List<COSBase> entries = entries(parentDict);
    PDStructureElement wrapper = new PDStructureElement(type, parent);
    COSDictionary wrapperDict = wrapper.getCOSObject();
    COSBase page = parentDict.getItem(COSName.PG);
    if (page != null) {
      wrapperDict.setItem(COSName.PG, page);
    }

    Map<COSBase, Boolean> moving = new IdentityHashMap<>();
    List<Integer> movingMcids = new ArrayList<>();
    for (Object kid : kids) {
      if (kid instanceof Integer mcid) {
        movingMcids.add(mcid);
      } else if (kid instanceof COSObjectable objectable) {
        moving.put(objectable.getCOSObject(), Boolean.TRUE);
      } else {
        throw new IllegalArgumentException("unsupported kid " + kid);
      }
    }

    COSArray kept = new COSArray();
    COSArray moved = new COSArray();
    int insertAt = -1;
    for (COSBase entry : entries) {
      COSBase resolved = resolve(entry);
      boolean move = resolved instanceof COSInteger n ? movingMcids.remove(Integer.valueOf(n.intValue())) : moving.remove(resolved) != null;
      if (move) {
        if (insertAt < 0) {
          insertAt = kept.size();
          kept.add(wrapperDict);
        }
        moved.add(entry);
      } else {
        kept.add(entry);
      }
    }
    if (!movingMcids.isEmpty() || !moving.isEmpty()) {
      throw new IllegalArgumentException("wrap: not every kid belongs to the parent");
    }
    wrapperDict.setItem(COSName.K, moved.size() == 1 ? moved.get(0) : moved);
    parentDict.setItem(COSName.K, kept);

    COSDictionary parentTree = parentTree(parentDict);
    for (COSBase entry : moved) {
      COSBase resolved = resolve(entry);
      if (resolved instanceof COSInteger mcid) {
        repointMcid(parentTree, page, mcid.intValue(), wrapperDict);
      } else if (resolved instanceof COSDictionary dict && MCR.equals(dict.getCOSName(COSName.TYPE))) {
        COSBase stm = dict.getDictionaryObject(STM);
        COSBase mcrPage = dict.containsKey(COSName.PG) ? dict.getItem(COSName.PG) : page;
        repointMcid(parentTree, stm != null ? stm : mcrPage, dict.getInt(COSName.MCID), wrapperDict);
      } else if (resolved instanceof COSDictionary dict && OBJR.equals(dict.getCOSName(COSName.TYPE))) {
        COSBase obj = dict.getDictionaryObject(COSName.OBJ);
        if (obj instanceof COSDictionary target && target.containsKey(COSName.STRUCT_PARENT)) {
          setParentTreeValue(parentTree, target.getInt(COSName.STRUCT_PARENT), wrapperDict);
        }
      } else if (resolved instanceof COSDictionary element) {
        element.setItem(COSName.P, wrapperDict);
      }
    }
    return wrapper;
  }

  /**
   * The inverse of {@link #wrap}: replaces {@code child} in {@code parent}'s /K by the child's own
   * kids (in order), repoints their /ParentTree entries and /P links at {@code parent}, and drops
   * the child. Every /ParentTree entry to repoint is resolved first; if one is missing, or integer
   * MCIDs would change page, nothing is changed and false is returned.
   */
  public static boolean unwrap(PDStructureElement parent, PDStructureElement child) {
    COSDictionary parentDict = parent.getCOSObject();
    COSDictionary childDict = child.getCOSObject();
    List<COSBase> parentEntries = entries(parentDict);
    int at = -1;
    for (int i = 0; i < parentEntries.size(); i++) {
      if (resolve(parentEntries.get(i)) == childDict) {
        if (at >= 0) {
          return false;
        }
        at = i;
      }
    }
    COSDictionary parentTree = parentTree(parentDict);
    if (at < 0 || parentTree == null) {
      return false;
    }
    COSBase childPage = childDict.getItem(COSName.PG);
    COSBase parentPage = parentDict.getItem(COSName.PG);
    List<COSBase> moved = entries(childDict);
    Map<Integer, COSBase> numbers = NumberTreeView.read(parentTree);

    List<Runnable> repoints = new ArrayList<>();
    boolean integerMcids = false;
    for (COSBase entry : moved) {
      COSBase resolved = resolve(entry);
      if (resolved instanceof COSInteger mcid) {
        integerMcids = true;
        COSArray array = mcidArray(numbers, childPage, mcid.intValue(), childDict);
        if (array == null) {
          return false;
        }
        repoints.add(() -> array.set(mcid.intValue(), parentDict));
      } else if (resolved instanceof COSDictionary dict && MCR.equals(dict.getCOSName(COSName.TYPE))) {
        COSBase stm = dict.getDictionaryObject(STM);
        COSBase owner = stm != null ? stm : dict.containsKey(COSName.PG) ? dict.getItem(COSName.PG) : childPage;
        int mcid = dict.getInt(COSName.MCID);
        COSArray array = mcidArray(numbers, owner, mcid, childDict);
        if (array == null) {
          return false;
        }
        if (stm == null && !dict.containsKey(COSName.PG) && childPage != null) {
          // The MCR relied on the child's /Pg; it keeps its page explicitly once the child is gone.
          repoints.add(() -> dict.setItem(COSName.PG, childPage));
        }
        repoints.add(() -> array.set(mcid, parentDict));
      } else if (resolved instanceof COSDictionary dict && OBJR.equals(dict.getCOSName(COSName.TYPE))) {
        if (!(dict.getDictionaryObject(COSName.OBJ) instanceof COSDictionary target) || !target.containsKey(COSName.STRUCT_PARENT)
            || resolve(numbers.get(target.getInt(COSName.STRUCT_PARENT))) != childDict) {
          return false;
        }
        repoints.add(() -> setParentTreeValue(parentTree, target.getInt(COSName.STRUCT_PARENT), parentDict));
      } else if (resolved instanceof COSDictionary element) {
        repoints.add(() -> element.setItem(COSName.P, parentDict));
      } else {
        return false;
      }
    }
    if (integerMcids) {
      if (childPage == null || (parentPage != null && resolve(parentPage) != resolve(childPage))) {
        return false;
      }
    }

    repoints.forEach(Runnable::run);
    if (integerMcids && parentPage == null) {
      parentDict.setItem(COSName.PG, childPage);
    }
    COSArray kids = new COSArray();
    for (int i = 0; i < parentEntries.size(); i++) {
      if (i == at) {
        moved.forEach(kids::add);
      } else {
        kids.add(parentEntries.get(i));
      }
    }
    parentDict.setItem(COSName.K, kids);
    childDict.removeItem(COSName.K);
    childDict.removeItem(COSName.P);
    return true;
  }

  /**
   * Removes {@code child} (with everything below it) from {@code parent}'s /K and nulls the /ParentTree slots of its MCIDs;
   * the content itself is untouched (the caller turns it into artifacts). Returns false and changes nothing unless
   * {@code child} appears exactly once in /K and nothing below it is an object reference.
   */
  public static boolean remove(PDStructureNode parent, PDStructureElement child) {
    COSDictionary parentDict = parent.getCOSObject();
    COSDictionary childDict = child.getCOSObject();
    List<COSBase> parentEntries = entries(parentDict);
    int at = -1;
    for (int i = 0; i < parentEntries.size(); i++) {
      if (resolve(parentEntries.get(i)) == childDict) {
        if (at >= 0) {
          return false;
        }
        at = i;
      }
    }
    if (at < 0) {
      return false;
    }
    COSBase page = null;
    COSDictionary up = childDict;
    for (int i = 0; up != null && i < 512 && page == null; i++) {
      page = up.getItem(COSName.PG);
      up = up.getDictionaryObject(COSName.P) instanceof COSDictionary p ? p : null;
    }
    Set<COSDictionary> subtree = visitedSet();
    List<COSBase[]> slots = new ArrayList<>(); // {owner, mcid}
    if (!collectSubtree(childDict, page, subtree, slots, 0)) {
      return false;
    }
    COSDictionary root = structRoot(childDict);
    COSDictionary parentTree = root != null && root.getDictionaryObject(COSName.PARENT_TREE) instanceof COSDictionary tree ? tree : null;
    if (root != null && root.getDictionaryObject(ID_TREE) instanceof COSDictionary idTree) {
      for (COSDictionary element : subtree) {
        if (element.getDictionaryObject(COSName.ID) instanceof COSString id) {
          removeName(idTree, id, subtree, 0, visitedSet());
        }
      }
    }
    if (parentTree != null) {
      Map<Integer, COSBase> numbers = NumberTreeView.read(parentTree);
      for (COSBase[] slot : slots) {
        if (resolve(slot[0]) instanceof COSDictionary owner && owner.containsKey(COSName.STRUCT_PARENTS)
            && resolve(numbers.get(owner.getInt(COSName.STRUCT_PARENTS))) instanceof COSArray array) {
          int mcid = ((COSInteger) slot[1]).intValue();
          if (mcid >= 0 && mcid < array.size() && resolve(array.get(mcid)) instanceof COSDictionary holder && subtree.contains(holder)) {
            array.set(mcid, org.apache.pdfbox.cos.COSNull.NULL);
          }
        }
      }
    }
    COSArray kept = new COSArray();
    for (int i = 0; i < parentEntries.size(); i++) {
      if (i != at) {
        kept.add(parentEntries.get(i));
      }
    }
    parentDict.setItem(COSName.K, kept);
    childDict.removeItem(COSName.P);
    return true;
  }

  /** Removes the pair {@code id → one of elements} from a name tree's leaf /Names (the /Limits are left as they are). */
  private static void removeName(COSDictionary node, COSString id, Set<COSDictionary> elements, int depth, Set<COSDictionary> visited) {
    if (depth > 32 || !visited.add(node)) {
      return;
    }
    if (node.getDictionaryObject(COSName.NAMES) instanceof COSArray names) {
      for (int i = names.size() - 2; i >= 0; i -= 2) {
        if (names.getObject(i) instanceof COSString key && key.getString().equals(id.getString())
            && resolve(names.get(i + 1)) instanceof COSDictionary value && elements.contains(value)) {
          names.remove(i + 1);
          names.remove(i);
        }
      }
    }
    if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
      for (COSBase kid : kids) {
        if (resolve(kid) instanceof COSDictionary child) {
          removeName(child, id, elements, depth + 1, visited);
        }
      }
    }
  }

  /** Types an emptied element keeps even without kids: table cells and rows keep the table's geometry. */
  private static final Set<String> KEPT_WHEN_EMPTY = Set.of("TD", "TH", "TR");

  /**
   * After a removal: walks up from {@code start} removing each element whose /K is empty, unless it is a {@code TD}/{@code TH}/
   * {@code TR}, has an /ID, is a direct kid of the root, or is named by the /ParentTree ({@link #referencedByParentTree});
   * stops at the first that stays. Returns how many elements were removed.
   */
  public static int removeEmptyAncestors(PDStructureNode start) {
    int removed = 0;
    Set<COSDictionary> visited = visitedSet();
    COSDictionary current = start.getCOSObject();
    COSDictionary root = structRoot(current);
    Set<COSDictionary> referenced = root == null ? visitedSet() : referencedByParentTree(root);
    for (int depth = 0; depth < 512 && current != null && visited.add(current); depth++) {
      if (!removableWhenEmpty(current) || referenced.contains(current)) {
        break;
      }
      COSDictionary parent = current.getDictionaryObject(COSName.P) instanceof COSDictionary p ? p : null;
      if (parent == null || isRoot(parent) || !remove(new PDStructureElement(parent), new PDStructureElement(current))) {
        break;
      }
      removed++;
      current = parent;
    }
    return removed;
  }

  /**
   * Every dictionary a /ParentTree value of the structure tree root {@code root} names: the value
   * itself (an annotation's /StructParent entry), or an entry of a page's or form's MCID array. An
   * identity set; removing any of these elements would leave a dangling ParentTree entry.
   */
  public static Set<COSDictionary> referencedByParentTree(COSDictionary root) {
    Set<COSDictionary> out = visitedSet();
    for (COSBase value : NumberTreeView.read(root.getDictionaryObject(COSName.PARENT_TREE)).values()) {
      COSBase resolved = resolve(value);
      if (resolved instanceof COSDictionary dict) {
        out.add(dict);
      } else if (resolved instanceof COSArray array) {
        for (COSBase entry : array) {
          if (resolve(entry) instanceof COSDictionary dict) {
            out.add(dict);
          }
        }
      }
    }
    return out;
  }

  /**
   * The criteria {@link #removeEmptyAncestors} and the upload empty-element cleanup share: a structure
   * element (not the root) with no /K or an empty one, without an /ID, whose type (own or through the
   * /RoleMap) is not {@code TD}, {@code TH} or {@code TR}. Whether it is a direct kid of the root is
   * the caller's check (it depends on the parent it was found under).
   */
  public static boolean removableWhenEmpty(COSDictionary element) {
    return !isRoot(element) && entries(element).isEmpty() && !element.containsKey(COSName.ID) && !keptWhenEmpty(element);
  }

  private static boolean isRoot(COSDictionary node) {
    return COSName.STRUCT_TREE_ROOT.equals(node.getCOSName(COSName.TYPE)) || !node.containsKey(COSName.S);
  }

  private static boolean keptWhenEmpty(COSDictionary element) {
    COSDictionary root = structRoot(element);
    COSDictionary roleMap = root != null && root.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary m ? m : null;
    String type = element.getNameAsString(COSName.S);
    for (int i = 0; i < 16 && type != null; i++) {
      if (KEPT_WHEN_EMPTY.contains(type)) {
        return true;
      }
      type = roleMap == null ? null : roleMap.getDictionaryObject(COSName.getPDFName(type)) instanceof COSName mapped ? mapped.getName() : null;
    }
    return false;
  }

  /** Every element below (and including) {@code element} and its MCIDs as {owner, mcid}; false when an OBJR is found. */
  private static boolean collectSubtree(COSDictionary element, COSBase inheritedPage, Set<COSDictionary> subtree, List<COSBase[]> slots, int depth) {
    if (depth > 256 || !subtree.add(element)) {
      return depth <= 256;
    }
    COSBase page = element.containsKey(COSName.PG) ? element.getItem(COSName.PG) : inheritedPage;
    for (COSBase entry : entries(element)) {
      COSBase resolved = resolve(entry);
      if (resolved instanceof COSInteger mcid) {
        if (page != null) {
          slots.add(new COSBase[] {page, mcid});
        }
      } else if (resolved instanceof COSDictionary dict && OBJR.equals(dict.getCOSName(COSName.TYPE))) {
        return false;
      } else if (resolved instanceof COSDictionary dict && MCR.equals(dict.getCOSName(COSName.TYPE))) {
        COSBase stm = dict.getDictionaryObject(STM);
        COSBase owner = stm != null ? stm : dict.containsKey(COSName.PG) ? dict.getItem(COSName.PG) : page;
        if (owner != null) {
          slots.add(new COSBase[] {owner, COSInteger.get(dict.getInt(COSName.MCID))});
        }
      } else if (resolved instanceof COSDictionary dict) {
        if (!collectSubtree(dict, page, subtree, slots, depth + 1)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Moves the structure element {@code element}, a kid of {@code from}, to position {@code index}
   * of {@code to}'s /K and sets its /P. The element keeps its own kids, so no /ParentTree entry
   * changes. Returns false and changes nothing unless the element appears exactly once in
   * {@code from}'s /K and {@code index} is within {@code to}'s /K.
   */
  public static boolean moveElement(PDStructureElement from, PDStructureElement element, PDStructureElement to, int index) {
    COSDictionary fromDict = from.getCOSObject();
    COSDictionary toDict = to.getCOSObject();
    COSDictionary elementDict = element.getCOSObject();
    if (fromDict == toDict || elementDict == toDict) {
      return false;
    }
    List<COSBase> fromEntries = entries(fromDict);
    COSBase moving = null;
    for (COSBase entry : fromEntries) {
      if (resolve(entry) == elementDict) {
        if (moving != null) {
          return false;
        }
        moving = entry;
      }
    }
    List<COSBase> toEntries = entries(toDict);
    if (moving == null || index < 0 || index > toEntries.size()) {
      return false;
    }
    COSArray kept = new COSArray();
    for (COSBase entry : fromEntries) {
      if (entry != moving) {
        kept.add(entry);
      }
    }
    COSArray target = new COSArray();
    toEntries.forEach(target::add);
    target.add(index, moving);
    fromDict.setItem(COSName.K, kept);
    toDict.setItem(COSName.K, target);
    elementDict.setItem(COSName.P, toDict);
    return true;
  }

  /** The ParentTree array for {@code owner}'s MCIDs if entry {@code mcid} exists and names {@code holder}, else null. */
  private static COSArray mcidArray(Map<Integer, COSBase> numbers, COSBase owner, int mcid, COSDictionary holder) {
    if (!(resolve(owner) instanceof COSDictionary ownerDict) || !ownerDict.containsKey(COSName.STRUCT_PARENTS)) {
      return null;
    }
    COSBase value = resolve(numbers.get(ownerDict.getInt(COSName.STRUCT_PARENTS)));
    if (value instanceof COSArray array && mcid >= 0 && mcid < array.size() && resolve(array.get(mcid)) == holder) {
      return array;
    }
    return null;
  }

  /** The /K entries of a node as a list, whether /K is a single item or an array. Shared by {@link FigureBBox} and {@link LinkContents}. */
  public static List<COSBase> entries(COSDictionary node) {
    COSBase k = node.getItem(COSName.K);
    COSBase resolved = resolve(k);
    List<COSBase> out = new ArrayList<>();
    if (resolved instanceof COSArray array) {
      for (COSBase entry : array) {
        out.add(entry);
      }
    } else if (k != null) {
      out.add(k);
    }
    return out;
  }

  /** The /ParentTree of the structure tree root this element belongs to (followed through /P), or null. */
  private static COSDictionary parentTree(COSDictionary element) {
    COSDictionary root = structRoot(element);
    return root != null && root.getDictionaryObject(COSName.PARENT_TREE) instanceof COSDictionary tree ? tree : null;
  }

  /** The structure tree root this element belongs to (followed through /P, typed /StructTreeRoot), or null. */
  private static COSDictionary structRoot(COSDictionary element) {
    COSDictionary node = element;
    for (int i = 0; node != null && i < 512; i++) {
      if (COSName.STRUCT_TREE_ROOT.equals(node.getCOSName(COSName.TYPE))) {
        return node;
      }
      node = node.getDictionaryObject(COSName.P) instanceof COSDictionary parent ? parent : null;
    }
    return null;
  }

  /** Sets entry {@code mcid} of the ParentTree array for the page (or form stream) {@code owner}. */
  private static void repointMcid(COSDictionary parentTree, COSBase owner, int mcid, COSDictionary holder) {
    if (parentTree == null || !(resolve(owner) instanceof COSDictionary ownerDict) || !ownerDict.containsKey(COSName.STRUCT_PARENTS)) {
      return;
    }
    COSBase value = resolve(NumberTreeView.read(parentTree).get(ownerDict.getInt(COSName.STRUCT_PARENTS)));
    if (value instanceof COSArray array && mcid >= 0 && mcid < array.size()) {
      array.set(mcid, holder);
    }
  }

  /**
   * Replaces the value of {@code key} in the number tree, wherever in /Nums or /Kids it is; false when
   * not found. The walk stops below the same depth as {@link NumberTreeView} (32) and visits each node
   * once, so a cyclic or repeated /Kids fails closed instead of overflowing the stack or fanning out.
   */
  static boolean setParentTreeValue(COSBase node, int key, COSDictionary value) {
    return setParentTreeValue(node, key, value, 0, visitedSet());
  }

  private static boolean setParentTreeValue(COSBase node, int key, COSDictionary value, int depth, Set<COSDictionary> visited) {
    if (!(resolve(node) instanceof COSDictionary dict) || depth > 32 || !visited.add(dict)) {
      return false;
    }
    if (dict.getDictionaryObject(COSName.NUMS) instanceof COSArray nums) {
      for (int i = 0; i + 1 < nums.size(); i += 2) {
        if (nums.getObject(i) instanceof COSNumber n && n.intValue() == key) {
          nums.set(i + 1, value);
          return true;
        }
      }
    }
    if (dict.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
      for (COSBase kid : kids) {
        if (setParentTreeValue(kid, key, value, depth + 1, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Follows an indirect reference to the object it points to; returns {@code base} unchanged otherwise. */
  public static COSBase resolve(COSBase base) {
    return base instanceof COSObject ref ? ref.getObject() : base;
  }
}
