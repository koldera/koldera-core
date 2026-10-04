package com.koldera.pdf.fonts;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * {@code PDF_FONT_INVISIBLE_TEXT_V1} (mode {@code fix}, uploads): an OCR layer's invisible Type 0 font whose shown codes resolve
 * to glyph 0 ({@code .notdef}) gets a program where they resolve to a real, empty glyph (veraPDF fails any {@code .notdef} use).
 * Fonts shared outside the repaired set, or reached via patterns, Type 3 or rollover appearances, are left. The change is
 * verified on a scratch copy first: rendering and text must stay identical ({@link RenderIdentity}) or nothing changes.
 */
public final class InvisibleTextFontRule implements RemediationRule {

  static final String NOTHING = "no invisible-text font references .notdef";

  /** Why a font was left; the clauses of the reason, in this order. */
  enum Left {
    VISIBLE("also used for visible text or in an annotation appearance"),
    REACHED("also used in a pattern, a Type 3 glyph or a rollover or down appearance"),
    SHARED("shares its font data with another font");

    final String why;

    Left(String why) {
      this.why = why;
    }
  }

  private static final COSName CID_SET = COSName.getPDFName("CIDSet");

  /** The rendering comparison ({@link RenderIdentity#changes} in production; a test seam). */
  interface RenderCheck {
    String changes(PDDocument document, List<Integer> pages, RenderIdentity.Mutation mutation);
  }

  private final RenderCheck renderCheck;

  public InvisibleTextFontRule() {
    this(RenderIdentity::changes);
  }

