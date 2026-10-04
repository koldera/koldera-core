package com.koldera.pdf.inference;

import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Discards a document's tags so structure can be built from content (only on the copy). Each {@code BDC} loses its
 * {@code /MCID}: with nothing else left it and its {@code EMC} are removed, otherwise it keeps its other properties
 * ({@code /ActualText}, {@code /Alt}, {@code /E}, {@code /Lang}); {@code /StructParent(s)} and {@code /StructTreeRoot} are
 * removed. Marker-only, so rendering is unchanged; everything is checked before anything is changed.
 */
public final class TagStripper {

  /** What was removed: whole marked-content sequences, MCIDs (removed or rewritten sequences), struct-parent keys. */
  public record Outcome(int sequencesRemoved, int mcidsRemoved, int structParentsRemoved) {}

  private static final String UNBALANCED = "existing marked content is not balanced; the tags are kept";
  private static final COSName STRUCT_PARENT = COSName.getPDFName("StructParent");
  private static final int MAX_FORM_DEPTH = 32;

  private TagStripper() {}

  /** A stream to rewrite: its owner (page or form stream) and new tokens. */
  private record Rewrite(Object owner, List<Object> tokens) {}

  public static Outcome strip(PDDocument doc) throws IOException, UnsupportedContentException {
    int[] counts = new int[2];
    List<Rewrite> rewrites = new ArrayList<>();
    Set<COSStream> forms = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PDPage page : doc.getPages()) {
      PDResources resources = page.getResources();
      if (page.hasContents()) {
        List<Object> rewritten = rewrite(new PDFStreamParser(page).parse(), resources, counts);
        if (rewritten != null) {
          rewrites.add(new Rewrite(page, rewritten));
        }
      }
      collectForms(resources, forms, rewrites, counts, 0);
    }
    // Everything checked: only now is the document changed.
    for (Rewrite rewrite : rewrites) {
      if (rewrite.owner() instanceof PDPage page) {
        PDStream replacement = new PDStream(doc);
        try (OutputStream out = replacement.createOutputStream(COSName.FLATE_DECODE)) {
          new ContentStreamWriter(out).writeTokens(rewrite.tokens());
        }
        page.setContents(replacement);
      } else if (rewrite.owner() instanceof COSStream form) {
        form.removeItem(COSName.DECODE_PARMS);
        form.removeItem(COSName.DP);
        form.removeItem(COSName.FILTER);
        try (OutputStream out = form.createOutputStream(COSName.FLATE_DECODE)) {
          new ContentStreamWriter(out).writeTokens(rewrite.tokens());
        }
      }
    }
    int structParents = 0;
    for (PDPage page : doc.getPages()) {
      structParents += remove(page.getCOSObject(), COSName.STRUCT_PARENTS);
      COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
      if (annots != null) {
        for (int i = 0; i < annots.size(); i++) {
          if (annots.getObject(i) instanceof COSDictionary annot) {
            structParents += remove(annot, STRUCT_PARENT);
          }
        }
      }
    }
    for (COSStream form : forms) {
      structParents += remove(form, COSName.STRUCT_PARENTS);
      structParents += remove(form, STRUCT_PARENT);
    }
    doc.getDocumentCatalog().getCOSObject().removeItem(COSName.STRUCT_TREE_ROOT);
    return new Outcome(counts[0], counts[1], structParents);
  }

  private static int remove(COSDictionary dict, COSName key) {
    if (dict.containsKey(key)) {
      dict.removeItem(key);
      return 1;
    }
    return 0;
  }

  private static void collectForms(PDResources resources, Set<COSStream> forms, List<Rewrite> rewrites, int[] counts, int depth)
      throws IOException, UnsupportedContentException {
    if (resources == null) {
      return;
    }
    COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
    if (xObjects == null) {
      return;
    }
    for (COSName name : new ArrayList<>(xObjects.keySet())) {
      if (!(xObjects.getDictionaryObject(name) instanceof COSStream stream) || !COSName.FORM.equals(stream.getCOSName(COSName.SUBTYPE)) || !forms.add(stream)) {
        continue;
      }
      if (depth >= MAX_FORM_DEPTH) {
        throw new UnsupportedContentException("form XObjects are nested deeper than " + MAX_FORM_DEPTH + " levels; the tags are kept");
      }
      COSDictionary own = stream.getCOSDictionary(COSName.RESOURCES);
      PDResources formResources = own != null ? new PDResources(own) : resources;
      List<Object> rewritten = rewrite(new PDFStreamParser(new PDFormXObject(stream)).parse(), formResources, counts);
      if (rewritten != null) {
        rewrites.add(new Rewrite(stream, rewritten));
      }
      if (own != null) {
        collectForms(formResources, forms, rewrites, counts, depth + 1);
      }
    }
  }

  /** The tokens without MCIDs, or {@code null} when the stream has none. */
  private static List<Object> rewrite(List<Object> tokens, PDResources resources, int[] counts) throws UnsupportedContentException {
    List<Object> out = new ArrayList<>(tokens.size());
    Deque<Boolean> open = new ArrayDeque<>();
    boolean changed = false;
    int operandStart = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if (!(tokens.get(i) instanceof Operator op)) {
        continue;
      }
      String name = op.getName();
      List<Object> operands = tokens.subList(operandStart, i);
      switch (name) {
        case "BDC" -> {
          COSDictionary properties = operands.size() == 2 ? properties(operands.get(1), resources) : null;
          if (properties != null && properties.containsKey(COSName.MCID)) {
            changed = true;
            counts[1]++;
            COSDictionary rest = new COSDictionary();
            rest.setDirect(true);
            for (COSName key : properties.keySet()) {
              if (!COSName.MCID.equals(key)) {
                rest.setItem(key, inline(properties.getDictionaryObject(key), 0));
              }
            }
            if (rest.size() == 0 && COSName.ARTIFACT.equals(operands.get(0))) {
              // Still an artifact, only without the MCID it should never have had.
              out.add(operands.get(0));
              out.add(BMC);
              open.push(false);
            } else if (rest.size() == 0) {
              counts[0]++;
              open.push(true);
            } else {
              out.add(operands.get(0));
              out.add(rest);
              out.add(op);
              open.push(false);
            }
          } else {
            out.addAll(operands);
            out.add(op);
            open.push(false);
          }
        }
        case "BMC" -> {
          out.addAll(operands);
          out.add(op);
          open.push(false);
        }
        case "EMC" -> {
          if (open.isEmpty()) {
            throw new UnsupportedContentException(UNBALANCED);
          }
          out.addAll(operands);
          if (!open.pop()) {
            out.add(op);
          }
        }
        default -> {
          out.addAll(operands);
          out.add(op);
        }
      }
      operandStart = i + 1;
    }
    if (!open.isEmpty()) {
      throw new UnsupportedContentException(UNBALANCED);
    }
    out.addAll(tokens.subList(operandStart, tokens.size()));
    return changed ? out : null;
  }

  private static final Operator BMC = Operator.getOperator("BMC");
  private static final int MAX_INLINE_DEPTH = 8;

  /**
   * A copy that can be written inline into a content stream: every indirect reference resolved (a
   * content stream cannot hold one). Streams, cycles and deeper nesting are refused while nothing has
   * been changed yet.
   */
  private static COSBase inline(COSBase value, int depth) throws UnsupportedContentException {
    COSBase v = value instanceof org.apache.pdfbox.cos.COSObject ref ? ref.getObject() : value;
    if (depth > MAX_INLINE_DEPTH || v instanceof COSStream) {
      throw new UnsupportedContentException("marked-content properties cannot be written inline; the tags are kept");
    }
    if (v instanceof COSDictionary dict) {
      COSDictionary copy = new COSDictionary();
      copy.setDirect(true);
      for (COSName key : dict.keySet()) {
        copy.setItem(key, inline(dict.getItem(key), depth + 1));
      }
      return copy;
    }
    if (v instanceof COSArray array) {
      COSArray copy = new COSArray();
      copy.setDirect(true);
      for (int i = 0; i < array.size(); i++) {
        copy.add(inline(array.get(i), depth + 1));
      }
      return copy;
    }
    return v == null ? org.apache.pdfbox.cos.COSNull.NULL : v;
  }

  private static COSDictionary properties(Object operand, PDResources resources) {
    if (operand instanceof COSDictionary inline) {
      return inline;
    }
    if (operand instanceof COSName named && resources != null) {
      COSDictionary all = resources.getCOSObject().getCOSDictionary(COSName.PROPERTIES);
      if (all != null && all.getDictionaryObject(named) instanceof COSDictionary dict) {
        return dict;
      }
    }
    return null;
  }
}
