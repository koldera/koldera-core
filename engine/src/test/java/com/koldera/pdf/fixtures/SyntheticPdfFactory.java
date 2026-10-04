package com.koldera.pdf.fixtures;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Deterministic, synthetic PDF fixtures generated in-process with PDFBox, not derived from any customer document; text is
 * filler and the signing certificate is a throwaway self-signed key generated per JVM. Fixtures are deliberately
 * <em>untagged</em> unless stated, so they fail PDF/UA-1 machine validation; the engine must never fabricate tags.
 */
public final class SyntheticPdfFactory {

  public static final String FILLER_TEXT = "Synthetic fixture paragraph for accessibility engine tests.";

  private SyntheticPdfFactory() {}

  /** Options for the plain single-page fixture family. */
  public record Options(
      String language,
      String infoTitle,
      Boolean displayDocTitle,
      boolean hideToolbar,
      int pages,
      boolean withImage,
      boolean withTable,
      /** No text at all: every page is one raster image, the shape of a scanned document. */
      boolean imageOnly) {

    public static Options plain() {
      return new Options(null, null, null, false, 1, false, false, false);
    }

    /** Every page becomes a single raster image with no text: the shape of a scanned document. */
    public Options scanned() {
      return new Options(language, infoTitle, displayDocTitle, hideToolbar, pages, true, withTable, true);
    }

    public Options language(String value) {
      return new Options(value, infoTitle, displayDocTitle, hideToolbar, pages, withImage, withTable, imageOnly);
    }

    public Options infoTitle(String value) {
      return new Options(language, value, displayDocTitle, hideToolbar, pages, withImage, withTable, imageOnly);
    }

    public Options displayDocTitle(Boolean value) {
      return new Options(language, infoTitle, value, hideToolbar, pages, withImage, withTable, imageOnly);
    }

    public Options hideToolbar(boolean value) {
      return new Options(language, infoTitle, displayDocTitle, value, pages, withImage, withTable, imageOnly);
    }

    public Options pages(int value) {
      return new Options(language, infoTitle, displayDocTitle, hideToolbar, value, withImage, withTable, imageOnly);
    }

    public Options image() {
      return new Options(language, infoTitle, displayDocTitle, hideToolbar, pages, true, withTable, imageOnly);
    }

    public Options table() {
      return new Options(language, infoTitle, displayDocTitle, hideToolbar, pages, withImage, true, imageOnly);
    }
  }

  /** Untagged one-page PDF with filler text, no /Lang, no Info Title. */
  public static byte[] simpleUntagged() {
    return build(Options.plain());
  }

