package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.xml.DomXmpParser;
import org.junit.jupiter.api.Test;

public class XmpPacketEditorTest {

  public static final String HEAD = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
      + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.6\">\n"
      + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
      + "  <rdf:Description rdf:about=\"uuid:1234\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"Adobe InDesign 19.5\">\n"
      + "   <xmp:Custom><rdf:Bag><rdf:li>kept &amp; untouched</rdf:li></rdf:Bag></xmp:Custom>\n"
      + "  </rdf:Description>\n";
  public static final String TAIL = " </rdf:RDF>\n</x:xmpmeta>\n" + " ".repeat(200) + "\n<?xpacket end=\"w\"?>";

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void readsWhatAPacketAlreadyHas() {
    XmpPacketEditor.Packet packet = XmpPacketEditor.read(bytes(HEAD + TAIL));
    assertNull(packet.unreadable());
    assertFalse(packet.hasTitle());
    assertFalse(packet.hasLanguage());

    String withBoth = HEAD.replace("xmp:CreatorTool=", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:language=\"en\" xmp:CreatorTool=")
        + "  <rdf:Description rdf:about=\"uuid:1234\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">T</rdf:li></rdf:Alt></dc:title></rdf:Description>\n"
        + TAIL;
    XmpPacketEditor.Packet both = XmpPacketEditor.read(bytes(withBoth));
    assertTrue(both.hasTitle(), "an element counts");
    assertTrue(both.hasLanguage(), "a simple property written as an attribute counts too");
  }

  @Test
  void addsTitleAndLanguageAsANewDescriptionWithoutChangingAnyExistingByte() throws IOException {
    String original = HEAD + TAIL;
    byte[] edited = XmpPacketEditor.add(bytes(original), "Annual R&D <report>", "uk");
    String text = new String(edited, StandardCharsets.UTF_8);

    int at = text.indexOf("<rdf:Description rdf:about=\"uuid:1234\" xmlns:rdf=");
    assertTrue(at > 0, text);
    int end = text.indexOf("</rdf:Description>\n", at) + "</rdf:Description>\n".length();
    assertEquals(original, text.substring(0, at) + text.substring(end), "only an insertion: every existing byte stays as it was");
    assertTrue(text.substring(at, end).contains("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Annual R&amp;D &lt;report&gt;</rdf:li></rdf:Alt></dc:title>"), text);
    assertTrue(text.substring(at, end).contains("<dc:language><rdf:Bag><rdf:li>uk</rdf:li></rdf:Bag></dc:language>"), text);
    assertEquals(end, text.indexOf("</rdf:RDF>"), "inserted inside rdf:RDF, right before it closes");

    XmpPacketEditor.Packet after = XmpPacketEditor.read(edited);
    assertTrue(after.hasTitle());
    assertTrue(after.hasLanguage());
  }

  @Test
  void theEditedPacketIsReadableByXmpbox() throws Exception {
    byte[] edited = XmpPacketEditor.add(bytes(HEAD.replace(" xmp:CreatorTool=\"Adobe InDesign 19.5\"", "").replace("   <xmp:Custom><rdf:Bag><rdf:li>kept &amp; untouched</rdf:li></rdf:Bag></xmp:Custom>\n", "") + TAIL), "Report", null);
    XMPMetadata xmp = new DomXmpParser().parse(edited);
    assertEquals("Report", xmp.getDublinCoreSchema().getTitle());
  }

  @Test
  void onlyWhatIsMissingAndKnownIsAdded() throws IOException {
    String text = new String(XmpPacketEditor.add(bytes(HEAD + TAIL), null, "ar"), StandardCharsets.UTF_8);
    assertFalse(text.contains("dc:title"), text);
    assertTrue(text.contains("<dc:language><rdf:Bag><rdf:li>ar</rdf:li></rdf:Bag></dc:language>"), text);
    assertThrows(IllegalArgumentException.class, () -> XmpPacketEditor.add(bytes(HEAD + TAIL), null, null));
  }

  @Test
  void anotherPrefixForRdfIsFollowed() throws IOException {
    String packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><R:RDF xmlns:R=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
        + "<R:Description R:about=\"\"/></R:RDF></x:xmpmeta>";
    String text = new String(XmpPacketEditor.add(bytes(packet), "T", null), StandardCharsets.UTF_8);
    assertTrue(text.endsWith("</rdf:Description>\n</R:RDF></x:xmpmeta>"), text);
    assertTrue(XmpPacketEditor.read(bytes(text)).hasTitle());
  }

  @Test
  void packetsItCannotEditSafelyAreRefusedWithAReason() {
    assertEquals("the packet is not well-formed XML", XmpPacketEditor.read(bytes("<x:xmpmeta><unclosed>")).unreadable());
    assertEquals("the packet has no rdf:RDF element", XmpPacketEditor.read(bytes("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")).unreadable());
    assertEquals("the packet is not UTF-8", XmpPacketEditor.read("<x:xmpmeta/>".getBytes(StandardCharsets.UTF_16LE)).unreadable());
    assertEquals("the packet is not UTF-8", XmpPacketEditor.read(new byte[] {'<', 'a', '>', (byte) 0xE9, '<', '/', 'a', '>'}).unreadable());
    assertEquals("the packet declares a DOCTYPE", XmpPacketEditor.read(bytes("<!DOCTYPE x [<!ENTITY e \"v\">]><x/>")).unreadable());
    String selfClosing = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>";
    assertEquals("the end of rdf:RDF cannot be located exactly once", XmpPacketEditor.read(bytes(selfClosing)).unreadable());
    String twice = HEAD + "<!-- </rdf:RDF> -->" + TAIL;
    assertEquals("the end of rdf:RDF cannot be located exactly once", XmpPacketEditor.read(bytes(twice)).unreadable());
    assertThrows(IOException.class, () -> XmpPacketEditor.add(bytes(twice), "T", null));
  }
}