  InvisibleTextFontRule(RenderCheck renderCheck) {
    this.renderCheck = renderCheck;
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_FONT_INVISIBLE_TEXT_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("invisible text fonts are only repaired in fix for uploaded PDFs", null, null);
    }
    return RuleDecision.apply("the invisible text fonts are checked when this rule applies", null, null);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    try {
      return run(document);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not repair the invisible text fonts (" + e.getClass().getSimpleName() + ")", null, null);
    }
  }

  /** One descendant CIDFont to give the invisible program, with the Type 0 fonts that show it. */
  private static final class Target {
    final COSDictionary descendant;
    final COSDictionary descriptor;
    final List<FontUses.Use> uses = new ArrayList<>();
    int maxCid;

    Target(COSDictionary descendant, COSDictionary descriptor) {
      this.descendant = descendant;
      this.descriptor = descriptor;
    }
  }

  /** The targets in order of first use, the pages to compare, and the fonts left per reason. */
  private record Plan(List<Target> targets, List<Integer> pages, Map<Left, List<String>> left) {}

  private WorkerResult.Action run(PDDocument document) throws IOException {
    Plan plan = analyze(document);
    String leftClause = leftClause(plan.left());
    if (plan.targets().isEmpty()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, leftClause == null ? NOTHING : leftClause, null, null);
    }
    TreeSet<Integer> usedOn = new TreeSet<>();
    int fonts = 0;
    long glyphs = 0;
    for (Target target : plan.targets()) {
      for (FontUses.Use use : target.uses) {
        fonts++;
        usedOn.addAll(use.pages());
        glyphs += use.codes().values().stream().mapToLong(Integer::longValue).sum();
      }
    }
    int expectedTargets = plan.targets().size();
    String changed = renderCheck.changes(document, plan.pages(), scratch -> {
      Plan again = analyze(scratch);
      if (again.targets().size() != expectedTargets) {
        throw new IllegalStateException("the scratch copy's fonts differ");
      }
      execute(scratch, again);
    });
    if (changed != null) {
      String reason = unchangedReason(changed);
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, leftClause == null ? reason : reason + "; " + leftClause, null, null);
    }
    execute(document, plan);
    String reason = "invisible text fonts given a font program without .notdef references: " + fonts + " fonts (" + glyphs + " glyphs on " + usedOn.size()
        + " pages); rendering and text unchanged" + (leftClause == null ? "" : "; " + leftClause);
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, null,
        fonts + (fonts == 1 ? " invisible text font" : " invisible text fonts") + " without .notdef references");
  }

  /** {@code "left: A, B (why); C (why)"}: each name once, under the first reason that left a font of that name; null when none. */
  private static String leftClause(Map<Left, List<String>> left) {
    List<String> clauses = new ArrayList<>();
    Set<String> named = new LinkedHashSet<>();
    for (Left why : Left.values()) {
      List<String> names = new ArrayList<>();
      for (String name : left.getOrDefault(why, List.of())) {
        if (!named.contains(name)) {
          names.add(name);
        }
      }
      if (!names.isEmpty()) {
        named.addAll(names);
        clauses.add(FontUnicodeRule.names(names) + " (" + why.why + ")");
      }
    }
    return clauses.isEmpty() ? null : "left: " + String.join("; ", clauses);
  }

  private static String unchangedReason(String changed) {
    String page = "the rendering of page ";
    if (changed.startsWith(page)) {
      return "rendering would change on page " + changed.substring(page.length()) + "; left as it is";
    }
    if (changed.equals("the extracted text")) {
      return "the extracted text would change; left as it is";
    }
    return "the rendering could not be compared: " + changed + "; left as it is";
  }

  private static Plan analyze(PDDocument document) throws IOException {
    FontReach reach = FontReach.walk(document);
    Map<COSDictionary, Target> byDescendant = new IdentityHashMap<>();
    List<Target> order = new ArrayList<>();
    Map<Left, List<String>> left = new EnumMap<>(Left.class);
    Set<COSDictionary> candidateType0 = identitySet();
    // Fonts shown visibly or in an appearance (with their descendants): a target sharing data with one is left as VISIBLE.
    Set<COSDictionary> shownVisibly = identitySet();
    List<FontUses.Use> uses = FontUses.collect(document);
    for (FontUses.Use use : uses) {
      if (use.visible() || use.inAppearance()) {
        shownVisibly.add(use.font().getCOSObject());
        if (use.font() instanceof PDType0Font type0 && type0.getDescendantFont() != null) {
          shownVisibly.add(type0.getDescendantFont().getCOSObject());
        }
      }
    }
    for (FontUses.Use use : uses) {
      if (!use.invisible() || !(use.font() instanceof PDType0Font type0) || !identityEncoded(type0.getCOSObject())) {
        continue;
      }
      PDCIDFont descendant = type0.getDescendantFont();
      COSDictionary descriptor = descendant == null ? null : descendant.getCOSObject().getCOSDictionary(COSName.FONT_DESC);
      if (descriptor == null || !embeddedProgram(descendant, descriptor) || !showsNotdef(descendant, use)) {
        continue;
      }
      String name = displayName(type0);
      if (use.visible() || use.inAppearance()) {
        left.computeIfAbsent(Left.VISIBLE, k -> new ArrayList<>()).add(name);
        continue;
      }
      if (reach.restricted(type0.getCOSObject()) || reach.restricted(descendant.getCOSObject())) {
        left.computeIfAbsent(Left.REACHED, k -> new ArrayList<>()).add(name);
        continue;
      }
      candidateType0.add(type0.getCOSObject());
      Target target = byDescendant.computeIfAbsent(descendant.getCOSObject(), key -> {
        Target created = new Target(key, descriptor);
        order.add(created);
        return created;
      });
      target.uses.add(use);
      for (int code : use.codes().keySet()) {
        target.maxCid = Math.max(target.maxCid, descendant.codeToCID(code));
      }
    }
    // A descendant or descriptor that a font outside the targets shares would change that font too: those targets are left.
    Set<COSDictionary> targetDescendants = identitySet();
    targetDescendants.addAll(byDescendant.keySet());
    Map<COSDictionary, Left> sharedDescendants = new IdentityHashMap<>();
    Map<COSDictionary, Left> sharedDescriptors = new IdentityHashMap<>();
    for (COSDictionary font : reach.fonts()) {
      Left why = shownVisibly.contains(font) ? Left.VISIBLE : reach.restricted(font) ? Left.REACHED : Left.SHARED;
      if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
        if (!candidateType0.contains(font) && font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray descendants) {
          for (int i = 0; i < descendants.size(); i++) {
            if (descendants.getObject(i) instanceof COSDictionary d) {
              sharedDescendants.merge(d, why, InvisibleTextFontRule::first);
            }
          }
        }
      } else if (!targetDescendants.contains(font) && font.getDictionaryObject(COSName.FONT_DESC) instanceof COSDictionary descriptor) {
        sharedDescriptors.merge(descriptor, why, InvisibleTextFontRule::first);
      }
    }
    // Leaving a target keeps its descriptor as it is, so a target that shares that descriptor is left too (until nothing changes).
    List<Target> targets = new ArrayList<>(order);
    boolean dropped = true;
    while (dropped) {
      dropped = false;
      for (Target target : List.copyOf(targets)) {
        Left why = first(sharedDescendants.get(target.descendant), sharedDescriptors.get(target.descriptor));
        if (why != null) {
          targets.remove(target);
          sharedDescriptors.merge(target.descriptor, why, InvisibleTextFontRule::first);
          dropped = true;
          for (FontUses.Use use : target.uses) {
            left.computeIfAbsent(why, k -> new ArrayList<>()).add(displayName((PDType0Font) use.font()));
          }
        }
      }
    }
    // Pages to compare: every page that shows a target font or whose resources reach it.
    TreeSet<Integer> pages = new TreeSet<>();
    for (Target target : targets) {
      pages.addAll(reach.pages(target.descendant));
      for (FontUses.Use use : target.uses) {
        pages.addAll(use.pages());
        pages.addAll(reach.pages(use.font().getCOSObject()));
      }
    }
    return new Plan(targets, List.copyOf(pages), left);
  }

  /** The reason listed first in {@link Left} (VISIBLE before REACHED before SHARED); null when both are. */
  private static Left first(Left a, Left b) {
    if (a == null) {
      return b;
    }
    return b == null || a.ordinal() <= b.ordinal() ? a : b;
  }

  private static Set<COSDictionary> identitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }

  private static boolean identityEncoded(COSDictionary type0) {
    COSName encoding = type0.getCOSName(COSName.ENCODING);
    return COSName.IDENTITY_H.equals(encoding) || COSName.IDENTITY_V.equals(encoding);
  }

  private static boolean embeddedProgram(PDCIDFont descendant, COSDictionary descriptor) {
    if (descendant instanceof PDCIDFontType0 cff) {
      return descriptor.getDictionaryObject(COSName.FONT_FILE3) instanceof COSStream && cff.getCFFFont() != null && !cff.isDamaged();
    }
    if (descendant instanceof PDCIDFontType2 trueType) {
      return descriptor.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream && trueType.isEmbedded() && !trueType.isDamaged();
    }
    return false;
  }

  /** Some shown code resolves to glyph 0; a code whose glyph cannot be determined counts as not ({@code .notdef} is never assumed). */
  private static boolean showsNotdef(PDCIDFont descendant, FontUses.Use use) {
    for (int code : use.codes().keySet()) {
      try {
        if (descendant.codeToGID(code) == 0) {
          return true;
        }
      } catch (IOException | RuntimeException e) {
        // unknown glyph: not evidence of .notdef
      }
    }
    return false;
  }

  private static String displayName(PDType0Font font) {
    String name = font.getName();
    return name == null ? "Type 0 font" : name;
  }

  /** Rewrites every target's descendant and descriptor (one shared program stream) and drops PDFBox's cached fonts. */
  private static void execute(PDDocument document, Plan plan) throws IOException {
    byte[] program = InvisibleGlyphProgram.bytes();
    COSStream fontFile = stream(document, program);
    fontFile.setInt(COSName.LENGTH1, program.length);
    for (Target target : plan.targets()) {
      target.descriptor.removeItem(COSName.FONT_FILE);
      target.descriptor.removeItem(COSName.FONT_FILE3);
      target.descriptor.removeItem(CID_SET);
      target.descriptor.setItem(COSName.FONT_FILE2, fontFile);
      byte[] map = new byte[2 * (target.maxCid + 1)];
      for (int i = 1; i < map.length; i += 2) {
        map[i] = 1;
      }
      target.descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
      target.descendant.setItem(COSName.CID_TO_GID_MAP, stream(document, map));
    }
    // Later rules read fonts through the document's resource cache, which still holds the programs replaced here.
    document.setResourceCache(new DefaultResourceCache());
  }

  private static COSStream stream(PDDocument document, byte[] data) throws IOException {
    COSStream stream = document.getDocument().createCOSStream();
    try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
      out.write(data);
    }
    return stream;
  }
}
