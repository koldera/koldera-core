package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

class MarkedContentScopeTest {

  @Test
  void aSequenceWithoutMcidSuchAsArtifactBmcIsTrackedWithoutFailing() {
    MarkedContentScope scope = new MarkedContentScope();
    COSDictionary p = new COSDictionary();
    p.setInt(COSName.MCID, 4);

    scope.begin(p);
    scope.begin(null); // /Artifact BMC: no property list
    assertEquals(4, scope.current(), "the enclosing MCID still applies");
    scope.end();
    assertEquals(4, scope.current());
    scope.end();
    assertNull(scope.current());
    scope.begin(null);
    assertNull(scope.current());
  }
}
