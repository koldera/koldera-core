package com.koldera.pdf.rules;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Adds {@code dc:title} and/or {@code dc:language} to an existing XMP packet by inserting one new {@code rdf:Description} before
 * the {@code rdf:RDF} end tag. Every existing byte stays where it was (nothing is re-serialized). A packet that is not UTF-8
 * or well-formed, has no single {@code rdf:RDF}, a DOCTYPE, or an end tag not found exactly once is left alone with a reason.
 * The edited packet is parsed again and must have gained exactly the new elements. {@link #withoutProperties} removes, likewise.
 */
final class XmpPacketEditor {

  static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  static final String DC_NS = "http://purl.org/dc/elements/1.1/";
  private static final Pattern ENCODING = Pattern.compile("^\\s*(?:\\uFEFF)?<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']");

  private XmpPacketEditor() {}

  /** What a packet already has; {@code unreadable} is the reason it cannot be edited, else null. */
  record Packet(String unreadable, boolean hasTitle, boolean hasLanguage, String about, String rdfEndTag, int elements, boolean titleBlank) {
    static Packet refused(String reason) {
      return new Packet(reason, false, false, null, null, 0, false);
    }
  }

  static Packet read(byte[] packet) {
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(packet))
          .toString();
    } catch (CharacterCodingException e) {
      return Packet.refused("the packet is not UTF-8");
    }
    if (text.indexOf('\u0000') >= 0) {
      return Packet.refused("the packet is not UTF-8");
    }
    Matcher declared = ENCODING.matcher(text);
    if (declared.find() && !declared.group(1).equalsIgnoreCase("UTF-8") && !declared.group(1).equalsIgnoreCase("UTF8")) {
      return Packet.refused("the packet is not UTF-8");
    }
    if (text.contains("<!DOCTYPE")) {
      return Packet.refused("the packet declares a DOCTYPE");
    }
    Document dom;
    try {
      dom = parse(packet);
    } catch (SAXException | IOException e) {
      return Packet.refused("the packet is not well-formed XML");
    }
    NodeList rdfs = dom.getElementsByTagNameNS(RDF_NS, "RDF");
    if (rdfs.getLength() != 1) {
      return Packet.refused(rdfs.getLength() == 0 ? "the packet has no rdf:RDF element" : "the packet has more than one rdf:RDF element");
    }
    Element rdf = (Element) rdfs.item(0);
    String endTag = "</" + (rdf.getPrefix() == null ? "" : rdf.getPrefix() + ":") + "RDF";
    int first = text.indexOf(endTag);
    if (first < 0 || text.indexOf(endTag, first + 1) >= 0) {
      return Packet.refused("the end of rdf:RDF cannot be located exactly once");
    }
    String about = "";
    NodeList descriptions = rdf.getElementsByTagNameNS(RDF_NS, "Description");
    if (descriptions.getLength() > 0) {
      Element description = (Element) descriptions.item(0);
      if (description.hasAttributeNS(RDF_NS, "about")) {
        about = description.getAttributeNS(RDF_NS, "about");
      }
    }
    boolean title = has(dom, "title");
    return new Packet(null, title, has(dom, "language"), about, endTag, dom.getElementsByTagName("*").getLength(), title && !hasText(dom, "title"));
  }

  /**
   * The packet with the given properties added (each may be null, not both). Throws when the packet
   * cannot be edited safely or the result does not read back as exactly the original plus the new
   * elements.
   */
  static byte[] add(byte[] packet, String title, String language) throws IOException {
    if (title == null && language == null) {
      throw new IllegalArgumentException("nothing to add");
    }
    Packet before = read(packet);
    if (before.unreadable() != null) {
      throw new IOException(before.unreadable());
    }
    String text = new String(packet, StandardCharsets.UTF_8);
    StringBuilder insert = new StringBuilder("<rdf:Description rdf:about=\"")
        .append(XmpMetadataRule.escape(before.about()))
        .append("\" xmlns:rdf=\"").append(RDF_NS).append("\" xmlns:dc=\"").append(DC_NS).append("\">\n");
    int added = 1;
    if (title != null) {
      insert.append("   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(XmpMetadataRule.escape(title)).append("</rdf:li></rdf:Alt></dc:title>\n");
      added += 3;
    }
    if (language != null) {
      insert.append("   <dc:language><rdf:Bag><rdf:li>").append(XmpMetadataRule.escape(language)).append("</rdf:li></rdf:Bag></dc:language>\n");
      added += 3;
    }
    insert.append("</rdf:Description>\n");
    int at = text.indexOf(before.rdfEndTag());
    byte[] edited = (text.substring(0, at) + insert + text.substring(at)).getBytes(StandardCharsets.UTF_8);
    Packet after = read(edited);
    if (after.unreadable() != null
        || after.elements() != before.elements() + added
        || (title != null && !after.hasTitle())
        || (language != null && !after.hasLanguage())) {
      throw new IOException("the edited packet did not read back as the original plus the added properties");
    }
    return edited;
  }

  /**
   * The packet without its properties in {@code namespace} (simple elements or RDF short-form attributes); every other byte
   * stays, the namespace declaration too. {@code null} when there are none. Throws when the packet cannot be edited safely or
   * the result does not read back as the original minus exactly those properties (same other elements, attributes and text).
   */
  static byte[] withoutProperties(byte[] packet, String namespace) throws IOException {
    Packet before = read(packet);
    if (before.unreadable() != null) {
      throw new IOException(before.unreadable());
    }
    Document dom = parseOrThrow(packet);
    int elements = dom.getElementsByTagNameNS(namespace, "*").getLength();
    int attributes = attributesIn(dom, namespace);
    if (elements + attributes == 0) {
      return null;
    }
    java.util.Set<String> prefixes = new java.util.TreeSet<>();
    NodeList all = dom.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      if (namespace.equals(element.getNamespaceURI())) {
        prefixes.add(element.getPrefix() == null ? "" : element.getPrefix());
      }
      NamedNodeMap attrs = element.getAttributes();
      for (int j = 0; j < attrs.getLength(); j++) {
        if (namespace.equals(attrs.item(j).getNamespaceURI())) {
          prefixes.add(attrs.item(j).getPrefix() == null ? "" : attrs.item(j).getPrefix());
        }
      }
    }
    if (prefixes.contains("")) {
      throw new IOException("the properties use a default namespace and cannot be located exactly");
    }
    String text = new String(packet, StandardCharsets.UTF_8);
    for (String prefix : prefixes) {
      String p = Pattern.quote(prefix);
      text = text.replaceAll("<" + p + ":([A-Za-z_][\\w.-]*)(?:\\s[^<>]*)?(?:/>|>[^<]*</" + p + ":\\1\\s*>)", "");
      text = text.replaceAll("\\s" + p + ":[A-Za-z_][\\w.-]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*')", "");
    }
    byte[] edited = text.getBytes(StandardCharsets.UTF_8);
    Document after = parseOrThrow(edited);
    for (int i = dom.getElementsByTagNameNS(namespace, "*").getLength() - 1; i >= 0; i--) {
      org.w3c.dom.Node gone = dom.getElementsByTagNameNS(namespace, "*").item(i);
      gone.getParentNode().removeChild(gone);
    }
    if (read(edited).unreadable() != null
        || after.getElementsByTagNameNS(namespace, "*").getLength() != 0 || attributesIn(after, namespace) != 0
        || after.getElementsByTagName("*").getLength() != before.elements() - elements
        || attributesIn(after, null) != attributesIn(dom, null) - attributes
        || !after.getDocumentElement().getTextContent().equals(dom.getDocumentElement().getTextContent())) {
      throw new IOException("the edited packet did not read back as the original minus the removed properties");
    }
    return edited;
  }

  /** Attributes in {@code namespace} on any element; every attribute when {@code namespace} is null. */
  private static int attributesIn(Document dom, String namespace) {
    int count = 0;
    NodeList all = dom.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      NamedNodeMap attrs = all.item(i).getAttributes();
      for (int j = 0; j < attrs.getLength(); j++) {
        if (namespace == null || namespace.equals(attrs.item(j).getNamespaceURI())) {
          count++;
        }
      }
    }
    return count;
  }

  private static Document parseOrThrow(byte[] packet) throws IOException {
    try {
      return parse(packet);
    } catch (SAXException e) {
      throw new IOException("the packet is not well-formed XML", e);
    }
  }

  /** A dc property present as an element anywhere, or as an attribute of any element (the RDF short form). */
  private static boolean has(Document dom, String property) {
    if (dom.getElementsByTagNameNS(DC_NS, property).getLength() > 0) {
      return true;
    }
    NodeList all = dom.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      NamedNodeMap attributes = all.item(i).getAttributes();
      for (int j = 0; attributes != null && j < attributes.getLength(); j++) {
        Attr attribute = (Attr) attributes.item(j);
        if (DC_NS.equals(attribute.getNamespaceURI()) && property.equals(attribute.getLocalName())) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether any occurrence of the dc property (element or short-form attribute) has non-whitespace text. */
  private static boolean hasText(Document dom, String property) {
    NodeList elements = dom.getElementsByTagNameNS(DC_NS, property);
    for (int i = 0; i < elements.getLength(); i++) {
      if (!elements.item(i).getTextContent().isBlank()) {
        return true;
      }
    }
    NodeList all = dom.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      NamedNodeMap attributes = all.item(i).getAttributes();
      for (int j = 0; attributes != null && j < attributes.getLength(); j++) {
        Attr attribute = (Attr) attributes.item(j);
        if (DC_NS.equals(attribute.getNamespaceURI()) && property.equals(attribute.getLocalName()) && !attribute.getValue().isBlank()) {
          return true;
        }
      }
    }
    return false;
  }

  private static Document parse(byte[] packet) throws SAXException, IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
        @Override
        public void warning(org.xml.sax.SAXParseException e) {
          // a warning does not make the packet unreadable
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }
      });
      return builder.parse(new ByteArrayInputStream(packet));
    } catch (ParserConfigurationException e) {
      throw new IOException("XML parser unavailable", e);
    }
  }
}
