package com.koldera.pdf.structure;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-page invariants for a tag-only change: raw content stream bytes, the multiset of extracted
 * text tokens (order-independent by design — a corrected order changes the linear digest and must
 * not count as a regression), annotation count and MCID count.
 */
public record PageDigest(
    @JsonProperty("page") int page,
    @JsonProperty("contentStreamSha256") String contentStreamSha256,
    @JsonProperty("textTokenMultisetSha256") String textTokenMultisetSha256,
    @JsonProperty("annotationCount") int annotationCount,
    @JsonProperty("mcidCount") int mcidCount) {}
