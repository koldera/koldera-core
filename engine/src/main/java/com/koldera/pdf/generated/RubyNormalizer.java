package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Chromium tags {@code <ruby>} as {@code Ruby > [NonStruct, RT, NonStruct, RT]}: base text outside an {@code RB} and several
 * pairs in one Ruby, where PDF expects one {@code RB} and one {@code RT} (with {@code RP}s) per Ruby. Each base moves into a
 * new {@code RB}; several pairs become separate Rubies under a {@code Span}; a missing {@code RP} is added empty. Text and
 * order are unchanged; a Ruby that does not read as pairs is left unchanged.
 */
public final class RubyNormalizer {

  private RubyNormalizer() {}

  /** One base/annotation pair: the base kids (or one existing RB) and the RP/RT/RP elements that follow. */
  private record Pair(List<Object> base, List<Object> annotation) {}

  /** Returns the number of Ruby elements changed. */
  public static int normalize(PDDocument doc) {
    int changed = 0;
    for (PDStructureElement ruby : StructTree.elementsOfType(doc, "Ruby")) {
      List<Pair> pairs = pairs(StructTree.kids(ruby));
      if (pairs == null || (pairs.size() == 1 && isType(pairs.get(0).base().get(0), "RB") && parenthesized(pairs.get(0).annotation()))) {
        continue;
      }
      List<List<Object>> groups = new ArrayList<>();
      for (Pair pair : pairs) {
        Object rb = pair.base().size() == 1 && isType(pair.base().get(0), "RB") ? pair.base().get(0) : StructTree.wrap(ruby, pair.base(), "RB");
        List<Object> group = new ArrayList<>();
        group.add(rb);
        group.addAll(pair.annotation());
        groups.add(group);
      }
      if (groups.size() > 1) {
        ruby.setStructureType("Span");
        for (List<Object> group : groups) {
          addParentheses(StructTree.wrap(ruby, group, "Ruby"));
        }
      } else {
        addParentheses(ruby);
      }
      changed++;
    }
    return changed;
  }

  /** True when the annotation is exactly RP, RT, RP. */
  private static boolean parenthesized(List<Object> annotation) {
    return annotation.size() == 3 && isType(annotation.get(0), "RP") && isType(annotation.get(1), "RT") && isType(annotation.get(2), "RP");
  }

  /** Makes a one-pair Ruby read RB, RP, RT, RP by adding an empty RP on each side of the RT that lacks one. */
  private static void addParentheses(PDStructureElement ruby) {
    List<COSBase> entries = StructTree.entries(ruby.getCOSObject());
    COSArray kids = new COSArray();
    for (int i = 0; i < entries.size(); i++) {
      COSBase entry = entries.get(i);
      boolean rt = StructTree.resolve(entry) instanceof org.apache.pdfbox.cos.COSDictionary d && "RT".equals(d.getNameAsString(COSName.S));
      if (rt && (i == 0 || !isRp(entries.get(i - 1)))) {
        kids.add(new PDStructureElement("RP", ruby).getCOSObject());
      }
      kids.add(entry);
      if (rt && (i == entries.size() - 1 || !isRp(entries.get(i + 1)))) {
        kids.add(new PDStructureElement("RP", ruby).getCOSObject());
      }
    }
    ruby.getCOSObject().setItem(COSName.K, kids);
  }

  private static boolean isRp(COSBase entry) {
    return StructTree.resolve(entry) instanceof org.apache.pdfbox.cos.COSDictionary d && "RP".equals(d.getNameAsString(COSName.S));
  }

  /** The kids read as base/annotation pairs, or null when they do not. */
  private static List<Pair> pairs(List<Object> kids) {
    List<Pair> pairs = new ArrayList<>();
    List<Object> base = new ArrayList<>();
    List<Object> annotation = new ArrayList<>();
    for (Object kid : kids) {
      boolean rt = isType(kid, "RT");
      boolean rp = isType(kid, "RP");
      boolean annotating = annotation.stream().anyMatch(k -> isType(k, "RT"));
      if (rt || rp) {
        if (base.isEmpty() || (rt && annotating)) {
          return null;
        }
        annotation.add(kid);
        continue;
      }
      if (!annotation.isEmpty()) {
        if (!annotating) {
          return null; // an RP not followed by its RT
        }
        pairs.add(new Pair(base, annotation));
        base = new ArrayList<>();
        annotation = new ArrayList<>();
      }
      base.add(kid);
    }
    if (!base.isEmpty() && annotation.stream().noneMatch(k -> isType(k, "RT"))) {
      return null;
    }
    if (!base.isEmpty()) {
      pairs.add(new Pair(base, annotation));
    }
    for (Pair pair : pairs) {
      if (pair.base().stream().anyMatch(k -> isType(k, "RB")) && pair.base().size() > 1) {
        return null;
      }
    }
    return pairs.isEmpty() ? null : pairs;
  }

  private static boolean isType(Object kid, String type) {
    return kid instanceof PDStructureElement e && type.equals(e.getStructureType());
  }
}
