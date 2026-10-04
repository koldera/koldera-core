package com.koldera.pdf.language;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.Map;

/**
 * Tells the writing system of a text from the Unicode script of its letters, offline and without a model (NFKC first). A
 * script is reported only with enough letters and a dominant share. A language is named only where the script, or letters
 * that exactly one supported language uses, decides: Cyrillic uk/ru, Arabic script ar/fa/ur, Hebrew, Greek, Thai, CJK
 * ko/ja/zh; Latin is never a language. The letter lists are this project's own (no third-party data or code).
 */
public final class ScriptDetector {

  public static final int MIN_LETTERS = 200;
  public static final double MIN_SHARE = 0.9;
  private static final int MIN_TELLING_LETTERS = 10;

  public enum Script {
    LATIN("Latin"), CYRILLIC("Cyrillic"), GREEK("Greek"), ARABIC("Arabic"), HEBREW("Hebrew"), CJK("Chinese/Japanese/Korean"), THAI("Thai"), OTHER("other");

    private final String label;

    Script(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * Detection: dominant script (null if none), letters counted, its share, the one supported language shown (or null), and a
   * note on why no script or language was named.
   */
  public record Detection(Script script, int letters, double share, String language, String note) {}

  private ScriptDetector() {}

  public static Detection detect(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
    Map<Script, Integer> byScript = new EnumMap<>(Script.class);
    Counts counts = new Counts();
    int letters = 0;
    for (int i = 0; i < normalized.length(); ) {
      int cp = normalized.codePointAt(i);
      i += Character.charCount(cp);
      if (!Character.isLetter(cp)) {
        continue;
      }
      Script script = scriptOf(cp, counts);
      if (script == null) {
        continue;
      }
      letters++;
      byScript.merge(script, 1, Integer::sum);
      counts.telling(cp);
    }
    if (letters < MIN_LETTERS) {
      return new Detection(null, letters, 0, null, "too little text to tell the script (fewer than " + MIN_LETTERS + " letters)");
    }
    Script top = null;
    int topCount = 0;
    for (Map.Entry<Script, Integer> e : byScript.entrySet()) {
      if (e.getValue() > topCount) {
        top = e.getKey();
        topCount = e.getValue();
      }
    }
    double share = (double) topCount / letters;
    if (top == null || share < MIN_SHARE) {
      return new Detection(null, letters, share, null, String.format(java.util.Locale.ROOT, "no script has 90%% of the letters (largest: %s, %.0f%%)", top == null ? "none" : top.label(), share * 100));
    }
    return language(top, letters, share, counts);
  }

  private static Detection language(Script script, int letters, double share, Counts c) {
    return switch (script) {
      case LATIN -> new Detection(script, letters, share, null, "Latin script is shared by many languages; the language is not detected from it");
      case GREEK -> new Detection(script, letters, share, "el", null);
      case THAI -> new Detection(script, letters, share, "th", null);
      case HEBREW -> c.yiddish * 100 >= c.hebrew
          ? new Detection(script, letters, share, null, "Hebrew-script text with Yiddish ligatures; the language is not decided")
          : new Detection(script, letters, share, "he", null);
      case CYRILLIC -> telling(script, letters, share, "Cyrillic", c.ukrainian, "uk", c.russian, "ru", c.otherCyrillic);
      case ARABIC -> arabic(letters, share, c);
      case CJK -> cjk(letters, share, c);
      case OTHER -> new Detection(script, letters, share, null, "the script is not one this engine names a language for");
    };
  }

  private static Detection telling(Script script, int letters, double share, String label, int a, String aLang, int b, String bLang, int others) {
    int total = a + b + others;
    if (total >= MIN_TELLING_LETTERS && a >= MIN_SHARE * total) {
      return new Detection(script, letters, share, aLang, null);
    }
    if (total >= MIN_TELLING_LETTERS && b >= MIN_SHARE * total) {
      return new Detection(script, letters, share, bLang, null);
    }
    return new Detection(script, letters, share, null, label + " text without enough letters that only one supported language uses");
  }

  private static Detection arabic(int letters, double share, Counts c) {
    int total = c.arabicOnly + c.persianUrdu + c.urduOnly;
    if (total >= MIN_TELLING_LETTERS) {
      // Persian and Urdu are often typed with the Arabic yeh and kaf; their own letters (پ چ ژ گ, Urdu letters) rule out Arabic.
      if (c.arabicOnly >= MIN_SHARE * total && (c.persianShapes + c.urduOnly) * 1000 < c.arabic) {
        return new Detection(Script.ARABIC, letters, share, "ar", null);
      }
      if (c.urduOnly * 100 >= c.arabic && c.persianUrdu + c.urduOnly >= MIN_SHARE * total) {
        return new Detection(Script.ARABIC, letters, share, "ur", null);
      }
      if (c.urduOnly == 0 && c.persianUrdu >= MIN_SHARE * total) {
        return new Detection(Script.ARABIC, letters, share, "fa", null);
      }
    }
    return new Detection(Script.ARABIC, letters, share, null, "Arabic-script text without enough letters that only one supported language uses");
  }

  private static Detection cjk(int letters, double share, Counts c) {
    int group = c.han + c.kana + c.hangul;
    if (c.hangul >= MIN_SHARE * group) {
      return new Detection(Script.CJK, letters, share, "ko", null);
    }
    if (c.hangul * 100 < group) {
      if (c.kana * 10 >= c.han + c.kana) {
        return new Detection(Script.CJK, letters, share, "ja", null);
      }
      if (c.kana * 100 < c.han + c.kana) {
        return new Detection(Script.CJK, letters, share, "zh", null);
      }
    }
    return new Detection(Script.CJK, letters, share, null, "Han, kana and Hangul are mixed in proportions that do not name one language");
  }

  private static Script scriptOf(int cp, Counts counts) {
    Character.UnicodeScript s;
    try {
      s = Character.UnicodeScript.of(cp);
    } catch (IllegalArgumentException e) {
      return null;
    }
    switch (s) {
      case LATIN:
        return Script.LATIN;
      case CYRILLIC:
        return Script.CYRILLIC;
      case GREEK:
        return Script.GREEK;
      case ARABIC:
        counts.arabic++;
        return Script.ARABIC;
      case HEBREW:
        counts.hebrew++;
        return Script.HEBREW;
      case HAN:
        counts.han++;
        return Script.CJK;
      case HIRAGANA:
      case KATAKANA:
        counts.kana++;
        return Script.CJK;
      case HANGUL:
        counts.hangul++;
        return Script.CJK;
      case THAI:
        return Script.THAI;
      case COMMON:
      case INHERITED:
      case UNKNOWN:
        return null;
      default:
        return Script.OTHER;
    }
  }

  /** Per-script tallies and the telling letters (text already lower-cased). */
  private static final class Counts {
    int arabic;
    int hebrew;
    int han;
    int kana;
    int hangul;
    int ukrainian;
    int russian;
    int otherCyrillic;
    int arabicOnly;
    int persianUrdu;
    int persianShapes;
    int urduOnly;
    int yiddish;

    void telling(int cp) {
      switch (cp) {
        case 'і', 'ї', 'є', 'ґ' -> ukrainian++;
        case 'ы', 'э', 'ё' -> russian++;
        case 'ў', 'ђ', 'ј', 'љ', 'њ', 'ћ', 'џ', 'ѓ', 'ќ', 'ѕ', 'ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ', 'ҳ', 'ҷ', 'ӣ', 'ӯ' -> otherCyrillic++;
        case 0x0629, 0x0649, 0x064A, 0x0643 -> arabicOnly++;
        case 0x067E, 0x0686, 0x0698, 0x06AF -> {
          persianUrdu++;
          persianShapes++;
        }
        case 0x06CC, 0x06A9 -> persianUrdu++;
        case 0x0679, 0x0688, 0x0691, 0x06BA, 0x06BE, 0x06C1, 0x06D2 -> urduOnly++;
        case 0x05F0, 0x05F1, 0x05F2 -> yiddish++;
        default -> {
          // not a telling letter
        }
      }
    }
  }
}
