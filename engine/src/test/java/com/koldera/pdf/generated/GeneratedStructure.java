package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.structure.NumberTreeView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Test-side view of a structure tree, read straight from the COS objects so the assertions do not
 * depend on the code under test: elements by type, the ordered content items and the ParentTree check.
 */
final class GeneratedStructure {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");

  private GeneratedStructure() {}

  static PDDocument fixture(String name) throws IOException {
    try (InputStream in = GeneratedStructure.class.getResourceAsStream("/generated/" + name)) {
      assertNotNull(in, "fixture " + name + " (a static, committed PDF under src/test/resources/generated/)");
      return Loader.loadPDF(in.readAllBytes());
    }
  }

  /** Saves and reloads, so identity checks below hold for what is actually written. */
  static PDDocument roundTrip(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    doc.save(out);
    doc.close();
    return Loader.loadPDF(out.toByteArray());
  }

  static COSDictionary root(PDDocument doc) {
    return doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
  }

  static COSBase resolve(COSBase base) {
    return base instanceof COSObject ref ? ref.getObject() : base;
  }

  /** The /K entries of a node, resolved, in order. */
  static List<COSBase> kids(COSDictionary node) {
    COSBase k = resolve(node.getItem(COSName.K));
    List<COSBase> out = new ArrayList<>();
    if (k instanceof COSArray array) {
      for (COSBase entry : array) {
        out.add(resolve(entry));
      }
    } else if (k != null) {
      out.add(k);
    }
    return out;
  }

  static boolean isElement(COSBase kid) {
    return kid instanceof COSDictionary d && !MCR.equals(d.getCOSName(COSName.TYPE)) && !OBJR.equals(d.getCOSName(COSName.TYPE)) && d.containsKey(COSName.S);
  }

  static String type(COSBase kid) {
    if (kid instanceof COSInteger) {
      return "mcid";
    }
    COSDictionary d = (COSDictionary) kid;
    if (MCR.equals(d.getCOSName(COSName.TYPE))) {
      return "mcr";
    }
    if (OBJR.equals(d.getCOSName(COSName.TYPE))) {
      return "objr";
    }
    return d.getNameAsString(COSName.S);
  }

  static List<String> kidTypes(COSDictionary node) {
    return kids(node).stream().map(GeneratedStructure::type).toList();
  }

  static List<COSDictionary> elements(PDDocument doc, String s) {
    List<COSDictionary> out = new ArrayList<>();
    collect(root(doc), s, out);
    return out;
  }

  private static void collect(COSDictionary node, String s, List<COSDictionary> out) {
    for (COSBase kid : kids(node)) {
      if (isElement(kid)) {
        COSDictionary el = (COSDictionary) kid;
        if (s.equals(el.getNameAsString(COSName.S))) {
          out.add(el);
        }
        collect(el, s, out);
      }
    }
  }

  /** One content item and the element whose /K holds it. */
  record Content(String key, COSDictionary holder, int parentTreeKey, int mcid) {}

  /** Every MCID and OBJR in structure order, as "sp&lt;StructParents&gt;:&lt;mcid&gt;" or "objr:&lt;StructParent&gt;". */
  static List<Content> content(PDDocument doc) {
    List<Content> out = new ArrayList<>();
    content(root(doc), out);
    return out;
  }

  static List<String> contentKeys(PDDocument doc) {
    return content(doc).stream().map(Content::key).toList();
  }

  private static void content(COSDictionary node, List<Content> out) {
    for (COSBase kid : kids(node)) {
      if (kid instanceof COSInteger mcid) {
        int sp = pageStructParents(node.getDictionaryObject(COSName.PG));
        out.add(new Content("sp" + sp + ":" + mcid.intValue(), node, sp, mcid.intValue()));
      } else if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE))) {
        COSBase stm = d.getDictionaryObject(COSName.getPDFName("Stm"));
        COSBase pg = d.containsKey(COSName.PG) ? d.getDictionaryObject(COSName.PG) : node.getDictionaryObject(COSName.PG);
        int sp = stm instanceof COSDictionary s ? s.getInt(COSName.STRUCT_PARENTS) : pageStructParents(pg);
        int mcid = d.getInt(COSName.MCID);
        out.add(new Content("sp" + sp + ":" + mcid, node, sp, mcid));
      } else if (kid instanceof COSDictionary d && OBJR.equals(d.getCOSName(COSName.TYPE))) {
        int sp = ((COSDictionary) d.getDictionaryObject(COSName.OBJ)).getInt(COSName.STRUCT_PARENT);
        out.add(new Content("objr:" + sp, node, sp, -1));
      } else if (isElement(kid)) {
        content((COSDictionary) kid, out);
      }
    }
  }

  private static int pageStructParents(COSBase page) {
    assertTrue(page instanceof COSDictionary, "content item without a page");
    return ((COSDictionary) page).getInt(COSName.STRUCT_PARENTS);
  }

  /** /ParentTree maps every MCID and every OBJR's StructParent to the element that holds it. */
  static void assertParentTreeMatches(PDDocument doc) {
    Map<Integer, COSBase> tree = NumberTreeView.read(root(doc).getDictionaryObject(COSName.PARENT_TREE));
    List<Content> items = content(doc);
    assertTrue(items.size() > 0, "no content in the structure tree");
    for (Content c : items) {
      COSBase value = resolve(tree.get(c.parentTreeKey()));
      assertNotNull(value, "ParentTree has no key " + c.parentTreeKey());
      COSBase parent = c.mcid() < 0 ? value : resolve(((COSArray) value).get(c.mcid()));
      assertTrue(c.holder() == parent, "ParentTree entry for " + c.key() + " is " + (parent instanceof COSDictionary d ? d.getNameAsString(COSName.S) : parent) + ", holder is " + c.holder().getNameAsString(COSName.S));
    }
  }

  /** /P of every element is the node whose /K holds it. */
  static void assertParentLinks(PDDocument doc) {
    assertParentLinks(root(doc));
  }

  private static void assertParentLinks(COSDictionary node) {
    for (COSBase kid : kids(node)) {
      if (isElement(kid)) {
        COSDictionary el = (COSDictionary) kid;
        assertTrue(node == el.getDictionaryObject(COSName.P), "/P of " + el.getNameAsString(COSName.S));
        assertParentLinks(el);
      }
    }
  }

  static void assertSameContent(List<String> before, PDDocument after) {
    assertEquals(before, contentKeys(after), "content items and their order are unchanged");
  }
}
