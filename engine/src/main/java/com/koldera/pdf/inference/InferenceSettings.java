package com.koldera.pdf.inference;

/**
 * Every threshold the structure strategy and inference read, in one place and pinned by a test ({@code InferenceSettingsTest}).
 * Distances are in PDF points; a {@code *Factor} multiplies the font size (or the median line gap where named); shares and
 * confidences are fractions of 1. Groups: strategy limits, line/block formation, running headers/footers, columns,
 * headings, lists, tables and captions.
 */
public record InferenceSettings(
    double minTaggedTextCoverage,
    double maxLargestElementShare,
    int largestElementMinMcids,
    int maxPages,
    int maxTextRuns,
    float sameLineFactor,
    float blockGapFactor,
    float blockLeftEdgeFactor,
    float blockFontSizeChange,
    double runningMinPageShare,
    int runningMinPages,
    float runningPositionTolerance,
    float columnGapFactor,
    int columnMinLines,
    float headingSizeRatio,
    int headingMaxLines,
    int headingMaxChars,
    float listIndentTolerance,
    int listMinItems,
    int tableMinAlignedRows,
    int tableMinColumns,
    float tableColumnTolerance,
    float rulingMaxThickness,
    float captionMaxDistance,
    double minConfidence,
    double columnRowAlignedShare,
    int columnMinLineChars,
    double columnMinLineFill,
    double columnMaxGutterShare,
    int runningMaxLines,
    int runningMaxChars,
    int runningNumericMaxChars,
    float listLabelGapFactor,
    float superscriptRiseFactor,
    float listItemGapFactor,
    int headingBoldMaxChars,
    float headingBoldFollowFactor,
    int headingBoldRunMax,
    double headingMinLetterShare,
    double paragraphEndLineShare,
    float tableCellGapFactor,
    float tableRowGapFactor,
    int captionMaxLines) {

  public static final InferenceSettings DEFAULTS = new InferenceSettings(
      0.90, 0.50, 20, 1000, 200000,
      0.25f, 1.5f, 2.0f, 0.15f,
      0.60, 3, 2.0f,
      2.0f, 3,
      1.2f, 2, 200,
      2.0f, 2,
      3, 2, 2.0f, 1.5f,
      24.0f,
      0.70,
      0.50, 20, 0.60, 0.50,
      2, 100, 12,
      4.0f, 0.5f, 2.0f,
      80, 1.5f, 3, 0.5, 0.85,
      0.6f, 3.5f, 3);
}
