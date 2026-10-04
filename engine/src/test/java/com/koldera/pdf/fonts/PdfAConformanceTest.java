package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.OptionalInt;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;

class PdfAConformanceTest {

  private static OptionalInt part(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return PdfAConformance.declaredPart(doc);
    }
  }

  @Test
  void readsPart() throws IOException {
    byte[] plain = SyntheticPdfFactory.untaggedArticle();
    assertEquals(OptionalInt.of(1), part(SyntheticPdfFactory.withXmpPacket(plain, SyntheticPdfFactory.pdfAPacket(1))));
    assertEquals(OptionalInt.of(2), part(SyntheticPdfFactory.withXmpPacket(plain, SyntheticPdfFactory.pdfAPacket(2))));
    assertEquals(OptionalInt.empty(), part(plain));
    assertEquals(OptionalInt.empty(), part(SyntheticPdfFactory.withXmpPacket(plain, SyntheticPdfFactory.EMPTY_DC_TITLE_PACKET)));
    try (PDDocument doc = Loader.loadPDF(plain)) {
      doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream("<x:xmpmeta><not closed".getBytes(UTF_8))));
      assertEquals(OptionalInt.empty(), PdfAConformance.declaredPart(doc));
    }
  }
}
