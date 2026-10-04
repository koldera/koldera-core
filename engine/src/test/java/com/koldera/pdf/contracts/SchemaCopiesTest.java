package com.koldera.pdf.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The engine's classpath schemas must stay byte-identical to the repository contracts. */
class SchemaCopiesTest {
  @Test
  void engineSchemasMatchTheRepositoryContracts() throws Exception {
    for (String name :
        new String[] {
          "pdf-worker-request.schema.json",
          "pdf-worker-result.schema.json",
          "pdf-inspect-result.schema.json",
          "pdf-structure-result.schema.json",
          "pdf-reorder-request.schema.json",
          "pdf-reorder-result.schema.json"
        }) {
      byte[] repo = Files.readAllBytes(Path.of("..", "contracts", name));
      byte[] engine = Files.readAllBytes(Path.of("src", "main", "resources", "contracts", name));
      assertArrayEquals(repo, engine, name + " differs from contracts/" + name);
    }
  }
}
