package com.koldera.pdf.integrity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.structure.ContentTagger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class CopyWriterTest {

  @Test
  void pdfbox308ObjectStreamWriterTurnsEqualDirectIntegersIntoReferences() throws Exception {
    // The mechanism behind the LibreOffice/Keynote/PowerPoint findings, pinned so a PDFBox upgrade
    // that fixes it is noticed: the default save emits "6 0 R" for a direct 0 once object 6 (= 0) was read.
    byte[] saved = tagAndSave(SyntheticPdfFactory.untaggedWithIndirectSmallIntegers(), false);
    try (PDDocument copy = Loader.loadPDF(saved)) {
      assertTrue(copy.getPage(0).getCOSObject().getItem(COSName.STRUCT_PARENTS) instanceof COSObject);
    }
  }

  @Test
  void copyWriterKeepsEveryDirectNumberDirect() throws Exception {
    byte[] saved = tagAndSave(SyntheticPdfFactory.untaggedWithIndirectSmallIntegers(), true);
    try (PDDocument copy = Loader.loadPDF(saved)) {
      COSDictionary page = copy.getPage(0).getCOSObject();
      assertTrue(page.getItem(COSName.STRUCT_PARENTS) instanceof COSInteger, String.valueOf(page.getItem(COSName.STRUCT_PARENTS)));
      assertTrue(page.getItem(COSName.ROTATE) instanceof COSObject, "an indirect value of the input stays indirect");
      COSDictionary pages = (COSDictionary) copy.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES);
      assertTrue(pages.getItem(COSName.COUNT) instanceof COSInteger);
      COSDictionary root = copy.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      COSArray nums = ((COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE)).getCOSArray(COSName.NUMS);
      assertTrue(nums.get(0) instanceof COSInteger, String.valueOf(nums.get(0)));
      COSArray elements = ((COSDictionary) root.getDictionaryObject(COSName.K)).getCOSArray(COSName.K);
      for (int i = 0; i < elements.size(); i++) {
        COSBase kid = ((COSDictionary) elements.getObject(i)).getItem(COSName.K);
        assertEquals(COSInteger.get(i), kid, "element " + i + " keeps MCID " + i + " as a direct integer");
      }
    }
  }

  @Test
  void copyWriterIsDeterministic() throws Exception {
    assertArrayEquals(
        tagAndSave(SyntheticPdfFactory.untaggedWithIndirectSmallIntegers(), true),
        tagAndSave(SyntheticPdfFactory.untaggedWithIndirectSmallIntegers(), true));
  }

  private static byte[] tagAndSave(byte[] input, boolean copyWriter) throws Exception {
    try (PDDocument doc = Loader.loadPDF(input); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.getPage(0).getRotation(); // the input's own indirect objects are read before the copy is written
      ContentTagger.apply(doc, ContentTagger.analyze(doc));
      if (copyWriter) {
        CopyWriter.save(doc, out);
      } else {
        doc.save(out);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw e;
    }
  }
}
