package com.koldera.pdf.structure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One structure element in stored (pre-order) sequence. */
public record StructureNode(
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("parentId") String parentId,
    @JsonProperty("role") String role,
    @JsonProperty("logicalIndex") int logicalIndex,
    @JsonProperty("pageNumber") Integer pageNumber,
    @JsonProperty("textPreview") String textPreview,
    @JsonProperty("boundingBox") Box boundingBox,
    @JsonProperty("private") PrivateMapping privateMapping) {

  public record Box(@JsonProperty("x") double x, @JsonProperty("y") double y, @JsonProperty("width") double width, @JsonProperty("height") double height) {}

  /**
   * Per node: kid-index path from the root, {@code "num gen"} object reference (or null), kid kinds (none, elements, content,
   * mixed), distinct 1-based pages, MCID and OBJR reference counts and direct element kids of the subtree.
   */
  public record PrivateMapping(
      @JsonProperty("path") List<Integer> path,
      @JsonProperty("objectRef") String objectRef,
      @JsonProperty("kidKinds") String kidKinds,
      @JsonProperty("pages") List<Integer> pages,
      @JsonProperty("contentRefs") int contentRefs,
      @JsonProperty("objectRefs") int objectRefs,
      @JsonProperty("elementKids") int elementKids) {}
}
