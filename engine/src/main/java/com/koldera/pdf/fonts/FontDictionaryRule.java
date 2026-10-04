package com.koldera.pdf.fonts;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

/**
 * {@code PDF_FONT_DICTIONARY_V1} (mode {@code fix}, uploads): in every reachable font dictionary an embedded CIDFont loses
 * {@code /CIDSet}, an embedded Type 1 font loses {@code /CharSet}, and a CIDFontType2 without {@code /CIDToGIDMap} gets
 * {@code /Identity} (the implicit value). Both optional keys are removed rather than recomputed because veraPDF fails
 * incomplete ones; PDF/A-1 documents keep them. Rendering and text are unchanged by construction.
 */
public final class FontDictionaryRule implements RemediationRule {

  static final String NOTHING = "no font has a CIDSet, a CharSet or a missing CIDToGIDMap";
  static final String KEPT = "CIDSet and CharSet kept: the document declares PDF/A-1, which requires them";

  private static final COSName CID_SET = COSName.getPDFName("CIDSet");
  private static final COSName CHAR_SET = COSName.getPDFName("CharSet");
  private static final COSName CID_FONT_TYPE0 = COSName.getPDFName("CIDFontType0");
  private static final COSName CID_FONT_TYPE2 = COSName.getPDFName("CIDFontType2");
  private static final COSName MM_TYPE1 = COSName.getPDFName("MMType1");
  private static final List<COSName> APPEARANCES = List.of(COSName.N, COSName.R, COSName.D);

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_FONT_DICTIONARY_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("font dictionaries are only repaired in fix for uploaded PDFs", null, null);
    }
    return RuleDecision.apply("the font dictionaries are checked when this rule applies", null, null);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    try {
      return run(document);
    } catch (RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not repair the font dictionaries (" + e.getClass().getSimpleName() + ")", null, null);
    }
  }

  private WorkerResult.Action run(PDDocument document) {
    boolean pdfA1 = PdfAConformance.declaredPart(document).orElse(0) == 1;
    // Decide everything first, then change: nothing is half-applied when reading a dictionary fails.
    List<COSDictionary> cidSets = new ArrayList<>();
    List<COSDictionary> charSets = new ArrayList<>();
    List<COSDictionary> cidToGid = new ArrayList<>();
    for (COSDictionary font : fonts(document)) {
      COSName subtype = font.getCOSName(COSName.SUBTYPE);
      COSDictionary descriptor = font.getCOSDictionary(COSName.FONT_DESC);
      if (descriptor == null || !embedded(descriptor)) {
        continue;
      }
      boolean cidFont = CID_FONT_TYPE0.equals(subtype) || CID_FONT_TYPE2.equals(subtype);
      boolean type1 = COSName.TYPE1.equals(subtype) || MM_TYPE1.equals(subtype);
      if (cidFont && descriptor.containsKey(CID_SET)) {
        cidSets.add(descriptor);
      }
      if (type1 && descriptor.containsKey(CHAR_SET)) {
        charSets.add(descriptor);
      }
      if (CID_FONT_TYPE2.equals(subtype) && font.getDictionaryObject(COSName.CID_TO_GID_MAP) == null) {
        cidToGid.add(font);
      }
    }
    boolean kept = pdfA1 && !(cidSets.isEmpty() && charSets.isEmpty());
    int removedCidSets = 0;
    int removedCharSets = 0;
    if (!pdfA1) {
      // A descriptor shared by two fonts is counted once: the second finds nothing left to remove.
      for (COSDictionary descriptor : cidSets) {
        if (descriptor.containsKey(CID_SET)) {
          descriptor.removeItem(CID_SET);
          removedCidSets++;
        }
      }
      for (COSDictionary descriptor : charSets) {
        if (descriptor.containsKey(CHAR_SET)) {
          descriptor.removeItem(CHAR_SET);
          removedCharSets++;
        }
      }
    }
    for (COSDictionary font : cidToGid) {
      font.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
    }
    int total = removedCidSets + removedCharSets + cidToGid.size();
    if (total == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, kept ? KEPT : NOTHING, null, null);
    }
    String reason = "font dictionaries repaired: CIDSet removed from " + removedCidSets + " CID fonts, CharSet removed from " + removedCharSets
        + " Type 1 fonts, CIDToGIDMap /Identity written for " + cidToGid.size() + " CIDFontType2 fonts (the value a missing entry means)"
        + (kept ? "; " + KEPT : "");
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, null, total + (total == 1 ? " font dictionary repaired" : " font dictionaries repaired"));
  }

  private static boolean embedded(COSDictionary descriptor) {
    return descriptor.getDictionaryObject(COSName.FONT_FILE) instanceof COSStream
        || descriptor.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream
        || descriptor.getDictionaryObject(COSName.FONT_FILE3) instanceof COSStream;
  }

  /** Every font dictionary reachable from resources, each once, Type 0 fonts followed by their descendants, in document order. */
  static List<COSDictionary> fonts(PDDocument document) {
    Walk walk = new Walk();
    for (PDPage page : document.getPages()) {
      if (PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.RESOURCES) instanceof COSDictionary resources) {
        walk.resources(resources);
      }
      if (page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annotations) {
        for (int i = 0; i < annotations.size(); i++) {
          if (annotations.getObject(i) instanceof COSDictionary a && a.getDictionaryObject(COSName.AP) instanceof COSDictionary ap) {
            for (COSName kind : APPEARANCES) {
              walk.appearance(ap.getDictionaryObject(kind));
            }
          }
        }
      }
    }
    return walk.fonts;
  }

  private static final class Walk {
    final List<COSDictionary> fonts = new ArrayList<>();
    private final Set<COSDictionary> seenFonts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSDictionary> seenResources = Collections.newSetFromMap(new IdentityHashMap<>());

    /** An appearance entry: a stream, or a dictionary of appearance states whose values are streams. */
    void appearance(COSBase entry) {
      if (entry instanceof COSStream stream) {
        resources(stream.getCOSDictionary(COSName.RESOURCES));
      } else if (entry instanceof COSDictionary states) {
        for (COSName state : states.keySet()) {
          if (states.getDictionaryObject(state) instanceof COSStream stream) {
            resources(stream.getCOSDictionary(COSName.RESOURCES));
          }
        }
      }
    }

    void resources(COSDictionary resources) {
      if (resources == null || !seenResources.add(resources)) {
        return;
      }
      COSDictionary fontDictionary = resources.getCOSDictionary(COSName.FONT);
      if (fontDictionary != null) {
        for (COSName name : fontDictionary.keySet()) {
          if (fontDictionary.getDictionaryObject(name) instanceof COSDictionary font) {
            font(font);
          }
        }
      }
      COSDictionary xObjects = resources.getCOSDictionary(COSName.XOBJECT);
      if (xObjects != null) {
        for (COSName name : xObjects.keySet()) {
          if (xObjects.getDictionaryObject(name) instanceof COSStream form && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))) {
            resources(form.getCOSDictionary(COSName.RESOURCES));
          }
        }
      }
    }

    private void font(COSDictionary font) {
      if (!seenFonts.add(font)) {
        return;
      }
      fonts.add(font);
      if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE)) && font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray descendants) {
        for (int i = 0; i < descendants.size(); i++) {
          if (descendants.getObject(i) instanceof COSDictionary descendant) {
            font(descendant);
          }
        }
      }
    }
  }
}