  public static byte[] build(Options options) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < options.pages(); i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          if (!options.imageOnly()) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(72, 720);
            cs.showText(FILLER_TEXT + " Page " + (i + 1) + ".");
            cs.endText();
          }
          if (options.withImage() && (i == 0 || options.imageOnly())) {
            BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 40; x++) {
              for (int y = 0; y < 40; y++) {
                img.setRGB(x, y, ((x + y) % 2 == 0 ? Color.BLUE : Color.WHITE).getRGB());
              }
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, img);
            cs.drawImage(image, 72, 600, 80, 80);
          }
          if (options.withTable() && i == 0) {
            // A grid of stroked rectangles with cell text: visually a table, semantically nothing.
            for (int r = 0; r < 3; r++) {
              for (int c = 0; c < 3; c++) {
                cs.addRect(72 + c * 120, 400 - r * 30, 120, 30);
                cs.stroke();
                cs.beginText();
                cs.setFont(font, 10);
                cs.newLineAtOffset(76 + c * 120, 410 - r * 30);
                cs.showText("R" + (r + 1) + "C" + (c + 1));
                cs.endText();
              }
            }
          }
        }
      }
      if (options.language() != null) {
        doc.getDocumentCatalog().setLanguage(options.language());
      }
      if (options.infoTitle() != null) {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(options.infoTitle());
      }
      if (options.displayDocTitle() != null || options.hideToolbar()) {
        PDViewerPreferences prefs = new PDViewerPreferences(new COSDictionary());
        if (options.displayDocTitle() != null) {
          prefs.setDisplayDocTitle(options.displayDocTitle());
        }
        if (options.hideToolbar()) {
          prefs.setHideToolbar(true);
        }
        doc.getDocumentCatalog().setViewerPreferences(prefs);
      }
      // Fixed creation metadata keeps the byte stream reproducible across runs.
      Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      fixed.setTimeInMillis(1_600_000_000_000L);
      doc.getDocumentInformation().setCreationDate(fixed);
      doc.getDocumentInformation().setModificationDate(fixed);
      doc.setDocumentId(42L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Minimal <em>tagged</em> one-page PDF: /MarkInfo Marked, a structure tree Document > H1 + P
   * whose kids are marked-content references (MCID 0 and 1) into the page content, plus the
   * ParentTree. Enough structure for a tag probe to call it usable; not a PDF/UA-1 claim.
   */
  public static byte[] tagged() {
    return tagged(false, 43L);
  }

  /**
   * {@link #tagged()} plus unmarked painting after the tagged content: three stroked ruling lines
   * ({@code 72 600 m 500 600 l S}, {@code … 580 …}, {@code … 560 …}) and one filled rectangle
   * ({@code 100 100 50 50 re f}), none of it inside marked content.
   */
  public static byte[] taggedWithUntaggedRules() {
    return tagged(true, 44L);
  }

  private static byte[] tagged(boolean untaggedRules, long documentId) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int mcid = 0; mcid < 2; mcid++) {
          COSDictionary props = new COSDictionary();
          props.setInt(COSName.MCID, mcid);
          cs.beginMarkedContent(COSName.getPDFName(mcid == 0 ? "H1" : "P"), PDPropertyList.create(props));
          cs.beginText();
          cs.setFont(font, mcid == 0 ? 18 : 12);
          cs.newLineAtOffset(72, 720 - mcid * 40);
          cs.showText(mcid == 0 ? "Synthetic heading" : FILLER_TEXT);
          cs.endText();
          cs.endMarkedContent();
        }
        if (untaggedRules) {
          for (int y = 600; y >= 560; y -= 20) {
            cs.moveTo(72, y);
            cs.lineTo(500, y);
            cs.stroke();
          }
          cs.addRect(100, 100, 50, 50);
          cs.fill();
        }
      }
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      PDStructureElement heading = new PDStructureElement("H1", document);
      heading.setPage(page);
      heading.getCOSObject().setItem(COSName.K, COSInteger.get(0));
      document.appendKid(heading);
      PDStructureElement paragraph = new PDStructureElement("P", document);
      paragraph.setPage(page);
      paragraph.getCOSObject().setItem(COSName.K, COSInteger.get(1));
      document.appendKid(paragraph);
      COSArray parents = new COSArray();
      parents.add(heading.getCOSObject());
      parents.add(paragraph.getCOSObject());
      COSDictionary parentTree = new COSDictionary();
      COSArray nums = new COSArray();
      nums.add(COSInteger.ZERO);
      nums.add(parents);
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(1);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Tagged synthetic fixture");
      Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      fixed.setTimeInMillis(1_600_000_000_000L);
      doc.getDocumentInformation().setCreationDate(fixed);
      doc.getDocumentInformation().setModificationDate(fixed);
      doc.setDocumentId(documentId);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One tagged block for {@link #taggedBlocks}: role, visible text, page (1-based) and an optional
   * fixed position (points from the page's lower-left corner). Positioned blocks let a fixture put
   * text in two columns or out of top-to-bottom order without changing its content order.
   */
  public record Block(String role, String text, int page, Float x, Float y) {
    public Block(String role, String text, int page) {
      this(role, text, page, null, null);
    }

    public static Block of(String role, String text) {
      return new Block(role, text, 1);
    }

    public static Block at(String role, String text, int page, float x, float y) {
      return new Block(role, text, page, x, y);
    }
  }

  /** Options for the tagged multi-block fixture family. */
  public record TaggedOptions(
      List<Block> blocks,
      /** Structure-tree order of the blocks as indexes into {@code blocks}; null keeps content order. */
      int[] structureOrder,
      /** Adds a Link annotation (with OBJR under a Link element) around the block at this index, or -1. */
      int linkBlock,
      /** Wraps every block in a Sect element instead of hanging it directly under Document. */
      boolean nested,
      /** /Alt text per block index (a Figure block draws an image instead of text, so /Alt is all a reader gets). */
      java.util.Map<Integer, String> alternates,
      /**
       * Block indexes drawn the way Chromium/Skia draws a right-to-left run: inside the block's MCID a
       * {@code /ReversedChars} sequence shows the glyphs in visual (reversed) order, and every other
       * glyph is a placeholder wrapped in {@code /Span <</ActualText …>>} without an MCID of its own.
       */
      java.util.Set<Integer> reversedRuns,
      /** Written as the structure tree root's /RoleMap (sorted by key); empty writes none. */
      java.util.Map<String, String> roleMap) {

    public TaggedOptions(
        List<Block> blocks, int[] structureOrder, int linkBlock, boolean nested, java.util.Map<Integer, String> alternates, java.util.Set<Integer> reversedRuns) {
      this(blocks, structureOrder, linkBlock, nested, alternates, reversedRuns, java.util.Map.of());
    }

    public static TaggedOptions of(Block... blocks) {
      return new TaggedOptions(List.of(blocks), null, -1, false, java.util.Map.of(), java.util.Set.of());
    }

    public TaggedOptions structureOrder(int... order) {
      return new TaggedOptions(blocks, order, linkBlock, nested, alternates, reversedRuns, roleMap);
    }

    public TaggedOptions link(int block) {
      return new TaggedOptions(blocks, structureOrder, block, nested, alternates, reversedRuns, roleMap);
    }

    public TaggedOptions nested(boolean value) {
      return new TaggedOptions(blocks, structureOrder, linkBlock, value, alternates, reversedRuns, roleMap);
    }

    public TaggedOptions alt(int block, String text) {
      java.util.Map<Integer, String> next = new java.util.HashMap<>(alternates);
      next.put(block, text);
      return new TaggedOptions(blocks, structureOrder, linkBlock, nested, java.util.Map.copyOf(next), reversedRuns, roleMap);
    }

    public TaggedOptions reversedRun(int block) {
      java.util.Set<Integer> next = new java.util.HashSet<>(reversedRuns);
      next.add(block);
      return new TaggedOptions(blocks, structureOrder, linkBlock, nested, alternates, java.util.Set.copyOf(next), roleMap);
    }

    public TaggedOptions roleMap(java.util.Map<String, String> value) {
      return new TaggedOptions(blocks, structureOrder, linkBlock, nested, alternates, reversedRuns, java.util.Map.copyOf(value));
    }
  }

  /**
   * Tagged fixture with an arbitrary sequence of blocks (each its own marked-content sequence with a
   * unique MCID, drawn top-to-bottom in content order, one or more pages), a Document root whose
   * kids follow {@code structureOrder} (so the structure tree can deliberately disagree with the
   * content order), a correct ParentTree, and optionally a Link annotation referenced through OBJR.
   */
  public static byte[] taggedBlocks(TaggedOptions options) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      int pageCount = options.blocks().stream().mapToInt(Block::page).max().orElse(1);
      List<PDPage> pages = new ArrayList<>();
      List<PDPageContentStream> streams = new ArrayList<>();
      int[] nextMcid = new int[pageCount];
      float[] cursor = new float[pageCount];
      for (int i = 0; i < pageCount; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        pages.add(page);
        streams.add(new PDPageContentStream(doc, page));
        cursor[i] = 720;
      }
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      List<PDStructureElement> elements = new ArrayList<>();
      List<Integer> mcids = new ArrayList<>();
      List<Integer> mcidPages = new ArrayList<>();
      COSArray[] parentArrays = new COSArray[pageCount];
      for (int i = 0; i < pageCount; i++) {
        parentArrays[i] = new COSArray();
      }
      PDAnnotationLink link = null;
      for (int b = 0; b < options.blocks().size(); b++) {
        Block block = options.blocks().get(b);
        int pageIndex = block.page() - 1;
        PDPage page = pages.get(pageIndex);
        PDPageContentStream cs = streams.get(pageIndex);
        int mcid = nextMcid[pageIndex]++;
        COSDictionary props = new COSDictionary();
        props.setInt(COSName.MCID, mcid);
        cs.beginMarkedContent(COSName.getPDFName(block.role()), PDPropertyList.create(props));
        float blockY = block.y() != null ? block.y() : cursor[pageIndex];
        if ("Figure".equals(block.role()) || "Figure".equals(options.roleMap().get(block.role()))) {
          // An image and nothing else: the only thing a reader can get is the element's /Alt (also for a type the /RoleMap maps to Figure).
          BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
          for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
              img.setRGB(x, y, ((x / 6 + y / 6) % 2 == 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY).getRGB());
            }
          }
          cs.drawImage(LosslessFactory.createFromImage(doc, img), block.x() != null ? block.x() : 72, blockY - 4, 24, 24);
        } else if (options.reversedRuns().contains(b)) {
          cs.beginMarkedContent(COSName.getPDFName("ReversedChars"));
          cs.beginText();
          cs.setFont(font, 11);
          cs.newLineAtOffset(block.x() != null ? block.x() : 72, blockY);
          String visual = new StringBuilder(block.text()).reverse().toString();
          for (int i = 0; i < visual.length(); i++) {
            if (i % 2 == 1) {
              COSDictionary span = new COSDictionary();
              span.setString(COSName.ACTUAL_TEXT, String.valueOf(visual.charAt(i)));
              cs.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(span));
              cs.showText("#");
              cs.endMarkedContent();
            } else {
              cs.showText(String.valueOf(visual.charAt(i)));
            }
          }
          cs.endText();
          cs.endMarkedContent();
        } else {
          cs.beginText();
          cs.setFont(font, block.role().startsWith("H") ? 16 : 11);
          cs.newLineAtOffset(block.x() != null ? block.x() : 72, blockY);
          cs.showText(block.text());
          cs.endText();
        }
        cs.endMarkedContent();
        PDStructureElement element = new PDStructureElement(block.role(), document);
        element.setPage(page);
        if (options.alternates().containsKey(b)) {
          element.setAlternateDescription(options.alternates().get(b));
        }
        element.getCOSObject().setItem(COSName.K, COSInteger.get(mcid));
        elements.add(element);
        mcids.add(mcid);
        mcidPages.add(pageIndex);
        parentArrays[pageIndex].add(element.getCOSObject());
        if (b == options.linkBlock()) {
          link = new PDAnnotationLink();
          link.setRectangle(new PDRectangle((block.x() != null ? block.x() : 72) - 2, blockY - 4, 200, 16));
          link.setStructParent(pageCount + 100);
          page.getAnnotations().add(link);
          PDStructureElement linkElement = new PDStructureElement("Link", element);
          linkElement.setPage(page);
          PDObjectReference reference = new PDObjectReference();
          reference.setReferencedObject(link);
          linkElement.appendKid(reference);
          element.appendKid(linkElement);
        }
        if (block.y() == null) {
          cursor[pageIndex] -= 40;
        }
      }
      for (PDPageContentStream cs : streams) {
        cs.close();
      }
      int[] order = options.structureOrder() == null ? java.util.stream.IntStream.range(0, elements.size()).toArray() : options.structureOrder();
      for (int index : order) {
        PDStructureElement element = elements.get(index);
        if (options.nested()) {
          PDStructureElement sect = new PDStructureElement("Sect", document);
          sect.setPage(pages.get(options.blocks().get(index).page() - 1));
          element.setParent(sect);
          sect.appendKid(element);
          document.appendKid(sect);
        } else {
          document.appendKid(element);
        }
      }
      COSArray nums = new COSArray();
      for (int i = 0; i < pageCount; i++) {
        nums.add(COSInteger.get(i));
        nums.add(parentArrays[i]);
        pages.get(i).getCOSObject().setInt(COSName.STRUCT_PARENTS, i);
      }
      if (link != null) {
        nums.add(COSInteger.get(pageCount + 100));
        // The link's parent is the Link structure element: find it as the OBJR owner.
        for (PDStructureElement element : elements) {
          for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement child && "Link".equals(child.getStructureType())) {
              nums.add(child.getCOSObject());
            }
          }
        }
      }
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(pageCount + 101);
      if (!options.roleMap().isEmpty()) {
        COSDictionary roleMap = new COSDictionary();
        for (String type : new java.util.TreeSet<>(options.roleMap().keySet())) {
          roleMap.setName(COSName.getPDFName(type), options.roleMap().get(type));
        }
        root.getCOSObject().setItem(COSName.ROLE_MAP, roleMap);
      }
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Tagged blocks fixture");
      Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      fixed.setTimeInMillis(1_600_000_000_000L);
      doc.getDocumentInformation().setCreationDate(fixed);
      doc.getDocumentInformation().setModificationDate(fixed);
      doc.setDocumentId(44L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One-page tagged fixture of body lines (Helvetica 11 pt, 30 pt apart, each line its own
   * paragraph, every line {@code "Line NN of synthetic body text."}, so every line has the same glyph
   * count): the first {@code tagged} lines each in {@code /P <</MCID k>>} with a {@code P} element of
   * its own, the remaining {@code untagged} lines unmarked. Document root, ParentTree, /Lang, Info title.
   */
  public static byte[] taggedWithUntaggedLines(int tagged, int untagged) {
    int[] owners = new int[tagged];
    for (int k = 0; k < tagged; k++) {
      owners[k] = k;
    }
    return taggedLines(tagged + untagged, owners, 60L);
  }

  /**
   * One-page tagged fixture with {@code mcids} body lines (as in {@link #taggedWithUntaggedLines}),
   * each its own MCID: the first {@code inLargest} MCIDs are the kids of one {@code P}, every other
   * MCID has a {@code P} of its own.
   */
  public static byte[] taggedOneLargeElement(int mcids, int inLargest) {
    int[] owners = new int[mcids];
    for (int k = 0; k < mcids; k++) {
      owners[k] = k < inLargest ? 0 : k - inLargest + 1;
    }
    return taggedLines(mcids, owners, 61L);
  }

  /** {@code owners[k]} is the index of the P element that holds MCID k; lines from {@code owners.length} on are unmarked. */
  private static byte[] taggedLines(int lines, int[] owners, long documentId) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      float height = Math.max(PDRectangle.A4.getHeight(), 90 + 30f * lines);
      PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getWidth(), height));
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int n = 0; n < lines; n++) {
          boolean marked = n < owners.length;
          if (marked) {
            COSDictionary props = new COSDictionary();
            props.setInt(COSName.MCID, n);
            cs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
          }
          cs.beginText();
          cs.setFont(font, 11);
          cs.newLineAtOffset(72, height - 60 - 30f * n);
          cs.showText(String.format(java.util.Locale.ROOT, "Line %02d of synthetic body text.", n + 1));
          cs.endText();
          if (marked) {
            cs.endMarkedContent();
          }
        }
      }
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      List<PDStructureElement> elements = new ArrayList<>();
      List<COSArray> kids = new ArrayList<>();
      COSArray parents = new COSArray();
      for (int k = 0; k < owners.length; k++) {
        if (owners[k] == elements.size()) {
          PDStructureElement element = new PDStructureElement("P", document);
          element.setPage(page);
          document.appendKid(element);
          elements.add(element);
          kids.add(new COSArray());
        }
        kids.get(owners[k]).add(COSInteger.get(k));
        parents.add(elements.get(owners[k]).getCOSObject());
      }
      for (int e = 0; e < elements.size(); e++) {
        COSArray k = kids.get(e);
        elements.get(e).getCOSObject().setItem(COSName.K, k.size() == 1 ? k.get(0) : k);
      }
      COSArray nums = new COSArray();
      nums.add(COSInteger.ZERO);
      nums.add(parents);
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(1);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Tagged lines fixture");
      finish(doc, documentId);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One described element of {@link #taggedUnusableWithDescribedImages}: structure type, {@code /Alt}, {@code /ActualText}, {@code /Lang}
   * (null = absent), the number of {@code images} under one MCID (0 covers a text line), whether they are inline or in a form drawn
   * once ({@code inForm}, with {@code formDraws} draws).
   */
  public record DescribedImages(String type, String alt, String actualText, String lang, int images, boolean inForm, boolean inlineImages, int formDraws) {
    public DescribedImages(String type, String alt, String actualText, String lang, int images, boolean inForm) {
      this(type, alt, actualText, lang, images, inForm, false, 1);
    }

    public static DescribedImages figure(String alt) {
      return new DescribedImages("Figure", alt, null, null, 1, false);
    }
  }

  /**
   * A one-page {@code tagged_unusable} upload (one tagged {@code P} line of ten, 10 % tagged text
   * coverage) whose tree also holds {@code described}, each over its own images (checkerboards, 80 pt,
   * placed apart), in the given order after the text; an optional {@code /RoleMap}.
   */
  public static byte[] taggedUnusableWithDescribedImages(List<DescribedImages> described, java.util.Map<String, String> roleMap) {
    return taggedUnusableWithDescribedImages(described, roleMap, false);
  }

  /** As {@link #taggedUnusableWithDescribedImages(List, java.util.Map)}; {@code embeddedFont}: the text in fully embedded LiberationSans (written as raw operators, so no subset; a copy can pass the font checks). */
  public static byte[] taggedUnusableWithDescribedImages(List<DescribedImages> described, java.util.Map<String, String> roleMap, boolean embeddedFont) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      org.apache.pdfbox.pdmodel.font.PDFont font = embeddedFont
          ? org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, false)
          : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), font);
      page.setResources(resources);
      PDImageXObject image = LosslessFactory.createFromImage(doc, checkerboard(40));
      StringBuilder content = new StringBuilder();
      for (int n = 0; n < 10; n++) {
        if (n == 0) {
          content.append("/P <</MCID 0>> BDC\n");
        }
        content.append("BT /F1 11 Tf 72 ").append(780 - 18 * n).append(" Td ").append(shown(font, "Line " + (n + 1) + " of synthetic body text.")).append(" Tj ET\n");
        if (n == 0) {
          content.append("EMC\n");
        }
      }
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      PDStructureElement paragraph = new PDStructureElement("P", document);
      paragraph.setPage(page);
      paragraph.getCOSObject().setItem(COSName.K, COSInteger.get(0));
      document.appendKid(paragraph);
      COSArray pageParents = new COSArray();
      pageParents.add(paragraph.getCOSObject());
      COSArray nums = new COSArray();
      nums.add(COSInteger.ZERO);
      nums.add(pageParents);
      int slot = 0;
      int nextKey = 1;
      for (DescribedImages spec : described) {
        PDStructureElement element = new PDStructureElement(spec.type(), document);
        element.setPage(page);
        if (spec.alt() != null) {
          element.getCOSObject().setString(COSName.ALT, spec.alt());
        }
        if (spec.actualText() != null) {
          element.getCOSObject().setString(COSName.ACTUAL_TEXT, spec.actualText());
        }
        if (spec.lang() != null) {
          element.getCOSObject().setString(COSName.LANG, spec.lang());
        }
        document.appendKid(element);
        StringBuilder body = new StringBuilder();
        if (spec.images() == 0) {
          body.append("BT /F1 11 Tf 72 ").append(560 - 150 * (slot / 2)).append(" Td ").append(shown(font, "Text described as a figure.")).append(" Tj ET\n");
          slot += 2;
        }
        for (int i = 0; i < spec.images(); i++, slot++) {
          body.append("q 80 0 0 80 ").append(72 + 250 * (slot % 2)).append(' ').append(480 - 150 * (slot / 2)).append(spec.inlineImages() ? " cm BI /W 2 /H 2 /CS /G /BPC 8 /F /AHx ID 00FF00FF> EI Q\n" : " cm /Im1 Do Q\n");
        }
        if (!spec.inForm()) {
          int mcid = pageParents.size();
          content.append("/Figure <</MCID ").append(mcid).append(">> BDC\n").append(body).append("EMC\n");
          element.getCOSObject().setItem(COSName.K, COSInteger.get(mcid));
          pageParents.add(element.getCOSObject());
          resources.put(COSName.getPDFName("Im1"), image);
        } else {
          PDFormXObject form = new PDFormXObject(doc);
          form.setBBox(new PDRectangle(PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight()));
          PDResources formResources = new PDResources();
          formResources.put(COSName.getPDFName("Im1"), image);
          formResources.put(COSName.getPDFName("F1"), font);
          form.setResources(formResources);
          int key = nextKey++;
          form.setStructParents(key);
          try (java.io.OutputStream os = form.getContentStream().createOutputStream()) {
            os.write(("/Figure <</MCID 0>> BDC\n" + body + "EMC\n").getBytes(StandardCharsets.US_ASCII));
          }
          COSName formName = resources.add(form);
          for (int draw = 0; draw < spec.formDraws(); draw++) {
            content.append("q 1 0 0 1 0 ").append(-250 * draw).append(" cm /").append(formName.getName()).append(" Do Q\n");
          }
          COSDictionary mcr = new COSDictionary();
          mcr.setItem(COSName.TYPE, COSName.MCR);
          mcr.setItem(COSName.PG, page);
          mcr.setItem(COSName.getPDFName("Stm"), form);
          mcr.setInt(COSName.MCID, 0);
          element.getCOSObject().setItem(COSName.K, mcr);
          COSArray formParents = new COSArray();
          formParents.add(element.getCOSObject());
          nums.add(COSInteger.get(key));
          nums.add(formParents);
        }
      }
      PDStream stream = new PDStream(doc);
      try (java.io.OutputStream os = stream.createOutputStream()) {
        os.write(content.toString().getBytes(StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      if (roleMap != null) {
        COSDictionary map = new COSDictionary();
        roleMap.forEach(map::setName);
        root.getCOSObject().setItem(COSName.ROLE_MAP, map);
      }
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(nextKey);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Described images fixture");
      finish(doc, 131L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A string operand for a text-showing operator: a literal for a simple font, hex glyph codes for an embedded Type0 font. */
  private static String shown(org.apache.pdfbox.pdmodel.font.PDFont font, String text) throws IOException {
    if (!(font instanceof org.apache.pdfbox.pdmodel.font.PDType0Font)) {
      return "(" + text + ")";
    }
    StringBuilder hex = new StringBuilder("<");
    for (byte b : font.encode(text)) {
      hex.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xFF));
    }
    return hex.append('>').toString();
  }

  /**
   * A one-page {@code tagged_unusable} upload whose tree describes non-Figure elements: a {@code P}, a {@code Span} with
   * {@code /ActualText (fi)} over a ligature glyph, a {@code Formula} with {@code /Alt} and {@code /Lang} over two sequences, a {@code P}
   * with only {@code /Lang}, and a {@code Span} with {@code /ActualText} U+00AD over a painted "-". With {@code scattered} the
   * Formula's content is not one contiguous run.
   */
  public static byte[] taggedUnusableWithDescribedText(boolean scattered) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      COSDictionary ligatures = new COSDictionary();
      ligatures.setItem(COSName.TYPE, COSName.FONT);
      ligatures.setItem(COSName.SUBTYPE, COSName.TYPE1);
      ligatures.setName(COSName.BASE_FONT, "Helvetica");
      COSDictionary encoding = new COSDictionary();
      encoding.setItem(COSName.TYPE, COSName.ENCODING);
      encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
      COSArray differences = new COSArray();
      differences.add(COSInteger.ONE);
      differences.add(COSName.getPDFName("fi"));
      encoding.setItem(COSName.DIFFERENCES, differences);
      ligatures.setItem(COSName.ENCODING, encoding);
      resources.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), ligatures);
      page.setResources(resources);
      String german = "/P <</MCID 4>> BDC BT /F1 11 Tf 72 470 Td (Ein deutscher Satz.) Tj ET EMC\n";
      StringBuilder content = new StringBuilder("/P <</MCID 0>> BDC BT /F1 11 Tf 72 780 Td (Line 1 of synthetic body text.) Tj ET EMC\n");
      for (int n = 2; n <= 11; n++) {
        content.append("BT /F1 11 Tf 72 ").append(780 - 18 * (n - 1)).append(" Td (Line ").append(n).append(" of synthetic body text.) Tj ET\n");
      }
      content.append("BT /F2 11 Tf 72 560 Td (A de) Tj /Span <</MCID 1>> BDC <01> Tj EMC (ned ligature.) Tj ET\n");
      content.append("BT /F1 11 Tf 72 540 Td (A regu) Tj /Span <</MCID 5>> BDC (-) Tj EMC ET BT /F1 11 Tf 72 527 Td (lation.) Tj ET\n");
      content.append("/Formula <</MCID 2>> BDC BT /F1 11 Tf 72 500 Td (E = mc) Tj ET EMC\n");
      if (scattered) {
        content.append(german);
      }
      content.append("/Formula <</MCID 3>> BDC BT /F1 7 Tf 110 505 Td (2) Tj ET EMC\n");
      if (!scattered) {
        content.append(german);
      }
      PDStream stream = new PDStream(doc);
      try (java.io.OutputStream os = stream.createOutputStream()) {
        os.write(content.toString().getBytes(StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      COSArray pageParents = new COSArray();
      String[][] elements = {{"P", null, null, null}, {"Span", null, "fi", null}, {"Formula", "E equals m c squared", null, "en-GB"}, {"P", null, null, "de"}, {"Span", null, "\u00AD", null}};
      int[][] mcids = {{0}, {1}, {2, 3}, {4}, {5}};
      for (int e = 0; e < elements.length; e++) {
        PDStructureElement element = new PDStructureElement(elements[e][0], document);
        element.setPage(page);
        if (elements[e][1] != null) {
          element.getCOSObject().setString(COSName.ALT, elements[e][1]);
        }
        if (elements[e][2] != null) {
          element.getCOSObject().setString(COSName.ACTUAL_TEXT, elements[e][2]);
        }
        if (elements[e][3] != null) {
          element.getCOSObject().setString(COSName.LANG, elements[e][3]);
        }
        COSArray k = new COSArray();
        for (int mcid : mcids[e]) {
          k.add(COSInteger.get(mcid));
          pageParents.add(element.getCOSObject());
        }
        element.getCOSObject().setItem(COSName.K, k.size() == 1 ? k.get(0) : k);
        document.appendKid(element);
      }
      COSArray nums = new COSArray();
      nums.add(COSInteger.ZERO);
      nums.add(pageParents);
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, nums);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.setParentTreeNextKey(1);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDMarkInfo markInfo = new PDMarkInfo();
      markInfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markInfo);
      doc.getDocumentCatalog().setLanguage("en");
      doc.getDocumentInformation().setTitle("Described text fixture");
      finish(doc, 132L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page article for the structure tagger: three two-line paragraphs (every line its
   * own text object, the first line split over two show operators), one raster image and one
   * stroked rule between paragraph two and three. Content order: P, P, image, rule, P.
   */
  public static byte[] untaggedArticle() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        line(cs, font, 72, 720, "Paragraph one ", "line one.");
        line(cs, font, 72, 706, "Paragraph one line two.");
        line(cs, font, 72, 660, "Paragraph two line one.");
        line(cs, font, 72, 646, "Paragraph two line two.");
        cs.drawImage(LosslessFactory.createFromImage(doc, checkerboard(40)), 72, 500, 80, 80);
        cs.moveTo(72, 480);
        cs.lineTo(300, 480);
        cs.stroke();
        line(cs, font, 72, 440, "Paragraph three line one.");
        line(cs, font, 72, 426, "Paragraph three line two.");
      }
      finish(doc, 45L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page fixture with form XObjects: one form containing text is drawn once, one
   * vector-only form is drawn twice. Page text sits far away from the form text.
   */
  public static byte[] untaggedWithFormXObjects() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDFormXObject textForm = new PDFormXObject(doc);
      textForm.setBBox(new PDRectangle(0, 0, 300, 40));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      textForm.setResources(formResources);
      try (java.io.OutputStream os = textForm.getContentStream().createOutputStream()) {
        os.write("BT /F1 12 Tf 0 10 Td (Text inside a form drawn once.) Tj ET\n".getBytes(StandardCharsets.US_ASCII));
      }
      PDFormXObject boxForm = new PDFormXObject(doc);
      boxForm.setBBox(new PDRectangle(0, 0, 50, 50));
      boxForm.setResources(new PDResources());
      try (java.io.OutputStream os = boxForm.getContentStream().createOutputStream()) {
        os.write("0 0 50 50 re f\n".getBytes(StandardCharsets.US_ASCII));
      }
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        line(cs, font, 72, 720, "Page text above the forms.");
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(72, 500));
        cs.drawForm(textForm);
        cs.restoreGraphicsState();
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(72, 300));
        cs.drawForm(boxForm);
        cs.restoreGraphicsState();
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(300, 300));
        cs.drawForm(boxForm);
        cs.restoreGraphicsState();
      }
      finish(doc, 46L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged fixture whose content already carries marked-content sequences with MCIDs although
   * there is no structure tree, the shape of a pdfTeX paper that embeds figures exported from a
   * tagged producer: a page-level {@code /P <</MCID 0>>} text block, and a form drawn once with a
   * {@code /Figure <</MCID 2>>} vector block and a {@code /Span <</MCID 5>>} text block.
   */
  public static byte[] untaggedWithStrayMarkedContent() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDFormXObject figureForm = new PDFormXObject(doc);
      figureForm.setBBox(new PDRectangle(0, 0, 300, 60));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      figureForm.setResources(formResources);
      try (java.io.OutputStream os = figureForm.getContentStream().createOutputStream()) {
        os.write(("/Figure <</MCID 2>> BDC 0 0 m 50 0 l S EMC\n"
                + "BT /F1 12 Tf 0 20 Td /Span <</MCID 5>> BDC (Text in a stray span.) Tj EMC ET\n")
            .getBytes(StandardCharsets.US_ASCII));
      }
      PDResources pageResources = new PDResources();
      pageResources.put(COSName.getPDFName("F1"), font);
      pageResources.put(COSName.getPDFName("Fx1"), figureForm);
      page.setResources(pageResources);
      page.setContents(rawStream(doc,
          "BT /F1 12 Tf 72 720 Td (Plain text before.) Tj ET\n"
              + "BT /F1 12 Tf 72 600 Td /P <</MCID 0>> BDC (Stray page paragraph.) Tj EMC ET\n"
              + "q 1 0 0 1 72 400 cm /Fx1 Do Q\n"));
      finish(doc, 49L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged three-page fixture whose stray marked content sits where the tagger would otherwise
   * see an artifact: the same {@code /P <</MCID 0>>} header text in the top 5 % band of every page
   * (the pagination rule) and a {@code /Span <</MCID 1>>} line outside the crop box (the layout
   * rule), plus one plain body line per page. Author-marked content is never demoted to an artifact.
   */
  public static byte[] untaggedWithStrayMarkedContentInArtifactBands() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < 3; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), font);
        page.setResources(resources);
        page.setContents(rawStream(doc,
            "BT /F1 10 Tf 72 820 Td /P <</MCID 0>> BDC (Running header with MCID) Tj EMC ET\n"
                + "BT /F1 12 Tf 72 720 Td (Body line " + i + ".) Tj ET\n"
                + "BT /F1 12 Tf -200 400 Td /Span <</MCID 1>> BDC (Outside crop box) Tj EMC ET\n"));
      }
      finish(doc, 50L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page fixture whose existing marked content cannot be adopted without guessing: an
   * {@code /Artifact} block that contains an MCID sequence, and an optional-content block that mixes
   * an MCID sequence with unmarked text. Either shape must be refused before validation.
   */
  public static byte[] untaggedWithStrayMcidInsideArtifact() {
    return strayMixed("/Artifact <</Type /Layout>> BDC BT /F1 12 Tf 72 700 Td /P <</MCID 0>> BDC (Tagged inside artifact) Tj EMC ET EMC\n", 51L);
  }

  public static byte[] untaggedWithStrayMcidMixedWithUnmarkedText() {
    return strayMixed("/OC /oc1 BDC BT /F1 12 Tf 72 700 Td (Unmarked text) Tj 0 -20 Td /P <</MCID 0>> BDC (Marked text) Tj EMC ET EMC\n", 52L);
  }

  private static byte[] strayMixed(String content, long documentId) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), font);
      page.setResources(resources);
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 760 Td (Plain line.) Tj ET\n" + content));
      finish(doc, documentId);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged multi-page fixture with the same running header text in the top 5 % of every page,
   * one body line and a differing page number in the bottom 5 %.
   */
  public static byte[] untaggedWithRunningHeader(int pages) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          line(cs, font, 72, 815, "Synthetic running header");
          line(cs, font, 72, 720, "Body paragraph on page " + (i + 1) + ".");
          line(cs, font, 72, 20, "Page " + (i + 1));
        }
      }
      finish(doc, 47L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged one-page fixture whose content stream closes a marked-content sequence it never opened. */
  public static byte[] untaggedUnbalancedMarkedContent() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), font);
      page.setResources(resources);
      PDStream content = new PDStream(doc);
      try (java.io.OutputStream os = content.createOutputStream()) {
        os.write("BT /F1 12 Tf 72 720 Td (Unbalanced fixture.) Tj ET EMC\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.setContents(content);
      finish(doc, 48L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Hand-written minimal untagged PDF whose trailer has no {@code /ID}: PDFBox always writes one on
   * save, so the bytes are assembled here to exercise deterministic id handling downstream.
   */
  public static byte[] untaggedWithoutTrailerId() {
    String content = "BT /F1 12 Tf 72 720 Td (Synthetic fixture without a trailer identifier.) Tj ET\n";
    List<String> objects =
        List.of(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream");
    return handWritten(objects, "");
  }

  /**
   * Hand-written tagged PDF whose {@code Document} {@code /K} is an indirect array containing a reference to itself: a walker
   * without a visited set never ends. With {@code widget}, a widget annotation makes a {@code tagged_unusable} verdict keep the
   * tags, so every later rule walks the cyclic tree.
   */
  public static byte[] taggedWithSelfReferencingKArray(boolean widget) {
    String content = "/P <</MCID 0>> BDC BT /F1 12 Tf 72 720 Td (A paragraph in a cyclic tree.) Tj ET EMC\n";
    String annots = widget ? " /Annots [11 0 R]" : "";
    List<String> objects = new ArrayList<>(List.of(
        "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true >> /StructTreeRoot 6 0 R /Lang (en) >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /StructParents 0 /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R" + annots + " >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream",
        "<< /Type /StructTreeRoot /K 7 0 R /ParentTree 9 0 R /ParentTreeNextKey 1 >>",
        "<< /Type /StructElem /S /Document /P 6 0 R /K 8 0 R >>",
        "[10 0 R 8 0 R]",
        "<< /Nums [0 [10 0 R]] >>",
        "<< /Type /StructElem /S /P /P 7 0 R /Pg 3 0 R /K 0 >>"));
    if (widget) {
      objects.add("<< /Type /Annot /Subtype /Widget /Rect [72 600 172 620] /P 3 0 R /FT /Tx /T (field) >>");
    }
    return handWritten(objects, " /ID [<6b6f6c64657261313233343536373839> <6b6f6c64657261313233343536373839>]");
  }

  /**
   * Hand-written untagged PDF whose page takes {@code /Rotate} and {@code /UserUnit} from indirect integer objects, as producers
   * write stream lengths. PDFBox hands out one shared instance per small integer, so the object-stream writer used to emit a
   * reference for every direct {@code 0} in the saved copy. Three paragraphs far apart get MCIDs 0, 1, 2.
   */
  public static byte[] untaggedWithIndirectSmallIntegers() {
    String content =
        "BT /F1 12 Tf 72 720 Td (First paragraph.) Tj ET\n"
            + "BT /F1 12 Tf 72 600 Td (Second paragraph, far below.) Tj ET\n"
            + "BT /F1 12 Tf 72 480 Td (Third paragraph, further below.) Tj ET\n";
    List<String> objects =
        List.of(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Rotate 6 0 R /UserUnit 7 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream",
            "0",
            "1");
    return handWritten(objects, " /ID [<6b6f6c64657261313233343536373839> <6b6f6c64657261313233343536373839>]");
  }

  /**
   * Hand-written untagged PDF whose page names an object that does not exist ({@code /PieceInfo 9 0 R};
   * there are 5 objects), next to a real indirect integer ({@code /Rotate 6 0 R} = 0).
   */
  public static byte[] untaggedWithDanglingReference() {
    String content = "BT /F1 12 Tf 72 720 Td (A page with a reference to nothing.) Tj ET" + (char) 10;
    List<String> objects =
        List.of(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Rotate 6 0 R /PieceInfo 9 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + content.length() + " >>" + (char) 10 + "stream" + (char) 10 + content + "endstream",
            "0");
    return handWritten(objects, "");
  }

  /** Objects numbered from 1 in list order, a classic xref table and a trailer with {@code trailerExtra}. */
  private static byte[] handWritten(List<String> objects, String trailerExtra) {
    StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
    List<Integer> offsets = new ArrayList<>();
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(pdf.length());
      pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
    }
    int xref = pdf.length();
    pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
    for (int offset : offsets) {
      pdf.append(String.format("%010d 00000 n \n", offset));
    }
    pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R").append(trailerExtra).append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /**
   * Untagged page whose content stream fills a path and then paints again with no current path
   * ({@code ... h f 0 0 0 rg f}), the shape pdfTeX leaves in some embedded figures: the second
   * {@code f} is not allowed in that state (ISO 32000-2 Figure 9). Rendering ignores it; a strict
   * content parser (the PAC engine) rejects the stream, and every tag placed in it.
   */
  public static byte[] untaggedWithPaintingOperatorWithoutPath() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      page.setContents(rawStream(doc,
          "BT /F1 12 Tf 72 720 Td (Text before the figure.) Tj ET\n"
              + "0 0 1 rg 100 300 m 200 300 l 200 400 l h f 0 0 0 rg f\n"
              + "BT /F1 12 Tf 72 200 Td (Text after the figure.) Tj ET\n"));
      finish(doc, 55L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page with valid painting ({@code 0 0 m f}, a filled triangle, a stroked line) next to
   * three painting operators that have no path: a bare {@code f} on the page, and a bare {@code S}
   * and {@code b*} in a form XObject that is drawn once and shows text (the pdfTeX figure shape).
   */
  public static byte[] untaggedWithStrayPaintOperatorsInPageAndForm() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = rawPage(doc);
      PDFormXObject figure = new PDFormXObject(doc);
      figure.setBBox(new PDRectangle(0, 0, 300, 100));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      figure.setResources(formResources);
      try (java.io.OutputStream os = figure.getContentStream().createOutputStream()) {
        os.write(("1 0 0 RG 0 0 m 200 0 l S S\nBT /F1 10 Tf 0 50 Td (Label inside the figure.) Tj ET\n0 1 0 rg 10 10 50 20 re f b*\n")
            .getBytes(StandardCharsets.US_ASCII));
      }
      page.getResources().put(COSName.getPDFName("Fx1"), figure);
      page.setContents(rawStream(doc,
          "BT /F1 12 Tf 72 720 Td (Text before the figure.) Tj ET\n"
              + "0 0 m f\n"
              + "0 0 1 rg 100 300 m 200 300 l 200 400 l h f 0 0 0 rg f\n"
              + "q 1 0 0 1 72 500 cm /Fx1 Do Q\n"
              + "BT /F1 12 Tf 72 200 Td (Text after the figure.) Tj ET\n"));
      finish(doc, 57L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page and once-drawn form each holding a painting operator with no path and a stray
   * operand in front of it ({@code 0 0 0 rg 1 f q ... Q}): dropping the {@code f} would hand the
   * {@code 1} to the next operator, so the tagger must refuse rather than remove it.
   */
  public static byte[] untaggedWithStrayPaintOperatorWithOperands() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = rawPage(doc);
      PDFormXObject figure = new PDFormXObject(doc);
      figure.setBBox(new PDRectangle(0, 0, 300, 100));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      figure.setResources(formResources);
      try (java.io.OutputStream os = figure.getContentStream().createOutputStream()) {
        os.write("BT /F1 10 Tf 0 50 Td (Label inside the figure.) Tj ET\n2 S\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.getResources().put(COSName.getPDFName("Fx1"), figure);
      page.setContents(rawStream(doc,
          "BT /F1 12 Tf 72 720 Td (Text before.) Tj ET\n"
              + "0 0 0 rg 1 f q Q\n"
              + "q 1 0 0 1 72 500 cm /Fx1 Do Q\n"
              + "BT /F1 12 Tf 72 200 Td (Text after.) Tj ET\n"));
      finish(doc, 58L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page with a no-op {@code n} while no path is open ({@code q ... S Q n}), the shape
   * pgf/TikZ (beamer) writes everywhere. Unlike a stray fill, the PAC engine accepts it, so the
   * tagger must too.
   */
  public static byte[] untaggedWithEndPathWithoutPath() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      page.setContents(rawStream(doc,
          "BT /F1 12 Tf 72 720 Td (Text before the drawing.) Tj ET\n"
              + "q 100 300 m 200 300 l S Q n\n"
              + "BT /F1 12 Tf 72 200 Td (Text after the drawing.) Tj ET\n"));
      finish(doc, 56L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page whose {@code /Contents} is a two-stream array: a text object and an existing
   * {@code /Span} block start in the first stream and end in the second.
   */
  public static byte[] untaggedWithContentArray() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      PDStream first = rawStream(doc, "/Span BMC BT /F1 12 Tf 72 720 Td (Half one ) Tj\n");
      PDStream second = rawStream(doc, "(and half two.) Tj ET EMC\nBT /F1 12 Tf 72 706 Td (Second line.) Tj ET\n");
      page.setContents(List.of(first, second));
      finish(doc, 49L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page with a clipping path that paints nothing ({@code re W n}), a 2x2 grey inline image
   * and a text object whose three show operators straddle a {@code q ... Q} pair.
   */
  public static byte[] untaggedWithInlineImageClipAndNestedState() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      ByteArrayOutputStream content = new ByteArrayOutputStream();
      content.writeBytes("72 400 300 300 re W n\nq 80 0 0 80 72 500 cm\nBI /W 2 /H 2 /CS /G /BPC 8 ID ".getBytes(StandardCharsets.US_ASCII));
      content.writeBytes(new byte[] {0x00, (byte) 0xff, (byte) 0x80, 0x40});
      content.writeBytes(" EI\nQ\nBT /F1 12 Tf 72 720 Td (One ) Tj q 0.5 g (Two ) Tj Q (Three.) Tj ET\n".getBytes(StandardCharsets.US_ASCII));
      PDStream stream = new PDStream(doc);
      try (java.io.OutputStream os = stream.createOutputStream()) {
        os.write(content.toByteArray());
      }
      page.setContents(stream);
      finish(doc, 50L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Three untagged pages: text, no {@code /Contents} at all, an empty content stream. */
  public static byte[] untaggedWithEmptyAndMissingContents() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage first = rawPage(doc);
      first.setContents(rawStream(doc, "BT /F1 12 Tf 72 720 Td (Only page one has text.) Tj ET\n"));
      doc.addPage(new PDPage(PDRectangle.A4));
      PDPage third = rawPage(doc);
      third.setContents(rawStream(doc, ""));
      finish(doc, 51L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged page drawing once a form XObject whose content stream is Flate-encoded with a PNG
   * predictor ({@code /DecodeParms << /Predictor 12 /Columns 1 >>}); the form shows text and an image.
   */
  public static byte[] untaggedWithPredictorForm() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDImageXObject image = LosslessFactory.createFromImage(doc, checkerboard(16));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      formResources.put(COSName.getPDFName("Im1"), image);
      byte[] formContent =
          "BT /F1 12 Tf 0 10 Td (Inside predictor form.) Tj ET\nq 40 0 0 40 0 30 cm /Im1 Do Q\n".getBytes(StandardCharsets.US_ASCII);
      // PNG "Up" predictor, one column: every row is [2, byte - previous byte].
      ByteArrayOutputStream predicted = new ByteArrayOutputStream();
      int previous = 0;
      for (byte b : formContent) {
        predicted.write(2);
        predicted.write((b - previous) & 0xff);
        previous = b;
      }
      java.util.zip.Deflater deflater = new java.util.zip.Deflater();
      deflater.setInput(predicted.toByteArray());
      deflater.finish();
      ByteArrayOutputStream deflated = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      while (!deflater.finished()) {
        deflated.write(buffer, 0, deflater.deflate(buffer));
      }
      deflater.end();
      COSStream formStream = doc.getDocument().createCOSStream();
      try (java.io.OutputStream os = formStream.createRawOutputStream()) {
        os.write(deflated.toByteArray());
      }
      formStream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
      COSDictionary parms = new COSDictionary();
      parms.setInt(COSName.PREDICTOR, 12);
      parms.setInt(COSName.COLUMNS, 1);
      formStream.setItem(COSName.DECODE_PARMS, parms);
      formStream.setItem(COSName.TYPE, COSName.XOBJECT);
      formStream.setItem(COSName.SUBTYPE, COSName.FORM);
      PDFormXObject form = new PDFormXObject(formStream);
      form.setBBox(new PDRectangle(0, 0, 300, 80));
      form.setResources(formResources);

      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        line(cs, font, 72, 720, "Page text above the predictor form.");
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(72, 500));
        cs.drawForm(form);
        cs.restoreGraphicsState();
      }
      finish(doc, 52L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged page whose content draws an XObject name that is not in the resources. */
  public static byte[] untaggedWithDanglingXObject() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      page.setContents(rawStream(doc, "q 80 0 0 80 72 500 cm /Im9 Do Q\nBT /F1 12 Tf 72 720 Td (Text next to a dangling XObject.) Tj ET\n"));
      finish(doc, 53L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged page drawing an XObject whose {@code /Subtype} PDFBox cannot load. */
  public static byte[] untaggedWithUnknownXObjectSubtype() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = rawPage(doc);
      COSStream odd = doc.getDocument().createCOSStream();
      try (java.io.OutputStream os = odd.createOutputStream()) {
        os.write("0 0 10 10 re f\n".getBytes(StandardCharsets.US_ASCII));
      }
      odd.setItem(COSName.TYPE, COSName.XOBJECT);
      odd.setItem(COSName.SUBTYPE, COSName.getPDFName("Foo"));
      COSDictionary xObjects = new COSDictionary();
      xObjects.setItem(COSName.getPDFName("Fx1"), odd);
      page.getResources().getCOSObject().setItem(COSName.XOBJECT, xObjects);
      page.setContents(rawStream(doc, "q 80 0 0 80 72 500 cm /Fx1 Do Q\nBT /F1 12 Tf 72 720 Td (Text next to an odd XObject.) Tj ET\n"));
      finish(doc, 54L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged fixture in the shape of an InDesign master-page item: one form XObject with text
   * ({@code BBox 0 0 300 20}) drawn at {@code 1 0 0 1 72 800 cm} on every page, and one body line
   * {@code "Body text on page N."} at (72, 700) per page.
   */
  public static byte[] untaggedWithSharedTextFormOnEveryPage(int pages) {
    return sharedForm("BT /F1 9 Tf 0 5 Td (Annual report 2025 - chapter 3) Tj ET\n", pages, true, 62L);
  }

  /** Untagged one-page fixture: the same text form drawn at (72, 600) and (72, 300), body line at (72, 700). */
  public static byte[] untaggedWithSharedTextFormAtTwoPositions() {
    return sharedForm("BT /F1 9 Tf 0 5 Td (Annual report 2025 - chapter 3) Tj ET\n", 1, false, 63L);
  }

  /** Untagged two-page fixture whose shared form already holds marked content with an MCID. */
  public static byte[] untaggedWithSharedFormHoldingMarkedContent() {
    return sharedForm("/Span <</MCID 0>> BDC BT /F1 9 Tf 0 5 Td (Label) Tj ET EMC\n", 2, true, 64L);
  }

  private static byte[] sharedForm(String formContent, int pages, boolean onEveryPage, long documentId) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(0, 0, 300, 20));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), font);
      form.setResources(formResources);
      try (java.io.OutputStream os = form.getContentStream().createOutputStream()) {
        os.write(formContent.getBytes(StandardCharsets.US_ASCII));
      }
      for (int p = 1; p <= pages; p++) {
        PDPage page = rawPage(doc);
        page.getResources().put(COSName.getPDFName("Fm1"), form);
        String body = "BT /F1 12 Tf 72 700 Td (Body text on page " + p + ".) Tj ET\n";
        String content = onEveryPage
            ? "q 1 0 0 1 72 800 cm /Fm1 Do Q\n" + body
            : body + "q 1 0 0 1 72 600 cm /Fm1 Do Q\nq 1 0 0 1 72 300 cm /Fm1 Do Q\n";
        page.setContents(rawStream(doc, content));
      }
      finish(doc, documentId);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String[] ORDINAL_WORDS = {"first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth"};

  /**
   * Untagged report of {@code pages} A4 pages: a running header {@code "Annual report
   * 2025"} (Helvetica 9 pt at 72, 800), a running footer {@code "Page N"} (9 pt at 290, 30) and two body
   * paragraphs per page (Helvetica 11 pt, two lines each at y 700/686 and 640/626), whose words differ
   * per page. Content order: header, paragraphs, footer.
   */
  public static byte[] untaggedReport(int pages) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        String word = ORDINAL_WORDS[i % ORDINAL_WORDS.length] + (i >= ORDINAL_WORDS.length ? " again" : "");
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          text(cs, font, 9, 72, 800, "Annual report 2025");
          text(cs, font, 11, 72, 700, "The " + word + " page opens with a paragraph");
          text(cs, font, 11, 72, 686, "that continues on a second line.");
          text(cs, font, 11, 72, 640, "Another paragraph on the " + word + " page");
          text(cs, font, 11, 72, 626, "ends the body text here.");
          text(cs, font, 9, 290, 30, "Page " + (i + 1));
        }
      }
      finish(doc, 70L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Column line size of the two-column fixtures: 20 pt line pitch stays within 1.5 × the font size. */
  private static final float COLUMN_FONT_SIZE = 14;

  /**
   * Untagged one-page A4 fixture: 6 left-column prose lines (x 72, y 700 … 600, step 20) and 6 right-column prose
   * lines (x 320, y 690 … 590: a baseline grid half a line off, so the sides are not rows), Helvetica 14 pt, each
   * line about 190 pt wide ("Left column, line N of the prose"), drawn in the content order L1, R1, L2, R2, …;
   * each column is one block.
   */
  public static byte[] untaggedTwoColumnsInterleaved() {
    return twoColumns(true, false, 71L);
  }

  /** As {@link #untaggedTwoColumnsInterleaved()}, content order L1 … L6, R1 … R6. */
  public static byte[] untaggedTwoColumnsInOrder() {
    return twoColumns(false, false, 72L);
  }

  /** As {@link #untaggedTwoColumnsInterleaved()} plus a long Helvetica 11 pt line from x 72 to about x 500 at y 580, across the gutter. */
  public static byte[] untaggedTwoColumnsWithStraddlingBlock() {
    return twoColumns(true, true, 73L);
  }

  private static byte[] twoColumns(boolean interleaved, boolean straddling, long documentId) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        List<float[]> order = new ArrayList<>(); // {column, line}
        for (int n = 0; n < 6; n++) {
          if (interleaved) {
            order.add(new float[] {0, n});
            order.add(new float[] {1, n});
          }
        }
        if (!interleaved) {
          for (int c = 0; c < 2; c++) {
            for (int n = 0; n < 6; n++) {
              order.add(new float[] {c, n});
            }
          }
        }
        for (float[] entry : order) {
          int column = (int) entry[0];
          int n = (int) entry[1];
          text(cs, font, COLUMN_FONT_SIZE, column == 0 ? 72 : 320, (column == 0 ? 700 : 690) - 20 * n, columnLine(column, n));
        }
        if (straddling) {
          text(cs, font, 11, 72, 580, "A long line of body text that runs across both columns and the gutter between them.");
        }
      }
      finish(doc, documentId);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String columnLine(int column, int n) {
    return (column == 0 ? "Left" : "Right") + " column, line " + (n + 1) + " of the prose";
  }

  /**
   * Untagged one-page fixture: two columns as {@link #untaggedTwoColumnsInterleaved()} but each of two paragraphs of
   * three lines (left y 700/680/660 and 600/580/560, right 10 pt lower), so the paragraph gaps of both columns line up
   * at the same height. Content order: {@code interleaved} L1, R1, L2, R2, …, else L1 … L6, R1 … R6.
   */
  public static byte[] untaggedTwoColumnsWithAlignedParagraphGaps(boolean interleaved) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      float[] ys = {700, 680, 660, 600, 580, 560};
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int k = 0; k < 12; k++) {
          int column = interleaved ? k % 2 : k / 6;
          int n = interleaved ? k / 2 : k % 6;
          text(cs, font, COLUMN_FONT_SIZE, column == 0 ? 72 : 320, ys[n] - (column == 0 ? 0 : 10), columnLine(column, n));
        }
      }
      finish(doc, interleaved ? 75L : 76L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page fixture: a two-line paragraph (Helvetica 11 pt at 72, 700 and 72, 686) whose first line holds a
   * footnote mark "1" (7 pt, raised 4 pt) in the middle, drawn in the order: first part of line 1, the mark, the rest
   * of line 1, line 2.
   */
  public static byte[] untaggedParagraphWithSuperscript() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      String first = "The study found a clear effect";
      float x = 72 + font.getStringWidth(first) / 1000 * 11;
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        text(cs, font, 11, 72, 700, first);
        text(cs, font, 7, x, 704, "1");
        text(cs, font, 11, x + font.getStringWidth("1") / 1000 * 7, 700, " in every group that was tested,");
        text(cs, font, 11, 72, 686, "and the effect lasted for the whole year.");
      }
      finish(doc, 77L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page label/value form: {@code rows} rows of a label (x 72) and a value (x 200) on the same baseline
   * (y 700, step 20, Helvetica 11 pt), drawn row by row. Two rows: Name/Date; four rows: an invoice header.
   */
  public static byte[] untaggedLabelValueForm(int rows) {
    String[][] pairs = {{"Name:", "Jane Doe"}, {"Date:", "2026-09-28"}, {"Invoice number:", "INV-2041"}, {"Amount due:", "1,250.00 EUR"}};
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int r = 0; r < rows; r++) {
          text(cs, font, 11, 72, 700 - 20 * r, pairs[r][0]);
          text(cs, font, 11, 200, 700 - 20 * r, pairs[r][1]);
        }
      }
      finish(doc, 78L + rows);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged {@code pages}-page fixture: each page a body paragraph (11 pt, two lines at 72, 700/686, words differ per
   * page), the line {@code repeated} (with the page number appended when {@code numbered}) at (72, 650) on every page,
   * and a closing line at (72, 610).
   */
  public static byte[] untaggedRepeatedBodyLine(int pages, String repeated, boolean numbered) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        String word = ORDINAL_WORDS[i % ORDINAL_WORDS.length];
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          text(cs, font, 11, 72, 700, "The " + word + " page opens with a paragraph");
          text(cs, font, 11, 72, 686, "that continues on a second line.");
          text(cs, font, 11, 72, 650, numbered ? repeated + " " + (i + 1) : repeated);
          text(cs, font, 11, 72, 610, "Closing text of the " + word + " page.");
        }
      }
      finish(doc, 90L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged 6-page fixture: a chapter header in the top band (9 pt at 72, 810), "Chapter one" on pages 1-3 and
   * "Chapter two" on pages 4-6 (each on half of the pages, below the running-artifact share, but on three pages, which
   * the basic tagger's pagination rule counts), and a body paragraph whose words differ per page.
   */
  public static byte[] untaggedChapterHeaders() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int i = 0; i < 6; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        String word = ORDINAL_WORDS[i];
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          text(cs, font, 9, 72, 810, i < 3 ? "Chapter one" : "Chapter two");
          text(cs, font, 11, 72, 700, "The " + word + " page opens with a paragraph");
          text(cs, font, 11, 72, 686, "that continues on a second line.");
        }
      }
      finish(doc, 91L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged one-page fixture: a Helvetica-Bold 12 pt line at (72, 720), then a Helvetica 12 pt line at (72, 700). */
  public static byte[] untaggedBoldAndRegularLine() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        text(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12, 72, 720, "A bold line");
        text(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12, 72, 700, "A regular line");
      }
      finish(doc, 74L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page article in Helvetica: a 20 pt title, a 14 pt heading, paragraphs, a bullet list whose marker and text are
   * separate operators, an ordered list, a second heading and a closing paragraph. Lines within a block are 14 pt apart, blocks 30 pt.
   */
  public static byte[] untaggedStructuredArticle() {
    return structuredArticle(false);
  }

  /**
   * {@link #untaggedStructuredArticle()} with producer tags: {@code Document > H1, H2, P, P, L > LI > (Lbl, LBody) ×3, P,
   * L > LI > LBody ×3, H2, P}, one MCID per leaf (the bullet in {@code Lbl}, its text in {@code LBody}), ParentTree,
   * /Lang en, Info title.
   */
  public static byte[] taggedStructuredArticle() {
    return structuredArticle(true);
  }

  private static byte[] structuredArticle(boolean tagged) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      COSArray parents = new COSArray();
      int[] mcid = {0};
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        // leaf(parent, role, size, x, y, text): one marked sequence (tagged) around one text-showing operator.
        java.util.function.Function<Object[], PDStructureElement> leaf = a -> {
          PDStructureElement parent = (PDStructureElement) a[0];
          String role = (String) a[1];
          try {
            PDStructureElement element = null;
            if (tagged) {
              COSDictionary props = new COSDictionary();
              props.setInt(COSName.MCID, mcid[0]);
              cs.beginMarkedContent(COSName.getPDFName(role), PDPropertyList.create(props));
              element = new PDStructureElement(role, parent);
              element.setPage(page);
              addMcid(element, mcid[0]);
              parent.appendKid(element);
              parents.add(element.getCOSObject());
              mcid[0]++;
            }
            text(cs, font, (Float) a[2], (Float) a[3], (Float) a[4], (String) a[5]);
            if (tagged) {
              cs.endMarkedContent();
            }
            return element;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
        java.util.function.BiFunction<PDStructureElement, String, PDStructureElement> group = (parent, role) -> {
          PDStructureElement element = new PDStructureElement(role, parent);
          element.setPage(page);
          parent.appendKid(element);
          return element;
        };
        leaf.apply(new Object[] {document, "H1", 20f, 72f, 760f, "Annual review"});
        leaf.apply(new Object[] {document, "H2", 14f, 72f, 720f, "1 Results"});
        // A paragraph of two lines is one element with two MCIDs: draw both lines inside one sequence per line.
        paragraph(cs, font, tagged, document, page, parents, mcid, 690, "The results of the year were measured in all", "regions and are summarised in this review.");
        paragraph(cs, font, tagged, document, page, parents, mcid, 646, "Every region reported its figures on time", "and the totals were checked twice.");
        PDStructureElement bullets = tagged ? group.apply(document, "L") : null;
        String[] fruit = {"Apples grew well", "Pears grew slowly", "Plums failed"};
        for (int i = 0; i < fruit.length; i++) {
          PDStructureElement item = tagged ? group.apply(bullets, "LI") : null;
          float y = 602 - 14 * i;
          leaf.apply(new Object[] {item, "Lbl", 11f, 72f, y, "•"});
          leaf.apply(new Object[] {item, "LBody", 11f, 84f, y, fruit[i]});
        }
        leaf.apply(new Object[] {document, "P", 11f, 72f, 544f, "One paragraph sits between the lists."});
        PDStructureElement ordered = tagged ? group.apply(document, "L") : null;
        String[] steps = {"1. One", "2. Two", "3. Three"};
        for (int i = 0; i < steps.length; i++) {
          PDStructureElement item = tagged ? group.apply(ordered, "LI") : null;
          leaf.apply(new Object[] {item, "LBody", 11f, 72f, 514f - 14 * i, steps[i]});
        }
        leaf.apply(new Object[] {document, "H2", 14f, 72f, 450f, "2 Outlook"});
        leaf.apply(new Object[] {document, "P", 11f, 72f, 420f, "The outlook paragraph ends the article."});
      }
      if (tagged) {
        COSArray nums = new COSArray();
        nums.add(COSInteger.get(0));
        nums.add(parents);
        page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
        COSDictionary parentTree = new COSDictionary();
        parentTree.setItem(COSName.NUMS, nums);
        root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
        root.setParentTreeNextKey(1);
        doc.getDocumentCatalog().setStructureTreeRoot(root);
        PDMarkInfo markInfo = new PDMarkInfo();
        markInfo.setMarked(true);
        doc.getDocumentCatalog().setMarkInfo(markInfo);
        doc.getDocumentCatalog().setLanguage("en");
        doc.getDocumentInformation().setTitle("Structured article fixture");
      }
      finish(doc, tagged ? 94L : 93L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page ruled table, Helvetica 10 pt: caption {@code "Table 1. Revenue"} at (72, 712); four
   * horizontal (y 700/680/660/640, x 72-522) and four vertical (x 72/222/372/522, y 640-700) rulings, each a 0.5 pt filled
   * rectangle of its own; 3 × 3 cells, the header row bold {@code "Region"}, {@code "2024"}, {@code "2025"}, body rows
   * {@code "North"/"10"/"12"} and {@code "South"/"8"/"9"}, each cell text at the cell's x + 4 and top − 14.
   */
  public static byte[] untaggedRuledTable() {
    return ruledTable(false, 0);
  }

  /** {@link #untaggedRuledTable()} without the last body cell ({@code "9"}): an empty cell in a ruled grid. */
  public static byte[] untaggedRuledTableWithEmptyCell() {
    return ruledTable(false, 1);
  }

  /**
   * {@link #untaggedRuledTable()} with a fourth grid row (a fifth horizontal ruling at y 620, verticals down to 620) that
   * holds only a space run at (76, 626): a row without visible text whose white space belongs to the table.
   */
  public static byte[] untaggedRuledTableWithBlankRow() {
    return ruledTable(false, 2);
  }

  /**
   * {@link #untaggedRuledTable()} with producer tags as Word writes them: {@code Document > P} (the caption), {@code Table >
   * THead > TR > TH ×3, TBody > TR ×2 > TD ×3}, one MCID per leaf, rulings as {@code /Artifact}, ParentTree, /Lang en, Info
   * title.
   */
  public static byte[] taggedRuledTable() {
    return ruledTable(true, 0);
  }

  /**
   * {@link #taggedRuledTable()} without a {@code THead}: {@code Table > TBody > TR ×3 > TD ×3} (the header row's cells are
   * {@code TD} too), the shape the PAC engine reports as "TBody" (a table body without a head).
   */
  public static byte[] taggedRuledTableWithoutHead() {
    return ruledTable(true, 3);
  }

  /**
   * Untagged one-page article in the embedded Liberation Sans that ships with PDFBox ({@code PDType0Font}, a
   * subset with ToUnicode): {@code "Quarterly summary"} 18 pt at (72, 740); two 11 pt paragraphs of two lines each (14 pt
   * apart, 30 pt between blocks); no image, no /Lang, no Info title.
   */
  public static byte[] untaggedEmbeddedFontArticle() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      org.apache.pdfbox.pdmodel.font.PDType0Font font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      String[][] blocks = {
        {"Revenue grew in every region during the quarter,", "led by strong demand for the new product line."},
        {"Costs stayed flat, so the operating margin improved", "and the outlook for the next quarter is stable."}};
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        embeddedText(cs, font, 18, 72, 740, "Quarterly summary");
        float y = 710;
        for (String[] block : blocks) {
          for (String line : block) {
            embeddedText(cs, font, 11, 72, y, line);
            y -= 14;
          }
          y -= 16;
        }
      }
      finish(doc, 110L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * An XMP packet whose {@code dc:title} is an empty x-default entry ({@code <rdf:li xml:lang="x-default"/>}), the
   * shape PAC reported on un-charter-aspose ("Title in XMP metadata"); it has {@code dc:format} and nothing else.
   */
  public static final String EMPTY_DC_TITLE_PACKET = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
      + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
      + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:format>application/pdf</dc:format>"
      + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\"/></rdf:Alt></dc:title>"
      + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

  /** {@code pdf} with {@code packet} as its catalog /Metadata. */
  public static byte[] withXmpPacket(byte[] pdf, String packet) {
    try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.getDocumentCatalog().setMetadata(
          new org.apache.pdfbox.pdmodel.common.PDMetadata(doc, new ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8))));
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged one-page "scan": a single page-sized image, no text layer. */
  public static byte[] untaggedImageOnlyScan() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.drawImage(LosslessFactory.createFromImage(doc, checkerboard(40)), 36, 36, 523, 770);
      }
      finish(doc, 111L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Untagged one-page document whose only content is filled vector outlines (outlined text), no text or image. */
  public static byte[] untaggedOutlinesOnly() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int letter = 0; letter < 5; letter++) {
          float x = 72 + letter * 30;
          cs.moveTo(x, 700);
          cs.lineTo(x + 10, 730);
          cs.lineTo(x + 20, 700);
          cs.lineTo(x + 15, 700);
          cs.lineTo(x + 10, 715);
          cs.lineTo(x + 5, 700);
          cs.closePath();
          cs.fill();
        }
      }
      finish(doc, 112L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void embeddedText(PDPageContentStream cs, org.apache.pdfbox.pdmodel.font.PDFont font, float size, float x, float y, String text)
      throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText(text);
    cs.endText();
  }

  /** {@code variant}: 0 as described, 1 the last body cell empty, 2 an extra row holding only a space run, 3 (tagged) no THead. */
  private static byte[] ruledTable(boolean tagged, int variant) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      COSArray parents = new COSArray();
      int[] mcid = {0};
      java.util.function.BiFunction<PDStructureElement, String, PDStructureElement> group = (parent, role) -> {
        PDStructureElement element = new PDStructureElement(role, parent);
        element.setPage(page);
        parent.appendKid(element);
        return element;
      };
      PDStructureElement caption = tagged ? group.apply(document, "P") : null;
      PDStructureElement table = tagged ? group.apply(document, "Table") : null;
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        taggedText(cs, regular, 10, 72, 712, "Table 1. Revenue", caption, page, parents, mcid);
        if (tagged) {
          cs.beginMarkedContent(COSName.ARTIFACT);
        }
        float[] ys = variant == 2 ? new float[] {700, 680, 660, 640, 620} : new float[] {700, 680, 660, 640};
        float[] xs = {72, 222, 372, 522};
        float bottom = ys[ys.length - 1];
        for (float y : ys) {
          cs.addRect(72, y - 0.25f, 450, 0.5f);
          cs.fill();
        }
        for (float x : xs) {
          cs.addRect(x - 0.25f, bottom, 0.5f, 700 - bottom);
          cs.fill();
        }
        if (tagged) {
          cs.endMarkedContent();
        }
        String[][] cells = {{"Region", "2024", "2025"}, {"North", "10", "12"}, {"South", "8", "9"}};
        PDStructureElement head = tagged && variant != 3 ? group.apply(table, "THead") : null;
        PDStructureElement body = tagged ? group.apply(table, "TBody") : null;
        for (int r = 0; r < 3; r++) {
          PDStructureElement row = tagged ? group.apply(r == 0 && head != null ? head : body, "TR") : null;
          for (int c = 0; c < 3; c++) {
            PDStructureElement cell = tagged ? group.apply(row, r == 0 && head != null ? "TH" : "TD") : null;
            if (variant == 1 && r == 2 && c == 2) {
              continue;
            }
            taggedText(cs, r == 0 ? bold : regular, 10, xs[c] + 4, ys[r] - 14, cells[r][c], cell, page, parents, mcid);
          }
        }
        if (variant == 2) {
          text(cs, regular, 10, 76, 626, " ");
        }
      }
      if (tagged) {
        tagFinish(doc, root, page, parents, "Ruled table fixture");
      }
      finish(doc, tagged ? 96L + variant : 95L + 4L * variant);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page aligned table, Helvetica 11 pt, no rulings, no bold: 4 rows × 3 columns of separate text
   * runs at x 72/200/330, baselines 600/584/568/552 ({@code "Apples" "12" "40%"}, {@code "Pears" "9" "30%"}, {@code
   * "Plums" "6" "20%"}, {@code "Cherries" "3" "10%"}).
   */
  public static byte[] untaggedAlignedTable() {
    return alignedTable(false);
  }

  /**
   * {@link #untaggedAlignedTable()}, except that row 2 draws {@code "Alpha      Beta"} as one run from x 72 whose word
   * spacing puts {@code "Beta"} at x 200 (the run spans columns 1-2), then {@code "30%"} at 330.
   */
  public static byte[] untaggedTableWithMergedRun() {
    return alignedTable(true);
  }

  private static byte[] alignedTable(boolean merged) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      String[][] rows = {{"Apples", "12", "40%"}, {"Pears", "9", "30%"}, {"Plums", "6", "20%"}, {"Cherries", "3", "10%"}};
      float[] xs = {72, 200, 330};
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int r = 0; r < rows.length; r++) {
          float y = 600 - 16 * r;
          if (merged && r == 1) {
            String run = "Alpha      Beta";
            float natural = font.getStringWidth("Alpha      ") / 1000f * 11;
            cs.beginText();
            cs.setFont(font, 11);
            cs.setWordSpacing((xs[1] - xs[0] - natural) / 6f);
            cs.newLineAtOffset(xs[0], y);
            cs.showText(run);
            cs.setWordSpacing(0);
            cs.endText();
            text(cs, font, 11, xs[2], y, rows[r][2]);
            continue;
          }
          for (int c = 0; c < 3; c++) {
            text(cs, font, 11, xs[c], y, rows[r][c]);
          }
        }
      }
      finish(doc, merged ? 98L : 97L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One text-showing operator; tagged ({@code element} not null): inside a marked sequence whose MCID the element lists. */
  private static void taggedText(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text, PDStructureElement element,
      PDPage page, COSArray parents, int[] mcid) throws IOException {
    if (element != null) {
      COSDictionary props = new COSDictionary();
      props.setInt(COSName.MCID, mcid[0]);
      cs.beginMarkedContent(COSName.getPDFName(element.getStructureType()), PDPropertyList.create(props));
      addMcid(element, mcid[0]);
      parents.add(element.getCOSObject());
      mcid[0]++;
    }
    text(cs, font, size, x, y, text);
    if (element != null) {
      cs.endMarkedContent();
    }
  }

  /** ParentTree (one page, key 0), MarkInfo, /Lang en and the Info title of a tagged one-page fixture. */
  private static void tagFinish(PDDocument doc, PDStructureTreeRoot root, PDPage page, COSArray parents, String title) {
    COSArray nums = new COSArray();
    nums.add(COSInteger.get(0));
    nums.add(parents);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(1);
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    PDMarkInfo markInfo = new PDMarkInfo();
    markInfo.setMarked(true);
    doc.getDocumentCatalog().setMarkInfo(markInfo);
    doc.getDocumentCatalog().setLanguage("en");
    doc.getDocumentInformation().setTitle(title);
  }

  /** Appends an MCID kid to the element's /K (an integer, or an array once there are two). */
  private static void addMcid(PDStructureElement element, int mcid) {
    COSBase k = element.getCOSObject().getDictionaryObject(COSName.K);
    if (k == null) {
      element.getCOSObject().setItem(COSName.K, COSInteger.get(mcid));
    } else if (k instanceof COSArray array) {
      array.add(COSInteger.get(mcid));
    } else {
      COSArray array = new COSArray();
      array.add(k);
      array.add(COSInteger.get(mcid));
      element.getCOSObject().setItem(COSName.K, array);
    }
  }

  /** Two 11 pt lines at x 72 (y and y − 14); tagged: one P element whose two MCIDs are the two lines. */
  private static void paragraph(PDPageContentStream cs, PDType1Font font, boolean tagged, PDStructureElement document, PDPage page, COSArray parents,
      int[] mcid, float y, String first, String second) throws IOException {
    PDStructureElement element = null;
    if (tagged) {
      element = new PDStructureElement("P", document);
      element.setPage(page);
      document.appendKid(element);
    }
    String[] lines = {first, second};
    for (int i = 0; i < 2; i++) {
      if (tagged) {
        COSDictionary props = new COSDictionary();
        props.setInt(COSName.MCID, mcid[0]);
        cs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
        addMcid(element, mcid[0]);
        parents.add(element.getCOSObject());
        mcid[0]++;
      }
      text(cs, font, 11, 72, y - 14 * i, lines[i]);
      if (tagged) {
        cs.endMarkedContent();
      }
    }
  }

  private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text) throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText(text);
    cs.endText();
  }

  /**
   * Untagged {@code pages}-page fixture: a body line (11 pt at 72, 700, words differ per page) and a footer "Page footer"
   * (9 pt at 290, 30) that already carries marked content with MCID 0 on every page (an adopted block).
   */
  public static byte[] untaggedWithMarkedFooterOnEveryPage(int pages) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        PDPage page = rawPage(doc);
        page.setContents(rawStream(doc, "BT /F1 11 Tf 72 700 Td (The " + ORDINAL_WORDS[i % ORDINAL_WORDS.length] + " page body.) Tj ET\n"
            + "/P <</MCID 0>> BDC BT /F1 9 Tf 290 30 Td (Page footer) Tj ET EMC\n"));
      }
      finish(doc, 92L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document showing {@code codes} as one hex string in non-embedded 12 pt Helvetica. The Type 1 font has
   * {@code /Encoding} with {@code /Differences} built from {@code differences} (code to glyph name); a non-null
   * {@code toUnicodeOrNull} is written verbatim as its ToUnicode stream.
   */
  public static byte[] untaggedType1WithDifferences(Map<Integer, String> differences, byte[] codes, String toUnicodeOrNull) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary resources = fontResources(type1WithDifferences(doc, differences, toUnicodeOrNull));
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(resources));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <" + HexFormat.of().formatHex(codes) + "> Tj ET\n"));
      finish(doc, 93L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * As {@link #untaggedType1WithDifferences} with the glyphs split over the page and a form XObject:
   * the page shows {@code <41>} at x 72, draws form {@code /Fm1} (its own resources, the same font)
   * which shows {@code <42>} at x 120, then shows {@code <43>} at x 200. Content order: A, B, C.
   */
  public static byte[] untaggedType1WithDifferencesInForm(Map<Integer, String> differences, String toUnicodeOrNull) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary font = type1WithDifferences(doc, differences, toUnicodeOrNull);
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(0, 0, 595, 842));
      form.setResources(new PDResources(fontResources(font)));
      try (java.io.OutputStream os = form.getContentStream().createOutputStream()) {
        os.write("BT /F1 12 Tf 120 700 Td <42> Tj ET\n".getBytes(StandardCharsets.US_ASCII));
      }
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources pageResources = new PDResources(fontResources(font));
      pageResources.put(COSName.getPDFName("Fm1"), form);
      page.setResources(pageResources);
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <41> Tj ET\n/Fm1 Do\nBT /F1 12 Tf 200 700 Td <43> Tj ET\n"));
      finish(doc, 94L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document with a Type 3 font {@code /F1} (Ghostscript-style names: code 65
   * {@code /a0}, code 66 {@code /a1}; each glyph a filled square, width 600) showing {@code <4142>}
   * at 12 pt; {@code toUnicodeOrNull}, when non-null, is written verbatim as the font's ToUnicode.
   */
  public static byte[] untaggedType3WithNamedGlyphs(String toUnicodeOrNull) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary font = new COSDictionary();
      font.setItem(COSName.TYPE, COSName.FONT);
      font.setItem(COSName.SUBTYPE, COSName.TYPE3);
      font.setItem(COSName.FONT_BBOX, numbers(0, 0, 600, 600));
      font.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
      COSDictionary procs = new COSDictionary();
      for (String name : List.of("a0", "a1")) {
        procs.setItem(COSName.getPDFName(name), rawStream(doc, "600 0 0 0 500 500 d1 0 0 500 500 re f\n").getCOSObject());
      }
      font.setItem(COSName.CHAR_PROCS, procs);
      COSDictionary encoding = new COSDictionary();
      encoding.setItem(COSName.TYPE, COSName.ENCODING);
      COSArray differences = new COSArray();
      differences.add(COSInteger.get(65));
      differences.add(COSName.getPDFName("a0"));
      differences.add(COSName.getPDFName("a1"));
      encoding.setItem(COSName.DIFFERENCES, differences);
      font.setItem(COSName.ENCODING, encoding);
      font.setInt(COSName.FIRST_CHAR, 65);
      font.setInt(COSName.LAST_CHAR, 66);
      font.setItem(COSName.WIDTHS, numbers(600, 600));
      if (toUnicodeOrNull != null) {
        font.setItem(COSName.TO_UNICODE, rawStream(doc, toUnicodeOrNull));
      }
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(fontResources(font)));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <4142> Tj ET\n"));
      finish(doc, 95L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document showing the two-byte {@code codes} at 12 pt in fully embedded
   * LiberationSans (Type 0, Identity-H, CIDFontType2 with {@code /CIDToGIDMap /Identity}, so a code
   * is the glyph id: 36 = A, 37 = B, 1 = {@code .null}); the ToUnicode PDFBox writes is replaced by
   * {@code toUnicode} verbatim.
   */
  public static byte[] untaggedType0LiberationSans(byte[] codes, String toUnicode) {
    try (PDDocument doc = new PDDocument();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.font.PDType0Font font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, false);
      font.getCOSObject().setItem(COSName.TO_UNICODE, rawStream(doc, toUnicode));
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), font);
      page.setResources(resources);
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <" + HexFormat.of().formatHex(codes) + "> Tj ET\n"));
      finish(doc, 96L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document showing "AB" at 12 pt in non-embedded Helvetica whose Type 1
   * dictionary has no {@code /Encoding} entry (the font's built-in encoding applies, as in arxiv's
   * figure fonts) and no ToUnicode.
   */
  public static byte[] untaggedStandard14WithoutEncoding() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary font = new COSDictionary();
      font.setItem(COSName.TYPE, COSName.FONT);
      font.setItem(COSName.SUBTYPE, COSName.TYPE1);
      font.setName(COSName.BASE_FONT, "Helvetica");
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(fontResources(font)));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td (AB) Tj ET\n"));
      finish(doc, 97L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document with a Type 3 font that has no {@code /Name} and no {@code /BaseFont}
   * ({@code /FontMatrix [0.001 0 0 0.001 0 0]}, {@code /Differences [0 /a0 /a1]}, each glyph a
   * 500&times;500 square) showing codes 0 and 1 at 12 pt; no ToUnicode.
   */
  public static byte[] untaggedType3WithMeaninglessNames() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary font = new COSDictionary();
      font.setItem(COSName.TYPE, COSName.FONT);
      font.setItem(COSName.SUBTYPE, COSName.TYPE3);
      font.setItem(COSName.FONT_BBOX, numbers(0, 0, 500, 500));
      font.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
      COSDictionary procs = new COSDictionary();
      for (String name : List.of("a0", "a1")) {
        procs.setItem(COSName.getPDFName(name), rawStream(doc, "600 0 0 0 500 500 d1 0 0 500 500 re f\n").getCOSObject());
      }
      font.setItem(COSName.CHAR_PROCS, procs);
      COSDictionary encoding = new COSDictionary();
      encoding.setItem(COSName.TYPE, COSName.ENCODING);
      COSArray differences = new COSArray();
      differences.add(COSInteger.get(0));
      differences.add(COSName.getPDFName("a0"));
      differences.add(COSName.getPDFName("a1"));
      encoding.setItem(COSName.DIFFERENCES, differences);
      font.setItem(COSName.ENCODING, encoding);
      font.setInt(COSName.FIRST_CHAR, 0);
      font.setInt(COSName.LAST_CHAR, 1);
      font.setItem(COSName.WIDTHS, numbers(600, 600));
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(fontResources(font)));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <0001> Tj ET\n"));
      finish(doc, 98L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page document with the bundled LiberationSans patched into the shape of Word's symbolic subsets: {@code post}
   * version 3.0, platform-0 {@code cmap} records rewritten to (1,0) and (3,1) to (3,0). With {@code subset} it is first reduced to the
   * code points of {@code text}. Embedded as {@code /FontFile2} in a symbolic {@code /TrueType} dictionary without {@code /Encoding}
   * or ToUnicode; the page shows {@code text} as single-byte ASCII codes.
   */
  public static byte[] symbolicTrueTypeWithoutUnicodeTables(String text, boolean subset) {
    try (PDDocument doc = new PDDocument();
        InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] program = in.readAllBytes();
      if (subset) {
        try (TrueTypeFont ttf =
            new TTFParser().parse(new RandomAccessReadBuffer(program))) {
          TTFSubsetter subsetter = new TTFSubsetter(ttf);
          text.codePoints().forEach(subsetter::add);
          ByteArrayOutputStream sub = new ByteArrayOutputStream();
          subsetter.writeToStream(sub);
          program = sub.toByteArray();
        }
      }
      patchWordSymbolic(program);
      int[] widths = new int[95];
      try (TrueTypeFont ttf =
          new TTFParser().parse(new RandomAccessReadBuffer(program))) {
        CmapSubtable symbol = ttf.getCmap().getSubtable(3, 0);
        float unitsPerEm = ttf.getUnitsPerEm();
        for (int code = 32; code <= 126; code++) {
          int gid = symbol.getGlyphId(code);
          widths[code - 32] = Math.round(ttf.getAdvanceWidth(gid) * 1000f / unitsPerEm);
        }
      }
      PDStream file = new PDStream(doc, new ByteArrayInputStream(program), COSName.FLATE_DECODE);
      file.getCOSObject().setInt(COSName.LENGTH1, program.length);
      COSDictionary descriptor = new COSDictionary();
      descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
      descriptor.setName(COSName.FONT_NAME, "ABCDEF+LiberationSans");
      descriptor.setInt(COSName.FLAGS, 4);
      descriptor.setItem(COSName.FONT_BBOX, numbers(-203, -303, 1050, 910));
      descriptor.setInt(COSName.ITALIC_ANGLE, 0);
      descriptor.setInt(COSName.ASCENT, 905);
      descriptor.setInt(COSName.DESCENT, -212);
      descriptor.setInt(COSName.CAP_HEIGHT, 716);
      descriptor.setInt(COSName.STEM_V, 80);
      descriptor.setItem(COSName.FONT_FILE2, file);
      COSDictionary font = new COSDictionary();
      font.setItem(COSName.TYPE, COSName.FONT);
      font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
      font.setName(COSName.BASE_FONT, "ABCDEF+LiberationSans");
      font.setInt(COSName.FIRST_CHAR, 32);
      font.setInt(COSName.LAST_CHAR, 126);
      COSArray widthArray = new COSArray();
      for (int w : widths) {
        widthArray.add(COSInteger.get(w));
      }
      font.setItem(COSName.WIDTHS, widthArray);
      font.setItem(COSName.FONT_DESC, descriptor);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(fontResources(font)));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <" + HexFormat.of().formatHex(text.getBytes(StandardCharsets.US_ASCII)) + "> Tj ET\n"));
      finish(doc, 99L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** In place: {@code post} version 3.0; cmap records platform 0 → (1,0), (3,1) → (3,0). Tables located by tag. */
  private static void patchWordSymbolic(byte[] program) {
    ByteBuffer b = ByteBuffer.wrap(program);
    int tables = b.getShort(4) & 0xFFFF;
    for (int i = 0; i < tables; i++) {
      int record = 12 + 16 * i;
      String tag = new String(program, record, 4, StandardCharsets.US_ASCII);
      int offset = b.getInt(record + 8);
      if (tag.equals("post")) {
        b.putInt(offset, 0x00030000);
      } else if (tag.equals("cmap")) {
        int subtables = b.getShort(offset + 2) & 0xFFFF;
        for (int s = 0; s < subtables; s++) {
          int at = offset + 4 + 8 * s;
          int platform = b.getShort(at) & 0xFFFF;
          int encoding = b.getShort(at + 2) & 0xFFFF;
          if (platform == 0) {
            b.putShort(at, (short) 1);
            b.putShort(at + 2, (short) 0);
          } else if (platform == 3 && encoding == 1) {
            b.putShort(at + 2, (short) 0);
          }
        }
      }
    }
  }

  /**
   * {@link #untaggedType0LiberationSans} showing "AB" (codes 36, 37) and the {@code .null} glyph
   * (code 1) as raw two-byte codes, with a ToUnicode that maps only the code of "A".
   */
  public static byte[] type0WithPartialToUnicode() {
    StringBuilder cmap = new StringBuilder();
    cmap.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        .append("1 beginbfchar\n<0024> <0041>\nendbfchar\n")
        .append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
    return untaggedType0LiberationSans(new byte[] {0, 36, 0, 37, 0, 1}, cmap.toString());
  }

  /**
   * Untagged one-page document with two Type 1 Helvetica dictionaries sharing one ToUnicode stream
   * object that maps only code 65 to "A": {@code /F1} (WinAnsi, no Differences) shows "A",
   * {@code /F2} (WinAnsi with {@code /Differences [66 /f_f]}) shows "AB".
   */
  public static byte[] untaggedTwoFontsSharingToUnicode() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDStream shared = rawStream(doc, "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
          + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
          + "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n1 beginbfchar\n<41> <0041>\nendbfchar\n"
          + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
      COSDictionary f1 = type1WithDifferences(doc, Map.of(), null);
      COSDictionary f2 = type1WithDifferences(doc, Map.of(66, "f_f"), null);
      f1.setItem(COSName.TO_UNICODE, shared);
      f2.setItem(COSName.TO_UNICODE, shared);
      COSDictionary fonts = new COSDictionary();
      fonts.setItem(COSName.getPDFName("F1"), f1);
      fonts.setItem(COSName.getPDFName("F2"), f2);
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.FONT, fonts);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(resources));
      page.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td <41> Tj ET\nBT /F2 12 Tf 72 680 Td <4142> Tj ET\n"));
      finish(doc, 100L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Two pages, fonts {@code /F1} and {@code /F2} (Type 1 Helvetica, WinAnsi): page 1 shows "AB" in
   * F1 (fill); page 2 shows "C" in F1 and "Z" in F2, both in rendering mode 3; page 1 carries a
   * widget annotation whose normal appearance shows "D" in F1.
   */
  public static byte[] untaggedFontUses() {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary f1 = type1WithDifferences(doc, Map.of(), null);
      COSDictionary f2 = type1WithDifferences(doc, Map.of(), null);
      COSDictionary fonts = new COSDictionary();
      fonts.setItem(COSName.getPDFName("F1"), f1);
      fonts.setItem(COSName.getPDFName("F2"), f2);
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.FONT, fonts);
      PDPage one = new PDPage(PDRectangle.A4);
      doc.addPage(one);
      one.setResources(new PDResources(resources));
      one.setContents(rawStream(doc, "BT /F1 12 Tf 72 700 Td (AB) Tj ET\n"));
      PDPage two = new PDPage(PDRectangle.A4);
      doc.addPage(two);
      two.setResources(new PDResources(resources));
      two.setContents(rawStream(doc, "BT 3 Tr /F1 12 Tf 72 700 Td (C) Tj /F2 12 Tf (Z) Tj ET\n"));
      PDFormXObject appearance = new PDFormXObject(doc);
      appearance.setBBox(new PDRectangle(0, 0, 100, 20));
      appearance.setResources(new PDResources(fontResources(f1)));
      try (java.io.OutputStream os = appearance.getContentStream().createOutputStream()) {
        os.write("BT /F1 10 Tf 2 5 Td (D) Tj ET\n".getBytes(StandardCharsets.US_ASCII));
      }
      PDAnnotationWidget widget = new PDAnnotationWidget();
      widget.setRectangle(new PDRectangle(72, 600, 100, 20));
      widget.setPage(one);
      PDAppearanceDictionary ap = new PDAppearanceDictionary();
      ap.setNormalAppearance(new PDAppearanceStream(appearance.getCOSObject()));
      widget.setAppearance(ap);
      one.setAnnotations(List.of(widget));
      finish(doc, 101L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static COSDictionary type1WithDifferences(PDDocument doc, Map<Integer, String> differences, String toUnicodeOrNull) throws IOException {
    COSDictionary font = new COSDictionary();
    font.setItem(COSName.TYPE, COSName.FONT);
    font.setItem(COSName.SUBTYPE, COSName.TYPE1);
    font.setName(COSName.BASE_FONT, "Helvetica");
    COSDictionary encoding = new COSDictionary();
    encoding.setItem(COSName.TYPE, COSName.ENCODING);
    encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
    COSArray array = new COSArray();
    for (Map.Entry<Integer, String> e : new TreeMap<>(differences).entrySet()) {
      array.add(COSInteger.get(e.getKey()));
      array.add(COSName.getPDFName(e.getValue()));
    }
    encoding.setItem(COSName.DIFFERENCES, array);
    font.setItem(COSName.ENCODING, encoding);
    if (toUnicodeOrNull != null) {
      font.setItem(COSName.TO_UNICODE, rawStream(doc, toUnicodeOrNull));
    }
    return font;
  }

  /**
   * Untagged one-page document showing {@code "Hello world, a test."} at 12 pt in a non-embedded font {@code /F1} named
   * {@code baseFont}: a standard 14 font, or with {@code trueType} a hand-written {@code /TrueType} dictionary with {@code /Widths}
   * from the matching Liberation file plus {@code widthDelta} on code 97, and a descriptor without {@code FontFile2}.
   */
  public static byte[] untaggedNonEmbedded(String baseFont, boolean trueType, int widthDelta) {
    return nonEmbedded(List.of(new NonEmbedded(baseFont, trueType, widthDelta, 32, 0, null, "Hello world, a test.")), 101L);
  }

  /** As {@link #untaggedNonEmbedded} (TrueType, no width change) with descriptor {@code /Flags 4} (symbolic). */
  public static byte[] untaggedNonEmbeddedSymbolicTrueType(String baseFont) {
    return nonEmbedded(List.of(new NonEmbedded(baseFont, true, 0, 4, 0, null, "Hello world, a test.")), 102L);
  }

  /** As {@link #untaggedNonEmbedded} (Type 1) with the text shown in rendering mode 3 (invisible). */
  public static byte[] untaggedNonEmbeddedInvisible(String baseFont) {
    return nonEmbedded(List.of(new NonEmbedded(baseFont, false, 0, 32, 3, null, "Hello world, a test.")), 103L);
  }

  /** A non-embedded Type 1 dictionary {@code /BaseFont baseFont /Encoding /StandardEncoding} (no {@code /Widths}) showing {@code text}. */
  public static byte[] untaggedNonEmbeddedStandardEncoding(String baseFont, String text) {
    return nonEmbedded(List.of(new NonEmbedded(baseFont, false, 0, 32, 0, "StandardEncoding", text)), 104L);
  }

  /**
   * One page, three lines: non-embedded Helvetica (standard 14 Type 1, no {@code /Widths}), ArialMT and TimesNewRomanPSMT
   * (TrueType, WinAnsi, {@code /Widths} from Liberation), as {@code /F1}, {@code /F2}, {@code /F3}.
   */
  public static byte[] untaggedNonEmbeddedMix() {
    return nonEmbedded(List.of(
        new NonEmbedded("Helvetica", false, 0, 32, 0, null, "Helvetica line of text."),
        new NonEmbedded("ArialMT", true, 0, 32, 0, null, "Arial line of text."),
        new NonEmbedded("TimesNewRomanPSMT", true, 0, 32, 0, null, "Times line of text.")), 105L);
  }

  private record NonEmbedded(String baseFont, boolean trueType, int widthDelta, int flags, int renderingMode, String type1Encoding, String text) {}

  private static byte[] nonEmbedded(List<NonEmbedded> fonts, long id) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary fontDict = new COSDictionary();
      StringBuilder content = new StringBuilder();
      float y = 700;
      for (int i = 0; i < fonts.size(); i++) {
        NonEmbedded f = fonts.get(i);
        fontDict.setItem(COSName.getPDFName("F" + (i + 1)), nonEmbeddedFont(f));
        content.append("BT ").append(f.renderingMode()).append(" Tr /F").append(i + 1).append(" 12 Tf 72 ").append((int) y).append(" Td (")
            .append(f.text().replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj ET\n");
        y -= 20;
      }
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.FONT, fontDict);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources(resources));
      page.setContents(rawStream(doc, content.toString()));
      finish(doc, id);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static COSDictionary nonEmbeddedFont(NonEmbedded f) throws IOException {
    if (!f.trueType()) {
      if (f.type1Encoding() != null) {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE1);
        font.setName(COSName.BASE_FONT, f.baseFont());
        font.setName(COSName.ENCODING, f.type1Encoding());
        return font;
      }
      Standard14Fonts.FontName name = Arrays.stream(Standard14Fonts.FontName.values())
          .filter(n -> n.getName().equals(f.baseFont())).findFirst().orElseThrow();
      return new PDType1Font(name).getCOSObject();
    }
    COSDictionary font = new COSDictionary();
    font.setItem(COSName.TYPE, COSName.FONT);
    font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
    font.setName(COSName.BASE_FONT, f.baseFont());
    font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
    font.setInt(COSName.FIRST_CHAR, 32);
    font.setInt(COSName.LAST_CHAR, 126);
    COSArray widths = new COSArray();
    try (TrueTypeFont ttf = com.koldera.pdf.fonts.FontLibrary.substituteFor(f.baseFont()).orElseThrow().load()) {
      CmapSubtable cmap = ttf.getCmap().getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP);
      float scale = 1000f / ttf.getUnitsPerEm();
      org.apache.pdfbox.pdmodel.font.encoding.GlyphList agl = org.apache.pdfbox.pdmodel.font.encoding.GlyphList.getAdobeGlyphList();
      for (int code = 32; code <= 126; code++) {
        String unicode = agl.toUnicode(org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding.INSTANCE.getName(code));
        int gid = cmap.getGlyphId(unicode.codePointAt(0));
        float width = ttf.getAdvanceWidth(gid) * scale + (code == 97 ? f.widthDelta() : 0);
        widths.add(new org.apache.pdfbox.cos.COSFloat(width));
      }
    }
    font.setItem(COSName.WIDTHS, widths);
    COSDictionary descriptor = new COSDictionary();
    descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
    descriptor.setName(COSName.FONT_NAME, f.baseFont());
    descriptor.setInt(COSName.FLAGS, f.flags());
    descriptor.setItem(COSName.FONT_BBOX, numbers(-665, -325, 2000, 1006));
    descriptor.setInt(COSName.ITALIC_ANGLE, 0);
    descriptor.setInt(COSName.ASCENT, 891);
    descriptor.setInt(COSName.DESCENT, -216);
    descriptor.setInt(COSName.CAP_HEIGHT, 716);
    descriptor.setInt(COSName.STEM_V, 80);
    font.setItem(COSName.FONT_DESC, descriptor);
    return font;
  }

  private static COSDictionary fontResources(COSDictionary font) {
    COSDictionary fonts = new COSDictionary();
    fonts.setItem(COSName.getPDFName("F1"), font);
    COSDictionary resources = new COSDictionary();
    resources.setItem(COSName.FONT, fonts);
    return resources;
  }

  private static COSArray numbers(float... values) {
    COSArray array = new COSArray();
    for (float value : values) {
      array.add(new org.apache.pdfbox.cos.COSFloat(value));
    }
    return array;
  }

  /** A4 page with Helvetica as {@code /F1}, added to {@code doc}. */
  private static PDPage rawPage(PDDocument doc) {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    PDResources resources = new PDResources();
    resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    page.setResources(resources);
    return page;
  }

  private static PDStream rawStream(PDDocument doc, String content) throws IOException {
    PDStream stream = new PDStream(doc);
    try (java.io.OutputStream os = stream.createOutputStream()) {
      os.write(content.getBytes(StandardCharsets.US_ASCII));
    }
    return stream;
  }

  private static void line(PDPageContentStream cs, PDType1Font font, float x, float y, String... runs) throws IOException {
    cs.beginText();
    cs.setFont(font, 12);
    cs.newLineAtOffset(x, y);
    for (String run : runs) {
      cs.showText(run);
    }
    cs.endText();
  }

  private static BufferedImage checkerboard(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < size; x++) {
      for (int y = 0; y < size; y++) {
        img.setRGB(x, y, ((x + y) % 2 == 0 ? Color.BLUE : Color.WHITE).getRGB());
      }
    }
    return img;
  }

  /**
   * Untagged one-page document showing "Subset" at 12 pt in a PDFBox subset of the bundled LiberationSans (Type 0, Identity-H,
   * CIDFontType2 with a {@code /CIDToGIDMap} stream and a {@code /CIDSet}). With {@code pdfA1} the catalog also has an XMP
   * packet declaring {@code pdfaid:part 1}, {@code pdfaid:conformance A}.
   */
  public static byte[] type0Subset(boolean pdfA1) {
    byte[] pdf = type0SubsetDocument(false);
    return pdfA1 ? withXmpPacket(pdf, pdfAPacket(1)) : pdf;
  }

  /** As {@link #type0Subset(boolean)} (no XMP), but "Subset" is shown only in a widget annotation's normal appearance; the page content shows nothing. */
  public static byte[] type0SubsetInWidgetAppearance() {
    return type0SubsetDocument(true);
  }

  /** An XMP packet that declares PDF/A {@code part} with conformance A (and nothing else). */
  public static String pdfAPacket(int part) {
    return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
        + "xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"><pdfaid:part>" + part + "</pdfaid:part>"
        + "<pdfaid:conformance>A</pdfaid:conformance></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
  }

  private static byte[] type0SubsetDocument(boolean inWidgetAppearance) {
    byte[] saved;
    try (PDDocument doc = new PDDocument();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.font.PDType0Font font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, true);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      if (inWidgetAppearance) {
        page.setResources(new PDResources());
        page.setContents(rawStream(doc, "q Q\n"));
        PDAppearanceStream appearance = new PDAppearanceStream(doc);
        appearance.setBBox(new PDRectangle(0, 0, 200, 20));
        appearance.setResources(new PDResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, appearance)) {
          cs.beginText();
          cs.setFont(font, 12);
          cs.newLineAtOffset(2, 5);
          cs.showText("Subset");
          cs.endText();
        }
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(appearance);
        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(new PDRectangle(72, 700, 200, 20));
        widget.setAppearance(ap);
        widget.setPage(page);
        page.getAnnotations().add(widget);
      } else {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          cs.beginText();
          cs.setFont(font, 12);
          cs.newLineAtOffset(72, 700);
          cs.showText("Subset");
          cs.endText();
        }
      }
      finish(doc, 98L);
      doc.save(out);
      saved = out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // Reloaded and saved again: the subset is written by the first save only.
    try (PDDocument doc = Loader.loadPDF(saved); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Untagged one-page scan with an OCR layer: a gray image, then invisible ({@code 3 Tr}) text "Scan" in a Type 0 font whose
   * descendant is a {@code CIDFontType0} embedding a CID-keyed CFF whose only glyph is {@code .notdef}
   * ({@link #notdefOnlyCidCff}). {@code alsoVisible} adds {@code 0 Tr} text in the same font; {@code inAppearance} adds a widget
   * whose normal appearance shows it. (A {@code CIDFontType2} variant does not trigger 7.21.8-1; see {@link #untaggedInvisibleOcrTextTrueType}.)
   */
  public static byte[] untaggedInvisibleOcrText(boolean alsoVisible, boolean inAppearance) {
    return notdefOcrText(3, alsoVisible ? OcrExtra.VISIBLE : inAppearance ? OcrExtra.NORMAL_APPEARANCE : OcrExtra.NONE, false);
  }

  /** What {@link #untaggedInvisibleOcrText(OcrExtra)} adds besides the invisible "Scan" on page 1. */
  public enum OcrExtra {
    NONE,
    /** "S" in {@code /F1}, rendering mode 0, on page 1. */
    VISIBLE,
    /** "S" in {@code /F1}, rendering mode 7 (clip only), on page 1. */
    CLIP,
    /** A widget on page 1 whose normal appearance shows "S" in {@code /F1} (rendering mode 3). */
    NORMAL_APPEARANCE,
    /** A widget on page 1 with an empty normal appearance and a rollover ({@code /R}) appearance showing "S" in {@code /F1} (mode 0). */
    ROLLOVER_APPEARANCE,
    /** Page 2 fills a rectangle with a tiling pattern whose cell shows "S" in {@code /F1} (mode 0, from the pattern's resources). */
    PATTERN_PAGE_2,
    /** Page 2 shows "A" in a Type 3 font whose glyph procedure shows "S" in {@code /F1} (mode 0, from the Type 3 font's resources). */
    TYPE3_PAGE_2,
    /** Page 2 lists {@code /F1} in its resources but shows nothing. */
    UNUSED_ON_PAGE_2
  }

  /** As {@link #untaggedInvisibleOcrText(boolean, boolean)} with {@code extra} added. */
  public static byte[] untaggedInvisibleOcrText(OcrExtra extra) {
    return notdefOcrText(3, extra, false);
  }

  /**
   * As {@link #untaggedInvisibleOcrText} with nothing added, but the descendant is a {@code CIDFontType2} embedding as {@code
   * FontFile2} a {@code TTFSubsetter} subset of the PDFBox-bundled LiberationSans with no code point added (only {@code
   * .notdef}) and a {@code /CIDToGIDMap} stream of zeros up to CID 0x6E. PDFBox resolves every shown code to glyph 0; veraPDF
   * 1.30.2 does not report 7.21.8-1 for it, so it serves the TrueType path's unit test only.
   */
  public static byte[] untaggedInvisibleOcrTextTrueType() {
    return notdefOcrText(3, OcrExtra.NONE, true);
  }

  /** As {@link #untaggedInvisibleOcrText} with nothing added, but the text is shown in rendering mode 0 (visible). */
  public static byte[] untaggedVisibleNotdefText() {
    return notdefOcrText(0, OcrExtra.NONE, false);
  }

  private static byte[] notdefOcrText(int renderingMode, OcrExtra extra, boolean trueType) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      page.setResources(resources);
      BufferedImage gray = new BufferedImage(200, 100, BufferedImage.TYPE_BYTE_GRAY);
      java.awt.Graphics2D g = gray.createGraphics();
      g.setColor(new Color(128, 128, 128));
      g.fillRect(0, 0, 200, 100);
      g.dispose();
      COSName image = resources.add(LosslessFactory.createFromImage(doc, gray));
      COSDictionary type0 = hiddenOcrFont(doc, trueType);
      resources.getCOSObject().setItem(COSName.FONT, new COSDictionary());
      resources.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F1"), type0);
      String content = "q 200 0 0 100 72 500 cm /" + image.getName() + " Do Q\n"
          + "BT " + renderingMode + " Tr /F1 12 Tf 72 700 Td <005300630061006E> Tj ET\n"
          + (extra == OcrExtra.VISIBLE ? "BT 0 Tr /F1 12 Tf 72 650 Td <0053> Tj ET\n" : "")
          + (extra == OcrExtra.CLIP ? "q BT 7 Tr /F1 12 Tf 72 650 Td <0053> Tj ET Q\n" : "");
      page.setContents(rawStream(doc, content));
      if (extra == OcrExtra.ROLLOVER_APPEARANCE) {
        PDAppearanceStream normal = new PDAppearanceStream(doc);
        normal.setBBox(new PDRectangle(0, 0, 200, 20));
        PDAppearanceStream rollover = new PDAppearanceStream(doc);
        rollover.setBBox(new PDRectangle(0, 0, 200, 20));
        rollover.setResources(ocrFontResources(type0));
        try (java.io.OutputStream os = rollover.getCOSObject().createOutputStream()) {
          os.write("BT 0 Tr /F1 12 Tf 2 5 Td <0053> Tj ET\n".getBytes(StandardCharsets.US_ASCII));
        }
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(normal);
        ap.setRolloverAppearance(rollover);
        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(new PDRectangle(72, 600, 200, 20));
        widget.setAppearance(ap);
        widget.setPage(page);
        page.getAnnotations().add(widget);
      }
      if (extra == OcrExtra.PATTERN_PAGE_2 || extra == OcrExtra.TYPE3_PAGE_2 || extra == OcrExtra.UNUSED_ON_PAGE_2) {
        PDPage second = new PDPage(PDRectangle.A4);
        doc.addPage(second);
        PDResources secondResources = new PDResources();
        second.setResources(secondResources);
        String secondContent;
        if (extra == OcrExtra.PATTERN_PAGE_2) {
          COSStream pattern = doc.getDocument().createCOSStream();
          pattern.setItem(COSName.TYPE, COSName.PATTERN);
          pattern.setInt(COSName.PATTERN_TYPE, 1);
          pattern.setInt(COSName.PAINT_TYPE, 1);
          pattern.setInt(COSName.TILING_TYPE, 1);
          pattern.setItem(COSName.BBOX, new PDRectangle(0, 0, 50, 20).getCOSArray());
          pattern.setInt(COSName.X_STEP, 50);
          pattern.setInt(COSName.Y_STEP, 20);
          pattern.setItem(COSName.RESOURCES, ocrFontResources(type0).getCOSObject());
          try (java.io.OutputStream os = pattern.createOutputStream()) {
            os.write("BT 0 Tr /F1 12 Tf 2 5 Td <0053> Tj ET\n".getBytes(StandardCharsets.US_ASCII));
          }
          COSDictionary patterns = new COSDictionary();
          patterns.setItem(COSName.getPDFName("P1"), pattern);
          secondResources.getCOSObject().setItem(COSName.PATTERN, patterns);
          secondContent = "/Pattern cs /P1 scn 72 500 200 100 re f\n";
        } else if (extra == OcrExtra.TYPE3_PAGE_2) {
          COSStream procedure = doc.getDocument().createCOSStream();
          try (java.io.OutputStream os = procedure.createOutputStream()) {
            os.write("1000 0 d0 BT 0 Tr /F1 12 Tf 0 0 Td <0053> Tj ET\n".getBytes(StandardCharsets.US_ASCII));
          }
          COSDictionary procedures = new COSDictionary();
          procedures.setItem(COSName.getPDFName("a"), procedure);
          COSArray differences = new COSArray();
          differences.add(COSInteger.get(65));
          differences.add(COSName.getPDFName("a"));
          COSDictionary encoding = new COSDictionary();
          encoding.setItem(COSName.TYPE, COSName.ENCODING);
          encoding.setItem(COSName.DIFFERENCES, differences);
          COSArray matrix = new COSArray();
          for (float v : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
            matrix.add(new org.apache.pdfbox.cos.COSFloat(v));
          }
          COSArray widths = new COSArray();
          widths.add(COSInteger.get(1000));
          COSDictionary type3 = new COSDictionary();
          type3.setItem(COSName.TYPE, COSName.FONT);
          type3.setItem(COSName.SUBTYPE, COSName.TYPE3);
          type3.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000).getCOSArray());
          type3.setItem(COSName.FONT_MATRIX, matrix);
          type3.setItem(COSName.CHAR_PROCS, procedures);
          type3.setItem(COSName.ENCODING, encoding);
          type3.setInt(COSName.FIRST_CHAR, 65);
          type3.setInt(COSName.LAST_CHAR, 65);
          type3.setItem(COSName.WIDTHS, widths);
          type3.setItem(COSName.RESOURCES, ocrFontResources(type0).getCOSObject());
          COSDictionary fonts = new COSDictionary();
          fonts.setItem(COSName.getPDFName("T3"), type3);
          secondResources.getCOSObject().setItem(COSName.FONT, fonts);
          secondContent = "BT /T3 12 Tf 72 700 Td (A) Tj ET\n";
        } else {
          secondResources.getCOSObject().setItem(COSName.FONT, ocrFontResources(type0).getCOSObject().getCOSDictionary(COSName.FONT));
          secondContent = "q Q\n";
        }
        second.setContents(rawStream(doc, secondContent));
      }
      if (extra == OcrExtra.NORMAL_APPEARANCE) {
        PDAppearanceStream appearance = new PDAppearanceStream(doc);
        appearance.setBBox(new PDRectangle(0, 0, 200, 20));
        PDResources appearanceResources = new PDResources();
        appearanceResources.getCOSObject().setItem(COSName.FONT, new COSDictionary());
        appearanceResources.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F1"), type0);
        appearance.setResources(appearanceResources);
        try (java.io.OutputStream os = appearance.getCOSObject().createOutputStream()) {
          os.write("BT 3 Tr /F1 12 Tf 2 5 Td <0053> Tj ET\n".getBytes(StandardCharsets.US_ASCII));
        }
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(appearance);
        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(new PDRectangle(72, 600, 200, 20));
        widget.setAppearance(ap);
        widget.setPage(page);
        page.getAnnotations().add(widget);
      }
      finish(doc, 119L);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Resources whose only entry is {@code /Font << /F1 font >>}. */
  private static PDResources ocrFontResources(COSDictionary font) {
    PDResources resources = new PDResources();
    COSDictionary fonts = new COSDictionary();
    fonts.setItem(COSName.getPDFName("F1"), font);
    resources.getCOSObject().setItem(COSName.FONT, fonts);
    return resources;
  }

  /** The Type 0 {@code HiddenHorzOCR} font of {@link #untaggedInvisibleOcrText}: every shown CID resolves to glyph 0. */
  private static COSDictionary hiddenOcrFont(PDDocument doc, boolean trueType) throws IOException {
    byte[] program;
    if (trueType) {
      try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
          TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
          ByteArrayOutputStream sub = new ByteArrayOutputStream()) {
        new TTFSubsetter(ttf).writeToStream(sub);
        program = sub.toByteArray();
      }
    } else {
      program = notdefOnlyCidCff("HiddenHorzOCR");
    }
    COSStream fontFile = doc.getDocument().createCOSStream();
    try (java.io.OutputStream os = fontFile.createOutputStream(COSName.FLATE_DECODE)) {
      os.write(program);
    }
    if (trueType) {
      fontFile.setInt(COSName.LENGTH1, program.length);
    } else {
      fontFile.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType0C"));
    }
    COSDictionary descriptor = new COSDictionary();
    descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
    descriptor.setName(COSName.FONT_NAME, "HiddenHorzOCR");
    descriptor.setInt(COSName.FLAGS, 4);
    descriptor.setItem(COSName.FONT_BBOX, new PDRectangle(0, -200, 1000, 1000).getCOSArray());
    descriptor.setInt(COSName.ITALIC_ANGLE, 0);
    descriptor.setInt(COSName.ASCENT, 800);
    descriptor.setInt(COSName.DESCENT, -200);
    descriptor.setInt(COSName.CAP_HEIGHT, 700);
    descriptor.setInt(COSName.STEM_V, 80);
    descriptor.setItem(trueType ? COSName.FONT_FILE2 : COSName.FONT_FILE3, fontFile);
    COSDictionary systemInfo = new COSDictionary();
    systemInfo.setString(COSName.REGISTRY, "Adobe");
    systemInfo.setString(COSName.ORDERING, "Identity");
    systemInfo.setInt(COSName.SUPPLEMENT, 0);
    COSDictionary cidFont = new COSDictionary();
    cidFont.setItem(COSName.TYPE, COSName.FONT);
    cidFont.setItem(COSName.SUBTYPE, trueType ? COSName.CID_FONT_TYPE2 : COSName.CID_FONT_TYPE0);
    cidFont.setName(COSName.BASE_FONT, "HiddenHorzOCR");
    cidFont.setItem(COSName.CIDSYSTEMINFO, systemInfo);
    cidFont.setItem(COSName.FONT_DESC, descriptor);
    cidFont.setInt(COSName.DW, 500);
    if (trueType) {
      COSStream cidToGid = doc.getDocument().createCOSStream();
      try (java.io.OutputStream os = cidToGid.createOutputStream(COSName.FLATE_DECODE)) {
        os.write(new byte[2 * (0x6E + 1)]);
      }
      cidFont.setItem(COSName.CID_TO_GID_MAP, cidToGid);
    }
    java.util.TreeMap<Integer, String> unicode = new java.util.TreeMap<>(Map.of(0x53, "S", 0x63, "c", 0x61, "a", 0x6E, "n"));
    COSStream toUnicode = doc.getDocument().createCOSStream();
    try (java.io.OutputStream os = toUnicode.createOutputStream(COSName.FLATE_DECODE)) {
      os.write(com.koldera.pdf.fonts.ToUnicodeCMapWriter.write(2, unicode));
    }
    COSDictionary type0 = new COSDictionary();
    type0.setItem(COSName.TYPE, COSName.FONT);
    type0.setItem(COSName.SUBTYPE, COSName.TYPE0);
    type0.setName(COSName.BASE_FONT, "HiddenHorzOCR");
    type0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
    type0.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(cidFont)));
    type0.setItem(COSName.TO_UNICODE, toUnicode);
    return type0;
  }

  /**
   * A minimal CID-keyed CFF program (ROS Adobe-Identity-0, CIDCount 1) whose only glyph is {@code .notdef} (charstring {@code
   * endchar}), written byte by byte: header, Name/Top DICT/String/Global Subr INDEXes, charset format 0, FDSelect format 0,
   * CharStrings, FDArray with one Font DICT, and a Private DICT holding only {@code defaultWidthX 0}. Offsets use the 5-byte
   * integer form.
   */
  static byte[] notdefOnlyCidCff(String name) {
    byte[] nameIndex = cffIndex(name.getBytes(StandardCharsets.US_ASCII));
    byte[] strings = cffIndex("Adobe".getBytes(StandardCharsets.US_ASCII), "Identity".getBytes(StandardCharsets.US_ASCII));
    byte[] globalSubrs = {0, 0};
    byte[] charset = {0};
    byte[] fdSelect = {0, 0};
    byte[] charStrings = cffIndex(new byte[] {14});
    byte[] privateDict = {(byte) 139, 20};
    int topDictLength = 7 + 3 + 6 + 6 + 7 + 7;
    int topIndexLength = 2 + 1 + 2 + topDictLength;
    int charsetOffset = 4 + nameIndex.length + topIndexLength + strings.length + globalSubrs.length;
    int fdSelectOffset = charsetOffset + charset.length;
    int charStringsOffset = fdSelectOffset + fdSelect.length;
    int fdArrayOffset = charStringsOffset + charStrings.length;
    int fontDictLength = 11;
    int privateOffset = fdArrayOffset + 2 + 1 + 2 + fontDictLength;
    ByteArrayOutputStream top = new ByteArrayOutputStream();
    top.writeBytes(new byte[] {(byte) 248, 27, (byte) 248, 28, (byte) 139, 12, 30}); // ROS: SID 391 "Adobe", SID 392 "Identity", 0
    top.writeBytes(new byte[] {(byte) 140, 12, 34}); // CIDCount 1
    top.writeBytes(cffInt(charsetOffset));
    top.write(15);
    top.writeBytes(cffInt(charStringsOffset));
    top.write(17);
    top.writeBytes(cffInt(fdArrayOffset));
    top.writeBytes(new byte[] {12, 36});
    top.writeBytes(cffInt(fdSelectOffset));
    top.writeBytes(new byte[] {12, 37});
    ByteArrayOutputStream fontDict = new ByteArrayOutputStream();
    fontDict.writeBytes(cffInt(privateDict.length));
    fontDict.writeBytes(cffInt(privateOffset));
    fontDict.write(18);
    ByteArrayOutputStream cff = new ByteArrayOutputStream();
    cff.writeBytes(new byte[] {1, 0, 4, 1});
    cff.writeBytes(nameIndex);
    cff.writeBytes(cffIndex(top.toByteArray()));
    cff.writeBytes(strings);
    cff.writeBytes(globalSubrs);
    cff.writeBytes(charset);
    cff.writeBytes(fdSelect);
    cff.writeBytes(charStrings);
    cff.writeBytes(cffIndex(fontDict.toByteArray()));
    cff.writeBytes(privateDict);
    byte[] bytes = cff.toByteArray();
    if (top.size() != topDictLength || fontDict.size() != fontDictLength || bytes.length != privateOffset + privateDict.length) {
      throw new IllegalStateException("CFF layout arithmetic is off");
    }
    return bytes;
  }

  /** A CFF INDEX with 1-byte offsets (every fixture item is small). */
  private static byte[] cffIndex(byte[]... items) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0);
    out.write(items.length);
    out.write(1);
    int offset = 1;
    out.write(offset);
    for (byte[] item : items) {
      offset += item.length;
      out.write(offset);
    }
    for (byte[] item : items) {
      out.writeBytes(item);
    }
    return out.toByteArray();
  }

  /** The 5-byte CFF DICT integer form (operator 29). */
  private static byte[] cffInt(int value) {
    return new byte[] {29, (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
  }

  /** Fixed creation metadata and document id keep the byte stream reproducible across runs. */
  private static void finish(PDDocument doc, long documentId) {
    Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    fixed.setTimeInMillis(1_600_000_000_000L);
    doc.getDocumentInformation().setCreationDate(fixed);
    doc.getDocumentInformation().setModificationDate(fixed);
    doc.setDocumentId(documentId);
  }

  /** The plain fixture encrypted with AES-128 and a non-empty user password. */
  public static byte[] encrypted(String userPassword) {
    try (PDDocument doc = Loader.loadPDF(simpleUntagged());
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      AccessPermission ap = new AccessPermission();
      StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + userPassword, userPassword, ap);
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The plain fixture with a real detached PKCS#7 signature from a throwaway self-signed key. */
  public static byte[] signed() {
    return signed(simpleUntagged());
  }

  /** {@code base} with a real detached PKCS#7 signature from a throwaway self-signed key. */
  public static byte[] signed(byte[] base) {
    try (PDDocument doc = Loader.loadPDF(base);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      TestSigner signer = TestSigner.create();
      PDSignature signature = new PDSignature();
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
      signature.setName("Synthetic Test Signer");
      signature.setReason("fixture");
      signature.setSignDate(Calendar.getInstance());
      doc.addSignature(signature, signer);
      doc.saveIncremental(out);
      return out.toByteArray();
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  /** Bytes that start with the PDF magic but are not a parseable document. */
  public static byte[] corrupt() {
    byte[] prefix = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog".getBytes(StandardCharsets.ISO_8859_1);
    byte[] junk = new byte[2048];
    new java.util.Random(7).nextBytes(junk); // seeded: fixture bytes are reproducible
    Arrays.fill(junk, 0, 16, (byte) 'x');
    byte[] result = Arrays.copyOf(prefix, prefix.length + junk.length);
    System.arraycopy(junk, 0, result, prefix.length, junk.length);
    return result;
  }

  /** The plain fixture truncated in the middle of its xref, i.e. a damaged download. */
  public static byte[] truncated() {
    byte[] full = simpleUntagged();
    return Arrays.copyOf(full, full.length / 2);
  }

  public static Path write(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    Files.write(target, bytes);
    return target;
  }

  /** Minimal CMS detached signer used only to produce signed fixtures. */
  private static final class TestSigner implements SignatureInterface {
    private final PrivateKey key;
    private final X509Certificate certificate;

    private TestSigner(PrivateKey key, X509Certificate certificate) {
      this.key = key;
      this.certificate = certificate;
    }

    static TestSigner create() throws GeneralSecurityException {
      try {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        X500Name name = new X500Name("CN=Synthetic Test Signer, O=accessibility-platform fixtures");
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 3_600_000L);
        X509v3CertificateBuilder builder =
            new JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore, notAfter, name, pair.getPublic());
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(contentSigner));
        return new TestSigner(pair.getPrivate(), cert);
      } catch (Exception e) {
        throw new GeneralSecurityException("cannot create test signer", e);
      }
    }

    @Override
    public byte[] sign(InputStream content) throws IOException {
      try {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner sha256Signer = new JcaContentSignerBuilder("SHA256withRSA").build(key);
        gen.addSignerInfoGenerator(
            new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(sha256Signer, certificate));
        gen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(List.of(certificate)));
        CMSSignedData signedData = gen.generate(new CMSProcessableByteArray(content.readAllBytes()), false);
        return signedData.getEncoded();
      } catch (Exception e) {
        throw new IOException("cannot sign fixture", e);
      }
    }
  }
}
