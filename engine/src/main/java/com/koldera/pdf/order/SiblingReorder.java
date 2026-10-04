package com.koldera.pdf.order;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.order.ReorderResult.Checks;
import com.koldera.pdf.order.ReorderResult.ErrorCode;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.PageDigest;
import com.koldera.pdf.structure.StructureExtractor;
import com.koldera.pdf.structure.StructureNode;
import com.koldera.pdf.structure.StructureResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The only structural mutation the engine performs: permute the structure-element kids of one parent after a reviewer
 * approved that exact sequence. Signed, encrypted and untagged files, mixed or annotated parents, multi-page windows,
 * tables and incomplete permutations are refused. The copy is reparsed and published only if page count, content streams,
 * text multisets, annotations, MCIDs, trees and a pixel render are unchanged and the sequence is the approved one.
 */
public final class SiblingReorder {

  private static final Set<String> TABLE_ROLES = Set.of("Table", "TR", "TD", "TH", "THead", "TBody", "TFoot");
  private static final float RENDER_DPI = 36f;

  private final StructureExtractor extractor = new StructureExtractor();
  private final Consumer<PDDocument> afterMutationForTests;

  public SiblingReorder() {
    this(null);
  }

  /** Test seam: tamper with the mutated copy before it is saved, to prove the gate rolls back. */
  SiblingReorder(Consumer<PDDocument> afterMutationForTests) {
    this.afterMutationForTests = afterMutationForTests;
  }

  public ReorderResult apply(ReorderRequest request, Path workRoot) {
    String engine = EngineVersion.describe();
    Path input = workRoot.resolve("in").resolve("document.pdf");
    Path output = workRoot.resolve("out").resolve("reordered.pdf");
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(input);
    } catch (IOException e) {
      return ReorderResult.failed(request.jobId(), engine, request.baseSha256(), ErrorCode.INVALID_INPUT, "input document is missing or unreadable", null);
    }
    if (bytes.length > request.limits().maxBytes()) {
      return ReorderResult.failed(request.jobId(), engine, request.baseSha256(), ErrorCode.LIMIT_EXCEEDED, "input document exceeds maxBytes=" + request.limits().maxBytes(), null);
    }
    String sha256 = PdfPreflight.sha256Hex(bytes);
    if (!sha256.equals(request.baseSha256())) {
      return ReorderResult.failed(request.jobId(), engine, request.baseSha256(), ErrorCode.INVALID_INPUT, "the document does not match baseSha256; the approval is stale", null);
    }

