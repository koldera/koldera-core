package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.junit.jupiter.api.Test;

/** Number trees from hostile uploads: a /Kids array listing its own node, or one node twice. */
class NumberTreeFanOutTest {

  private static final Duration BOUND = Duration.ofSeconds(10);

  private static COSDictionary leaf(int... keys) {
    COSArray nums = new COSArray();
    for (int key : keys) {
      nums.add(COSInteger.get(key));
      nums.add(new COSString("v" + key));
    }
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, nums);
    leaf.setItem(COSName.LIMITS, new COSArray(List.of(COSInteger.get(keys[0]), COSInteger.get(keys[keys.length - 1]))));
    return leaf;
  }

  @Test
  void readVisitsASelfListingKidsArrayOnce() {
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.KIDS, new COSArray(List.of(root, root, leaf(1, 4))));

    Map<Integer, COSBase> read = assertTimeoutPreemptively(BOUND, () -> NumberTreeView.read(root));
    assertEquals(List.of(1, 4), List.copyOf(read.keySet()));
  }

  @Test
  void readVisitsASharedKidOnce() {
    COSDictionary bottom = leaf(2);
    COSDictionary next = bottom;
    for (int i = 0; i < 30; i++) { // within the 32-level cap, 2^30 paths
      COSDictionary node = new COSDictionary();
      node.setItem(COSName.KIDS, new COSArray(List.of(next, next)));
      node.setItem(COSName.LIMITS, new COSArray(List.of(COSInteger.get(2), COSInteger.get(2))));
      next = node;
    }
    COSDictionary top = next;
    Map<Integer, COSBase> read = assertTimeoutPreemptively(BOUND, () -> NumberTreeView.read(top));
    assertEquals(List.of(2), List.copyOf(read.keySet()));
  }

  @Test
  void appendRefusesATreeWhoseKidsRepeatOrCycle() {
    COSDictionary selfListing = new COSDictionary();
    COSDictionary last = leaf(1, 4);
    selfListing.setItem(COSName.KIDS, new COSArray(List.of(selfListing, last)));
    assertNull(assertTimeoutPreemptively(BOUND, () -> NumberTreeAppend.open(selfListing)), "a /Kids array listing its own node");
    assertFalse(NumberTreeAppend.appendable(selfListing));
    assertEquals(4, ((COSArray) last.getDictionaryObject(COSName.LIMITS)).getInt(1), "nothing changed");

    COSDictionary repeated = new COSDictionary();
    COSDictionary shared = leaf(0, 2);
    repeated.setItem(COSName.KIDS, new COSArray(List.of(shared, shared)));
    assertFalse(NumberTreeAppend.append(repeated, 3, new COSString("x")), "a kid listed twice");
    assertEquals(List.of(0, 2), List.copyOf(NumberTreeView.read(repeated).keySet()));
  }
}
