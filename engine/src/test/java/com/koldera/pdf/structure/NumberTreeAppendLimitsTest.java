package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.junit.jupiter.api.Test;

/** Fail closed on a node without /Limits; one validation for many appends. */
class NumberTreeAppendLimitsTest {

  private static COSArray nums(int... keys) {
    COSArray array = new COSArray();
    for (int key : keys) {
      array.add(COSInteger.get(key));
      array.add(new COSString("v" + key));
    }
    return array;
  }

  private static COSDictionary treeWithLeafLimits(COSArray leafLimits) {
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, nums(0, 3, 9));
    if (leafLimits != null) {
      leaf.setItem(COSName.LIMITS, leafLimits);
    }
    COSDictionary root = new COSDictionary();
    COSArray kids = new COSArray();
    kids.add(leaf);
    root.setItem(COSName.KIDS, kids);
    return root;
  }

  @Test
  void aNonRootNodeWithoutTwoElementLimitsIsRefusedAndNothingChanges() {
    COSDictionary noLimits = treeWithLeafLimits(null);
    assertFalse(NumberTreeAppend.appendable(noLimits), "a leaf's /Limits must describe its keys; [key key] would hide 0..9");
    assertFalse(NumberTreeAppend.append(noLimits, 10, new COSString("x")));
    assertEquals(List.of(0, 3, 9), List.copyOf(NumberTreeView.read(noLimits).keySet()));
    assertNull(NumberTreeAppend.open(noLimits));

    COSArray one = new COSArray();
    one.add(COSInteger.get(0));
    assertFalse(NumberTreeAppend.appendable(treeWithLeafLimits(one)));
    COSArray notNumbers = new COSArray();
    notNumbers.add(new COSString("a"));
    notNumbers.add(new COSString("b"));
    assertFalse(NumberTreeAppend.appendable(treeWithLeafLimits(notNumbers)));
  }

  @Test
  void oneOpenedAppenderAddsManyKeysQuicklyAndKeepsLimitsRight() {
    COSArray limits = new COSArray();
    limits.add(COSInteger.get(0));
    limits.add(COSInteger.get(9));
    COSDictionary root = treeWithLeafLimits(limits);
    NumberTreeAppend.Appender appender = NumberTreeAppend.open(root);
    assertNotNull(appender);
    assertEquals(10, appender.nextKey());
    long start = System.nanoTime();
    for (int i = 0; i < 20_000; i++) {
      assertTrue(appender.append(appender.nextKey(), new COSString("n" + i)));
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertTrue(ms < 2_000, "20,000 appends took " + ms + " ms");
    assertEquals(20_010, appender.nextKey());
    assertEquals(20_013 - 10, NumberTreeView.read(root).size());
    assertEquals(20_009, limits.getInt(1));
    assertFalse(appender.append(5, new COSString("early")), "keys only grow");
  }
}