    StructureResult before;
    byte[] mutated;
    List<String> appliedRoles;
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.UNSUPPORTED_ENCRYPTED, "encrypted documents are never mutated", null);
      }
      before = extractor.extract(document, sha256, bytes.length, engine);
      if (before.status() != StructureResult.Status.INSPECTED) {
        return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.INVALID_INPUT, "document could not be inspected", null);
      }
      if (before.document().signed()) {
        return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.UNSUPPORTED_SIGNED_MUTATION, "document is digitally signed; mutation would invalidate the signature", null);
      }
      if (before.document().pages() > request.limits().maxPages()) {
        return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.LIMIT_EXCEEDED, "document has more pages than maxPages=" + request.limits().maxPages(), null);
      }
      if (!before.document().usableTags()) {
        return unsupported(request, engine, sha256, "the document has no usable structure tree; there is no tag order to correct");
      }

      // Resolve the parent and its element kids; refuse anything outside the supported shape.
      COSDictionary root = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      COSDictionary parent = root;
      StructureNode parentNode = null;
      List<Integer> path = new ArrayList<>();
      for (int index : request.parentPath()) {
        List<COSBase> kids = kidList(parent);
        if (index < 0 || index >= kids.size() || !(kids.get(index) instanceof COSDictionary next) || isReference(next)) {
          return unsupported(request, engine, sha256, "parent path does not resolve to a structure element");
        }
        parent = next;
        path.add(index);
        parentNode = nodeAt(before, path);
      }
      if (parentNode != null && TABLE_ROLES.contains(parentNode.role())) {
        return unsupported(request, engine, sha256, "table rows and cells are never reordered (parent role " + parentNode.role() + ")");
      }
      List<COSBase> kids = kidList(parent);
      if (kids.size() < 2) {
        return unsupported(request, engine, sha256, "the parent has fewer than two kids; nothing to permute");
      }
      for (COSBase kid : kids) {
        if (!(kid instanceof COSDictionary dict) || isReference(dict)) {
          return unsupported(request, engine, sha256, "the parent's kids must all be structure elements (no marked-content or object references at this level)");
        }
      }
      List<Integer> order = request.orderedKidIndexes();
      if (order.size() != kids.size()) {
        return unsupported(request, engine, sha256, "the approved order must be a complete permutation of the parent's " + kids.size() + " kids");
      }
      boolean[] seen = new boolean[kids.size()];
      boolean identity = true;
      for (int i = 0; i < order.size(); i++) {
        int index = order.get(i);
        if (index < 0 || index >= kids.size() || seen[index]) {
          return unsupported(request, engine, sha256, "the approved order is not a permutation of the parent's kids");
        }
        seen[index] = true;
        if (index != i) {
          identity = false;
        }
      }
      if (identity) {
        return unsupported(request, engine, sha256, "the structure is already in the approved order");
      }
      Set<Integer> pages = new HashSet<>();
      appliedRoles = new ArrayList<>();
      List<StructureNode> siblingNodes = new ArrayList<>();
      for (int i = 0; i < kids.size(); i++) {
        List<Integer> kidPath = new ArrayList<>(path);
        kidPath.add(i);
        StructureNode node = nodeAt(before, kidPath);
        if (node == null) {
          return unsupported(request, engine, sha256, "a sibling is not part of the snapshot (shared or cyclic reference)");
        }
        siblingNodes.add(node);
        if (TABLE_ROLES.contains(node.role())) {
          return unsupported(request, engine, sha256, "table rows and cells are never reordered (sibling role " + node.role() + ")");
        }
        if (node.privateMapping().objectRefs() > 0) {
          return unsupported(request, engine, sha256, "a sibling references an annotation or form field (OBJR); interactive content is reviewed manually");
        }
        if (node.privateMapping().contentRefs() == 0) {
          return unsupported(request, engine, sha256, "a sibling references no page content; it is not an independent semantic block");
        }
        if (node.privateMapping().pages().size() != 1) {
          return unsupported(request, engine, sha256, "a sibling spans more than one page (or none); cross-page structures are reviewed manually");
        }
      }
      // The move is the index window from the first to the last position that changes; every kid in
      // it (moved or not) must sit on the one page, so nothing on another page is spoken elsewhere
      // than before. A flat parent (structure_fix hangs every page's P under Document) may span many
      // pages as long as that window stays on one.
      int first = -1;
      int last = -1;
      for (int i = 0; i < order.size(); i++) {
        if (order.get(i) != i) {
          first = first < 0 ? i : first;
          last = i;
        }
      }
      for (int i = first; i <= last; i++) {
        pages.addAll(siblingNodes.get(i).privateMapping().pages());
      }
      if (pages.size() != 1) {
        return unsupported(request, engine, sha256, "the siblings are not all on the same page; cross-page structures are reviewed manually");
      }
      for (int index : order) {
        appliedRoles.add(siblingNodes.get(index).role());
      }

      // Apply on this in-memory copy: a new /K array with the same element objects, nothing else touched.
      COSArray original = kidArray(parent);
      COSArray permuted = new COSArray();
      for (int index : order) {
        permuted.add(original.get(index));
      }
      parent.setItem(COSName.K, permuted);
      if (afterMutationForTests != null) {
        afterMutationForTests.accept(document);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      CopyWriter.save(document, out);
      mutated = out.toByteArray();
    } catch (InvalidPasswordException e) {
      return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.UNSUPPORTED_ENCRYPTED, "document requires a password; encrypted documents are never mutated", null);
    } catch (IOException | RuntimeException e) {
      return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.INVALID_INPUT, "document could not be processed (" + e.getClass().getSimpleName() + ")", null);
    }

    // Independent verification on the reparsed copy.
    Checks checks;
    try {
      checks = verify(bytes, before, mutated, request, engine);
    } catch (IOException | RuntimeException e) {
      return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.INTEGRITY_REGRESSION, "the changed copy could not be verified (" + e.getClass().getSimpleName() + ")", null);
    }
    if (!checks.allTrue()) {
      return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.INTEGRITY_REGRESSION, "an invariant did not hold on the changed copy; nothing was published (" + describe(checks) + ")", checks);
    }
    try {
      Files.createDirectories(output.getParent());
      Path temp = Files.createTempFile(output.getParent(), ".reordered-", ".tmp");
      Files.write(temp, mutated);
      try {
        Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.WORKER_ERROR, "the changed copy could not be written", checks);
    }
    return new ReorderResult(ReorderResult.SPEC_VERSION, request.jobId(), ReorderResult.Status.APPLIED, engine, sha256, PdfPreflight.sha256Hex(mutated), mutated.length, checks, appliedRoles, null);
  }

  private Checks verify(byte[] originalBytes, StructureResult before, byte[] mutated, ReorderRequest request, String engine) throws IOException {
    try (PDDocument original = Loader.loadPDF(originalBytes); PDDocument changed = Loader.loadPDF(mutated)) {
      StructureResult after = extractor.extract(changed, PdfPreflight.sha256Hex(mutated), mutated.length, engine);
      boolean pageCount = before.document().pages() == after.document().pages();
      boolean streams = true;
      boolean tokens = true;
      boolean annotations = true;
      boolean mcids = true;
      if (pageCount) {
        for (int i = 0; i < before.document().pageDigests().size(); i++) {
          PageDigest b = before.document().pageDigests().get(i);
          PageDigest a = after.document().pageDigests().get(i);
          streams &= b.contentStreamSha256().equals(a.contentStreamSha256());
          tokens &= b.textTokenMultisetSha256().equals(a.textTokenMultisetSha256());
          annotations &= b.annotationCount() == a.annotationCount();
          mcids &= b.mcidCount() == a.mcidCount();
        }
        annotations &= before.document().annotations() == after.document().annotations();
      }
      boolean parentTree = parentTreeSignature(original, before).equals(parentTreeSignature(changed, after));
      boolean inventory = inventory(before).equals(inventory(after));
      boolean sequence = sequenceMatches(before, after, request);
      boolean render = pageCount && renderIdentical(original, changed);
      return new Checks(pageCount, streams, tokens, annotations, mcids, parentTree, render, sequence, inventory);
    }
  }

  /** ParentTree keys with the identity of what they point at (role, content refs, preview) — order-free. */
  private static TreeMap<Integer, String> parentTreeSignature(PDDocument document, StructureResult snapshot) {
    TreeMap<Integer, String> signature = new TreeMap<>();
    COSDictionary root = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
    if (root == null) {
      return signature;
    }
    var byPath = new java.util.HashMap<String, StructureNode>();
    for (StructureNode node : snapshot.nodes()) {
      byPath.put(node.privateMapping().objectRef() == null ? node.privateMapping().path().toString() : node.privateMapping().objectRef(), node);
    }
    var tree = com.koldera.pdf.structure.NumberTreeView.read(root.getDictionaryObject(COSName.PARENT_TREE));
    for (var entry : tree.entrySet()) {
      COSBase value = entry.getValue() instanceof COSObject ref ? ref.getObject() : entry.getValue();
      signature.put(entry.getKey(), describeValue(value, snapshot));
    }
    return signature;
  }

  private static String describeValue(COSBase value, StructureResult snapshot) {
    if (value instanceof COSArray array) {
      List<String> items = new ArrayList<>();
      for (COSBase item : array) {
        COSBase resolved = item instanceof COSObject ref ? ref.getObject() : item;
        items.add(describeValue(resolved, snapshot));
      }
      return "[" + String.join(",", items) + "]";
    }
    if (value instanceof COSDictionary dict) {
      StructureNode node = findNode(snapshot, dict);
      if (node != null) {
        String leafPreview = "content".equals(node.privateMapping().kidKinds()) ? node.textPreview() : "";
        return node.role() + "|" + node.privateMapping().contentRefs() + "|" + node.privateMapping().objectRefs() + "|" + leafPreview;
      }
      COSName role = dict.getCOSName(COSName.S);
      return "dict:" + (role == null ? "?" : role.getName());
    }
    return value == null ? "null" : value.getClass().getSimpleName();
  }

  private static StructureNode findNode(StructureResult snapshot, COSDictionary dict) {
    var key = dict.getKey();
    if (key != null) {
      String ref = key.getNumber() + " " + key.getGeneration();
      for (StructureNode node : snapshot.nodes()) {
        if (ref.equals(node.privateMapping().objectRef())) {
          return node;
        }
      }
    }
    // Direct dictionaries: match by role + kids shape + preview (best effort, still order-free).
    COSName role = dict.getCOSName(COSName.S);
    for (StructureNode node : snapshot.nodes()) {
      if (node.privateMapping().objectRef() == null && role != null && role.getName().equals(node.role())) {
        return node;
      }
    }
    return null;
  }

  /**
   * Multiset of (role, page, kid shape, contentRefs, objectRefs, preview-of-leaf) for every node:
   * identical before and after. An ancestor's preview is the concatenation of its kids' text and
   * legitimately changes with the approved order, so only leaf (content-bearing) previews count.
   */
  private static List<String> inventory(StructureResult snapshot) {
    List<String> items = new ArrayList<>();
    for (StructureNode node : snapshot.nodes()) {
      var m = node.privateMapping();
      String leafPreview = "content".equals(m.kidKinds()) ? node.textPreview() : "";
      items.add(node.role() + "|" + node.pageNumber() + "|" + m.kidKinds() + "|" + m.elementKids() + "|" + m.contentRefs() + "|" + m.objectRefs() + "|" + leafPreview);
    }
    items.sort(String::compareTo);
    return items;
  }

  private static boolean sequenceMatches(StructureResult before, StructureResult after, ReorderRequest request) {
    List<Integer> parentPath = request.parentPath();
    for (int i = 0; i < request.orderedKidIndexes().size(); i++) {
      List<Integer> beforePath = new ArrayList<>(parentPath);
      beforePath.add(request.orderedKidIndexes().get(i));
      List<Integer> afterPath = new ArrayList<>(parentPath);
      afterPath.add(i);
      StructureNode expected = nodeAt(before, beforePath);
      StructureNode actual = nodeAt(after, afterPath);
      if (expected == null || actual == null) {
        return false;
      }
      if (!expected.role().equals(actual.role()) || !java.util.Objects.equals(expected.textPreview(), actual.textPreview())
          || expected.privateMapping().contentRefs() != actual.privateMapping().contentRefs()) {
        return false;
      }
    }
    return true;
  }

  private static boolean renderIdentical(PDDocument a, PDDocument b) throws IOException {
    PDFRenderer ra = new PDFRenderer(a);
    PDFRenderer rb = new PDFRenderer(b);
    for (int i = 0; i < a.getNumberOfPages(); i++) {
      BufferedImage ia = ra.renderImageWithDPI(i, RENDER_DPI);
      BufferedImage ib = rb.renderImageWithDPI(i, RENDER_DPI);
      if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight()) {
        return false;
      }
      int[] pa = ia.getRGB(0, 0, ia.getWidth(), ia.getHeight(), null, 0, ia.getWidth());
      int[] pb = ib.getRGB(0, 0, ib.getWidth(), ib.getHeight(), null, 0, ib.getWidth());
      if (!Arrays.equals(pa, pb)) {
        return false;
      }
    }
    return true;
  }

  private static StructureNode nodeAt(StructureResult snapshot, List<Integer> path) {
    for (StructureNode node : snapshot.nodes()) {
      if (node.privateMapping().path().equals(path)) {
        return node;
      }
    }
    return null;
  }

  private static List<COSBase> kidList(COSDictionary element) {
    COSArray array = kidArray(element);
    List<COSBase> kids = new ArrayList<>();
    for (COSBase item : array) {
      kids.add(item instanceof COSObject ref ? ref.getObject() : item);
    }
    return kids;
  }

  private static COSArray kidArray(COSDictionary element) {
    COSBase k = element.getDictionaryObject(COSName.K);
    if (k instanceof COSArray array) {
      return array;
    }
    COSArray single = new COSArray();
    if (k != null) {
      single.add(element.getItem(COSName.K));
    }
    return single;
  }

  private static boolean isReference(COSDictionary dict) {
    COSName type = dict.getCOSName(COSName.TYPE);
    return COSName.MCR.equals(type) || COSName.OBJR.equals(type);
  }

  private static ReorderResult unsupported(ReorderRequest request, String engine, String sha256, String reason) {
    return ReorderResult.failed(request.jobId(), engine, sha256, ErrorCode.ORDER_FIX_UNSUPPORTED, reason, null);
  }

  private static String describe(Checks c) {
    List<String> failed = new ArrayList<>();
    if (!Boolean.TRUE.equals(c.pageCountUnchanged())) failed.add("pageCount");
    if (!Boolean.TRUE.equals(c.contentStreamsUnchanged())) failed.add("contentStreams");
    if (!Boolean.TRUE.equals(c.textTokensUnchanged())) failed.add("textTokens");
    if (!Boolean.TRUE.equals(c.annotationsUnchanged())) failed.add("annotations");
    if (!Boolean.TRUE.equals(c.mcidInventoryUnchanged())) failed.add("mcidInventory");
    if (!Boolean.TRUE.equals(c.parentTreeUnchanged())) failed.add("parentTree");
    if (!Boolean.TRUE.equals(c.renderIdentical())) failed.add("render");
    if (!Boolean.TRUE.equals(c.sequenceMatchesApproved())) failed.add("sequence");
    if (!Boolean.TRUE.equals(c.nodeInventoryUnchanged())) failed.add("nodeInventory");
    return String.join(", ", failed);
  }
}
