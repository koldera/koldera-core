package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SubstituteEmbedderTest {

  @Test
  void subsetTagIsDeterministicAndUppercase() throws Exception {
    byte[] program = {1, 2, 3, 4};
    SortedSet<Integer> codePoints = new TreeSet<>(java.util.List.of(72, 32, 101));
    String tag = SubstituteEmbedder.subsetTag(program, codePoints);
    assertTrue(tag.matches("^[A-Z]{6}$"), tag);
    assertEquals(tag, SubstituteEmbedder.subsetTag(program.clone(), new TreeSet<>(codePoints)));
    assertNotEquals(tag, SubstituteEmbedder.subsetTag(program, new TreeSet<>(java.util.List.of(72, 32))));
    // SHA-256(program ‖ "32,72,101"), first six bytes, 'A' + b % 26.
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    sha.update(program);
    byte[] digest = sha.digest("32,72,101".getBytes(StandardCharsets.UTF_8));
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      expected.append((char) ('A' + (digest[i] & 0xFF) % 26));
    }
    assertEquals(expected.toString(), tag);
  }
}
