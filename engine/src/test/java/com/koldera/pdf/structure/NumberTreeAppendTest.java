package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.junit.jupiter.api.Test;

class NumberTreeAppendTest {

  private static COSArray nums(int... keys) {
    COSArray array = new COSArray();
    for (int key : keys) {
      array.add(COSInteger.get(key));
      array.add(new COSString("v" + key));
    }
    return array;
  }

  private static COSArray limits(int low, int high) {
    COSArray array = new COSArray();
    array.add(COSInteger.get(low));
    array.add(COSInteger.get(high));
    return array;
  }

  @Test
  void aFlatTreeGetsTheKeyAtItsEnd() {
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.NUMS, nums(0, 1, 5));
    assertEquals(6, NumberTreeAppend.nextFreeKey(root));
    assertTrue(NumberTreeAppend.append(root, 6, new COSString("new")));
    Map<Integer, ?> read = NumberTreeView.read(root);
    assertEquals(List.of(0, 1, 5, 6), List.copyOf(read.keySet()));
  }

  @Test
  void aTreeWithKidsGetsTheKeyInItsLastLeafAndEveryLimitOnTheWayIsWidened() {
    COSDictionary leafA = new COSDictionary();
    leafA.setItem(COSName.NUMS, nums(0, 1));
    leafA.setItem(COSName.LIMITS, limits(0, 1));
    COSDictionary leafB = new COSDictionary();
    leafB.setItem(COSName.NUMS, nums(2, 7));
    leafB.setItem(COSName.LIMITS, limits(2, 7));
    COSDictionary middle = new COSDictionary();
    COSArray middleKids = new COSArray();
    middleKids.add(leafB);
    middle.setItem(COSName.KIDS, middleKids);
    middle.setItem(COSName.LIMITS, limits(2, 7));
    COSDictionary root = new COSDictionary();
    COSArray kids = new COSArray();
    kids.add(leafA);
    kids.add(middle);
    root.setItem(COSName.KIDS, kids);

    assertEquals(8, NumberTreeAppend.nextFreeKey(root));
    assertTrue(NumberTreeAppend.append(root, 8, new COSString("new")));
    assertEquals(List.of(0, 1, 2, 7, 8), List.copyOf(NumberTreeView.read(root).keySet()));
    assertEquals(8, ((COSArray) leafB.getDictionaryObject(COSName.LIMITS)).getInt(1));
    assertEquals(8, ((COSArray) middle.getDictionaryObject(COSName.LIMITS)).getInt(1));
    assertEquals(1, ((COSArray) leafA.getDictionaryObject(COSName.LIMITS)).getInt(1), "other leaves untouched");
  }

  @Test
  void refusesAKeyThatWouldNotBeLastOrATreeItCannotFollow() {
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.NUMS, nums(0, 4));
    assertFalse(NumberTreeAppend.append(root, 4, new COSString("dup")));
    assertFalse(NumberTreeAppend.append(root, 2, new COSString("early")));
    assertEquals(List.of(0, 4), List.copyOf(NumberTreeView.read(root).keySet()), "nothing changed");

    COSDictionary broken = new COSDictionary();
    COSArray kids = new COSArray();
    kids.add(new COSString("not a node"));
    broken.setItem(COSName.KIDS, kids);
    assertFalse(NumberTreeAppend.appendable(broken));
    assertFalse(NumberTreeAppend.append(broken, 1, new COSString("x")));

    COSDictionary empty = new COSDictionary();
    assertTrue(NumberTreeAppend.appendable(empty), "an empty tree gets a /Nums array");
    assertTrue(NumberTreeAppend.append(empty, 0, new COSString("first")));
    assertEquals(List.of(0), List.copyOf(NumberTreeView.read(empty).keySet()));
  }
}
