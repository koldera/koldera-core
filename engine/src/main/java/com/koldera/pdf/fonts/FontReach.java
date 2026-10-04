package com.koldera.pdf.fonts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

/**
 * Every font dictionary a page's resources reach (page, form XObject, pattern, Type 3 and annotation appearance resources).
 * A font is <em>restricted</em> when a path to it passes through a pattern, a Type 3 font's resources or a
 * rollover/down appearance: {@link FontUses} does not show what is painted there, so its rendering mode is unknown.
 */
final class FontReach {

  private static final int PLAIN = 0;
  private static final int RESTRICTED = 1;

  private final Map<COSDictionary, TreeSet<Integer>> pages = new IdentityHashMap<>();
  private final java.util.Set<COSDictionary> restricted = Collections.newSetFromMap(new IdentityHashMap<>());
  private final List<COSDictionary> order = new ArrayList<>();

  private FontReach() {}

  static FontReach walk(PDDocument document) {
    FontReach reach = new FontReach();
    int number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      Walk walk = reach.new Walk(number);
      if (PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.RESOURCES) instanceof COSDictionary resources) {
        walk.resources(resources, PLAIN);
      }
      if (page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annotations) {
        for (int i = 0; i < annotations.size(); i++) {
          if (annotations.getObject(i) instanceof COSDictionary a && a.getDictionaryObject(COSName.AP) instanceof COSDictionary ap) {
            walk.appearance(ap.getDictionaryObject(COSName.N), PLAIN);
            walk.appearance(ap.getDictionaryObject(COSName.R), RESTRICTED);
            walk.appearance(ap.getDictionaryObject(COSName.D), RESTRICTED);
          }
        }
      }
    }
    return reach;
  }

  /** Every font dictionary reached (Type 0 fonts, their descendants, simple and Type 3 fonts), in order of first reach. */
  List<COSDictionary> fonts() {
    return Collections.unmodifiableList(order);
  }

  /** 1-based pages whose resources reach {@code font}; empty when none does. */
  SortedSet<Integer> pages(COSDictionary font) {
    TreeSet<Integer> reached = pages.get(font);
    return reached == null ? Collections.emptySortedSet() : Collections.unmodifiableSortedSet(reached);
  }

  /** Some path to {@code font} passes through a pattern, a Type 3 font's resources or a rollover/down appearance. */
  boolean restricted(COSDictionary font) {
    return restricted.contains(font);
  }

  /** The walk of one page; each resources dictionary is entered once per flag value. */
  private final class Walk {
    private final int page;
    private final Map<COSDictionary, Integer> seen = new IdentityHashMap<>();

    Walk(int page) {
      this.page = page;
    }

    void appearance(COSBase entry, int flag) {
      if (entry instanceof COSStream stream) {
        resources(stream.getCOSDictionary(COSName.RESOURCES), flag);
      } else if (entry instanceof COSDictionary states) {
        for (COSName state : states.keySet()) {
          if (states.getDictionaryObject(state) instanceof COSStream stream) {
            resources(stream.getCOSDictionary(COSName.RESOURCES), flag);
          }
        }
      }
    }

    void resources(COSDictionary resources, int flag) {
      if (resources == null) {
        return;
      }
      Integer before = seen.get(resources);
      if (before != null && (before | flag) == before) {
        return;
      }
      seen.put(resources, before == null ? flag : before | flag);
      COSDictionary fonts = resources.getCOSDictionary(COSName.FONT);
      if (fonts != null) {
        for (COSName name : fonts.keySet()) {
          if (fonts.getDictionaryObject(name) instanceof COSDictionary font) {
            font(font, flag);
          }
        }
      }
      COSDictionary xObjects = resources.getCOSDictionary(COSName.XOBJECT);
      if (xObjects != null) {
        for (COSName name : xObjects.keySet()) {
          if (xObjects.getDictionaryObject(name) instanceof COSStream form && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))) {
            resources(form.getCOSDictionary(COSName.RESOURCES), flag);
          }
        }
      }
      COSDictionary patterns = resources.getCOSDictionary(COSName.PATTERN);
      if (patterns != null) {
        for (COSName name : patterns.keySet()) {
          if (patterns.getDictionaryObject(name) instanceof COSStream tiling) {
            resources(tiling.getCOSDictionary(COSName.RESOURCES), RESTRICTED);
          }
        }
      }
    }

    private void font(COSDictionary font, int flag) {
      record(font, flag);
      COSName subtype = font.getCOSName(COSName.SUBTYPE);
      if (COSName.TYPE0.equals(subtype) && font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray descendants) {
        for (int i = 0; i < descendants.size(); i++) {
          if (descendants.getObject(i) instanceof COSDictionary descendant) {
            record(descendant, flag);
          }
        }
      } else if (COSName.TYPE3.equals(subtype)) {
        resources(font.getCOSDictionary(COSName.RESOURCES), RESTRICTED);
      }
    }

    private void record(COSDictionary font, int flag) {
      TreeSet<Integer> reached = pages.get(font);
      if (reached == null) {
        reached = new TreeSet<>();
        pages.put(font, reached);
        order.add(font);
      }
      reached.add(page);
      if (flag == RESTRICTED) {
        restricted.add(font);
      }
    }
  }
}
