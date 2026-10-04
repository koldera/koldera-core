package com.koldera.pdf.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.language.ScriptDetector.Detection;
import com.koldera.pdf.language.ScriptDetector.Script;
import org.junit.jupiter.api.Test;

/** UDHR article 1 in each language (public text), repeated to pass the 200-letter minimum. */
public class ScriptDetectorTest {

  public static final String UK = "Всі люди народжуються вільними і рівними у своїй гідності та правах. Вони наділені розумом і совістю"
      + " і повинні діяти у відношенні один до одного в дусі братерства. ";
  public static final String RU = "Все люди рождаются свободными и равными в своем достоинстве и правах. Они наделены разумом и совестью"
      + " и должны поступать в отношении друг друга в духе братства. ";
  public static final String BG = "Всички хора се раждат свободни и равни по достойнство и права. Те са надарени с разум и съвест и следва"
      + " да се отнасят помежду си в дух на братство. ";
  public static final String AR = "يولد جميع الناس أحرارًا متساوين في الكرامة والحقوق. وقد وهبوا عقلاً وضميرًا وعليهم أن يعامل بعضهم بعضًا"
      + " بروح الإخاء. ";
  public static final String FA = "تمام افراد بشر آزاد به دنیا می‌آیند و از لحاظ حیثیت و حقوق با هم برابرند. همه دارای عقل و وجدان هستند"
      + " و باید نسبت به یکدیگر با روح برادری رفتار کنند. ";
  public static final String UR = "تمام انسان آزاد اور حقوق و عزت کے اعتبار سے برابر پیدا ہوئے ہیں۔ انہیں ضمیر اور عقل ودیعت ہوئی ہے۔ اس لئے"
      + " انہیں ایک دوسرے کے ساتھ بھائی چارے کا سلوک کرنا چاہیئے۔ ";
  public static final String HE = "כל בני האדם נולדו בני חורין ושווים בערכם ובזכויותיהם. כולם חוננו בתבונה ובמצפון, לפיכך חובה עליהם"
      + " לנהוג איש ברעהו ברוח של אחוה. ";
  public static final String JA = "すべての人間は、生まれながらにして自由であり、かつ、尊厳と権利とについて平等である。人間は、理性と良心とを授けられており、"
      + "互いに同胞の精神をもって行動しなければならない。";
  public static final String ZH = "人人生而自由，在尊严和权利上一律平等。他们赋有理性和良心，并应以兄弟关系的精神相对待。";
  public static final String KO = "모든 인간은 태어날 때부터 자유로우며 그 존엄과 권리에 있어 동등하다. 인간은 천부적으로 이성과 양심을 부여받았으며"
      + " 서로 형제애의 정신으로 행동하여야 한다. ";
  public static final String EL = "Όλοι οι άνθρωποι γεννιούνται ελεύθεροι και ίσοι στην αξιοπρέπεια και τα δικαιώματα. Είναι προικισμένοι"
      + " με λογική και συνείδηση, και οφείλουν να συμπεριφέρονται μεταξύ τους με πνεύμα αδελφοσύνης. ";
  public static final String EN = "All human beings are born free and equal in dignity and rights. They are endowed with reason and"
      + " conscience and should act towards one another in a spirit of brotherhood. ";

  private static Detection detect(String sample, int times) {
    return ScriptDetector.detect(sample.repeat(times));
  }

  @Test
  void cyrillicTextIsToldApartByLettersOnlyOneLanguageUses() {
    Detection uk = detect(UK, 2);
    assertEquals(Script.CYRILLIC, uk.script());
    assertEquals("uk", uk.language(), uk.note());
    assertTrue(uk.letters() >= 200);
    assertEquals(1.0, uk.share(), 1e-9);
    assertEquals("ru", detect(RU, 4).language(), "a few telling letters are not enough: four repetitions carry twelve");
    Detection bg = detect(BG, 3);
    assertEquals(Script.CYRILLIC, bg.script());
    assertNull(bg.language(), "no letter tells Bulgarian from Russian or Ukrainian apart here; nothing is guessed");
    assertEquals("Cyrillic text without enough letters that only one supported language uses", bg.note());
  }

  @Test
  void arabicScriptTextIsToldApartByLettersOnlyOneLanguageUses() {
    assertEquals("ar", detect(AR, 3).language(), detect(AR, 3).note());
    assertEquals("fa", detect(FA, 3).language(), detect(FA, 3).note());
    assertEquals("ur", detect(UR, 3).language(), detect(UR, 3).note());
    assertEquals(Script.ARABIC, detect(AR, 3).script());
  }

  @Test
  void persianTypedWithArabicYehAndKafIsNotNamed() {
    // Older Persian text often uses the Arabic ي (U+064A) and ك (U+0643) for ی and ک.
    String persianWithArabicLetters = FA.replace('\u06CC', '\u064A').replace('\u06A9', '\u0643');
    Detection d = detect(persianWithArabicLetters, 3);
    assertEquals(Script.ARABIC, d.script());
    assertNull(d.language(), "the Persian-only letters (گ) and the Arabic-only ones (ي ك) are both present: no language is named");
    assertEquals("Arabic-script text without enough letters that only one supported language uses", d.note());
  }

  @Test
  void arabicPresentationFormsCountAsTheLettersTheyShow() {
    // Glyph-level code points (U+FExx) as many PDF producers map them.
    String forms = "ﻳﻮﻟﺪ ﺟﻤﻴﻨ ﺎﻟﻨﺎﺳ ﺓ ﻲ ".repeat(30);
    Detection d = ScriptDetector.detect(forms);
    assertEquals(Script.ARABIC, d.script());
    assertEquals("ar", d.language(), d.note());
  }

  @Test
  void otherScriptsMapToTheirOneLanguage() {
    assertEquals("he", detect(HE, 3).language());
    assertEquals("ja", detect(JA, 3).language());
    assertEquals("zh", detect(ZH, 6).language());
    assertEquals("ko", detect(KO, 4).language());
    assertEquals("el", detect(EL, 2).language());
    assertEquals(Script.CJK, detect(JA, 3).script());
  }

  @Test
  void latinTextHasAScriptButNoLanguage() {
    Detection en = detect(EN, 2);
    assertEquals(Script.LATIN, en.script());
    assertNull(en.language());
    assertEquals("Latin script is shared by many languages; the language is not detected from it", en.note());
  }

  @Test
  void tooLittleOrMixedTextGivesNoScript() {
    Detection little = ScriptDetector.detect(UK.substring(0, 100));
    assertNull(little.script());
    assertEquals("too little text to tell the script (fewer than 200 letters)", little.note());

    Detection mixed = ScriptDetector.detect(EN.repeat(2) + UK.repeat(2));
    assertNull(mixed.script());
    assertTrue(mixed.note().startsWith("no script has 90% of the letters"), mixed.note());
  }
}
