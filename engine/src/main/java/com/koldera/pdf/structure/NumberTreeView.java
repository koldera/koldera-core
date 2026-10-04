package com.koldera.pdf.structure;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;

/**
 * Flat, read-only view of a PDF number tree (Nums / Kids): key -> raw value. Each node is read once
 * (by identity), so a /Kids array that lists a node twice or its own node cannot fan out.
 */
public final class NumberTreeView {

  private NumberTreeView() {}

  public static Map<Integer, COSBase> read(COSBase node) {
    Map<Integer, COSBase> out = new TreeMap<>();
    collect(node, out, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    return out;
  }

  private static void collect(COSBase node, Map<Integer, COSBase> out, int depth, Set<COSDictionary> visited) {
    COSBase resolved = node instanceof COSObject ref ? ref.getObject() : node;
    if (!(resolved instanceof COSDictionary dict) || depth > 32 || !visited.add(dict)) {
      return;
    }
    COSBase nums = dict.getDictionaryObject(COSName.NUMS);
    if (nums instanceof COSArray array) {
      for (int i = 0; i + 1 < array.size(); i += 2) {
        COSBase key = array.getObject(i);
        if (key instanceof COSNumber n) {
          out.put(n.intValue(), array.get(i + 1));
        }
      }
    }
    COSBase kids = dict.getDictionaryObject(COSName.KIDS);
    if (kids instanceof COSArray array) {
      for (COSBase kid : array) {
        collect(kid, out, depth + 1, visited);
      }
    }
  }
}
