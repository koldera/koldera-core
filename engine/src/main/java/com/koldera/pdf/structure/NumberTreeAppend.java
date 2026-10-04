package com.koldera.pdf.structure;

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

/**
 * Appends keys to a PDF number tree (a /ParentTree): each key must exceed every existing key and goes to the end of the
 * right-most leaf, widening /Limits on the way. The tree is checked once ({@link #open}); anything it cannot follow exactly
 * (odd kids, missing /Nums, unordered keys, depth over 32, repeated kids or cycles) is refused and nothing changes.
 */
public final class NumberTreeAppend {

  private static final int MAX_DEPTH = 32;

  private NumberTreeAppend() {}

  /** Appends to one checked tree; keys only grow. Each append is constant work. */
  public static final class Appender {
    private final COSDictionary root;
    private final List<COSDictionary> path;
    private COSArray nums;
    private int next;

    private Appender(COSDictionary root, List<COSDictionary> path, int next) {
      this.root = root;
      this.path = path;
      this.next = next;
      COSDictionary leaf = path.get(path.size() - 1);
      this.nums = leaf.getDictionaryObject(COSName.NUMS) instanceof COSArray existing ? existing : null;
    }

    /** The smallest key larger than every key in the tree. */
    public int nextKey() {
      return next;
    }

    public boolean append(int key, COSBase value) {
      if (key < next) {
        return false;
      }
      if (nums == null) {
        nums = new COSArray();
        path.get(path.size() - 1).setItem(COSName.NUMS, nums);
      }
      nums.add(COSInteger.get(key));
      nums.add(value);
      for (COSDictionary node : path) {
        if (node.getDictionaryObject(COSName.LIMITS) instanceof COSArray limits) {
          limits.set(1, COSInteger.get(key));
        } else if (node != root) {
          throw new IllegalStateException("checked node lost its /Limits");
        }
      }
      next = key + 1;
      return true;
    }
  }

  /** A checked appender for this tree, or null when the tree cannot be followed exactly. */
  public static Appender open(COSDictionary root) {
    List<COSDictionary> path = path(root);
    return path == null ? null : new Appender(root, path, nextFreeKey(root));
  }

  /** The smallest key larger than every key in the tree (0 for an empty tree). */
  public static int nextFreeKey(COSDictionary root) {
    Map<Integer, COSBase> keys = NumberTreeView.read(root);
    return keys.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
  }

  /** Whether {@link #append} can follow this tree to its right-most leaf. */
  public static boolean appendable(COSDictionary root) {
    return path(root) != null;
  }

  /** One append; for many, {@link #open} the tree once. */
  public static boolean append(COSDictionary root, int key, COSBase value) {
    Appender appender = open(root);
    return appender != null && appender.append(key, value);
  }

  /** Root, intermediate nodes and the right-most leaf; null when the tree cannot be followed. */
  private static List<COSDictionary> path(COSDictionary root) {
    if (!eachNodeOnce(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()))) {
      return null;
    }
    List<COSDictionary> path = new ArrayList<>();
    COSDictionary node = root;
    for (int depth = 0; depth <= MAX_DEPTH; depth++) {
      if (node.containsKey(COSName.LIMITS) || node != root) {
        if (!twoNumbers(node.getDictionaryObject(COSName.LIMITS))) {
          return null;
        }
      }
      path.add(node);
      COSBase kids = node.getDictionaryObject(COSName.KIDS);
      if (kids == null) {
        COSBase nums = node.getDictionaryObject(COSName.NUMS);
        if (nums == null) {
          return node == root ? path : null;
        }
        return nums instanceof COSArray array && array.size() % 2 == 0 && keysAscending(array) ? path : null;
      }
      if (!(kids instanceof COSArray array) || array.size() == 0 || node.containsKey(COSName.NUMS)) {
        return null;
      }
      COSBase last = array.get(array.size() - 1);
      COSBase resolved = last instanceof COSObject ref ? ref.getObject() : last;
      if (!(resolved instanceof COSDictionary nextNode)) {
        return null;
      }
      node = nextNode;
    }
    return null;
  }

  /** False when a node is reached twice (a repeated kid or a cycle) or the tree is deeper than {@link #MAX_DEPTH}. */
  private static boolean eachNodeOnce(COSDictionary node, int depth, Set<COSDictionary> visited) {
    if (depth > MAX_DEPTH || !visited.add(node)) {
      return false;
    }
    if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
      for (COSBase kid : kids) {
        COSBase resolved = kid instanceof COSObject ref ? ref.getObject() : kid;
        if (resolved instanceof COSDictionary child && !eachNodeOnce(child, depth + 1, visited)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean twoNumbers(COSBase limits) {
    return limits instanceof COSArray array && array.size() == 2 && array.getObject(0) instanceof COSNumber && array.getObject(1) instanceof COSNumber;
  }

  private static boolean keysAscending(COSArray nums) {
    int previous = Integer.MIN_VALUE;
    for (int i = 0; i < nums.size(); i += 2) {
      if (!(nums.getObject(i) instanceof COSNumber n) || n.intValue() <= previous) {
        return false;
      }
      previous = n.intValue();
    }
    return true;
  }
}
