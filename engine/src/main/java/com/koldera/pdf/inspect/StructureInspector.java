package com.koldera.pdf.inspect;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.inspect.InspectResult.ErrorCode;
import com.koldera.pdf.inspect.InspectResult.RoleCount;
import com.koldera.pdf.inspect.InspectResult.Structure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * Read-only probe of a PDF's logical structure: marked-content flag, structure tree, element and content-reference counts and
 * roles. The walk is COS-level, cycle-safe and depth-bounded; it never writes, executes actions or opens attachments.
 */
public final class StructureInspector {

  private static final int HEADER_SEARCH_WINDOW = 1024;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_ROLES = 100;
  private static final int MAX_STRING = 256;
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  public InspectResult inspect(Path input, long maxBytes) {
    String engine = EngineVersion.describe();
    long size;
    try {
      size = Files.size(input);
    } catch (NoSuchFileException e) {
      return InspectResult.failed(null, 0, engine, ErrorCode.INVALID_INPUT, "input document is missing");
    } catch (IOException e) {
      return InspectResult.failed(null, 0, engine, ErrorCode.INVALID_INPUT, "input document is unreadable");
    }
    if (size <= 0) {
      return InspectResult.failed(null, size, engine, ErrorCode.INVALID_INPUT, "input document is empty");
    }
    if (size > maxBytes) {
      return InspectResult.failed(null, size, engine, ErrorCode.LIMIT_EXCEEDED, "input document exceeds maxBytes=" + maxBytes);
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(input);
    } catch (IOException e) {
      return InspectResult.failed(null, size, engine, ErrorCode.INVALID_INPUT, "input document is unreadable");
    }
    String sha256 = sha256Hex(bytes);
    if (!hasPdfHeader(bytes)) {
      return InspectResult.failed(sha256, size, engine, ErrorCode.INVALID_INPUT, "input does not start with a %PDF- header");
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        return InspectResult.failed(sha256, size, engine, ErrorCode.UNSUPPORTED_ENCRYPTED, "encrypted documents are not inspected");
      }
      return InspectResult.inspected(sha256, size, engine, probe(document));
    } catch (InvalidPasswordException e) {
      return InspectResult.failed(sha256, size, engine, ErrorCode.UNSUPPORTED_ENCRYPTED, "document requires a password; encrypted documents are not inspected");
    } catch (IOException | RuntimeException e) {
      return InspectResult.failed(sha256, size, engine, ErrorCode.INVALID_INPUT, "document could not be parsed (" + e.getClass().getSimpleName() + ")");
    }
  }

  private static Structure probe(PDDocument document) {
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    COSDictionary catalogDict = catalog.getCOSObject();
    boolean marked = false;
    COSBase markInfo = catalogDict.getDictionaryObject(COSName.MARK_INFO);
    if (markInfo instanceof COSDictionary mi) {
      marked = mi.getBoolean("Marked", false);
    }
    COSBase rootBase = catalogDict.getDictionaryObject(COSName.STRUCT_TREE_ROOT);
    boolean hasRoot = rootBase instanceof COSDictionary;

    Walk walk = new Walk();
    if (hasRoot) {
      walk.kids(((COSDictionary) rootBase).getDictionaryObject(COSName.K), 0);
    }
    List<RoleCount> roles = new ArrayList<>();
    walk.roles.forEach((role, count) -> roles.add(new RoleCount(role, count)));
    roles.sort(Comparator.comparingInt(RoleCount::count).reversed().thenComparing(RoleCount::role));
    List<RoleCount> topRoles = roles.size() > MAX_ROLES ? new ArrayList<>(roles.subList(0, MAX_ROLES)) : roles;

    boolean usable = marked && hasRoot && walk.elements > 0 && walk.contentReferences > 0;
    String language = clip(catalog.getLanguage(), 64);
    String title = clip(document.getDocumentInformation() == null ? null : document.getDocumentInformation().getTitle(), MAX_STRING);
    return new Structure(
        document.getNumberOfPages(),
        false,
        marked,
        hasRoot,
        walk.elements,
        walk.contentReferences,
        usable,
        Collections.unmodifiableList(topRoles),
        language,
        title);
  }

  /** Cycle-safe, depth-bounded traversal of the /K forest. */
  private static final class Walk {
    final Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<String, Integer> roles = new HashMap<>();
    int elements;
    int contentReferences;

    void kids(COSBase kids, int depth) {
      if (kids == null || depth > MAX_DEPTH) {
        return;
      }
      COSBase resolved = kids instanceof COSObject ref ? ref.getObject() : kids;
      if (resolved instanceof COSArray array) {
        for (COSBase item : array) {
          kids(item, depth + 1);
        }
      } else if (resolved instanceof COSNumber) {
        // A bare integer kid is an MCID on the element's /Pg.
        contentReferences++;
      } else if (resolved instanceof COSDictionary dict) {
        node(dict, depth);
      }
    }

    private void node(COSDictionary dict, int depth) {
      if (!visited.add(dict)) {
        return;
      }
      COSName type = dict.getCOSName(COSName.TYPE);
      if (COSName.MCR.equals(type) || COSName.OBJR.equals(type)) {
        contentReferences++;
        return;
      }
      elements++;
      COSName role = dict.getCOSName(COSName.S);
      if (role != null && !role.getName().isEmpty()) {
        roles.merge(clip(role.getName(), 64), 1, Integer::sum);
      }
      kids(dict.getDictionaryObject(COSName.K), depth + 1);
    }
  }

  private static String clip(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static boolean hasPdfHeader(byte[] bytes) {
    int limit = Math.min(bytes.length, HEADER_SEARCH_WINDOW) - PDF_MAGIC.length;
    outer:
    for (int i = 0; i <= limit; i++) {
      for (int j = 0; j < PDF_MAGIC.length; j++) {
        if (bytes[i + j] != PDF_MAGIC[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
