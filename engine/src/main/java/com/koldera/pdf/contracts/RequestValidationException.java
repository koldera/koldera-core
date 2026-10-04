package com.koldera.pdf.contracts;

/** A worker request that does not satisfy the request schema or additional path constraints. */
public final class RequestValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RequestValidationException(String message) {
    super(message);
  }

  public RequestValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
