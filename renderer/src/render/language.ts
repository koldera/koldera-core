/**
 * The single language tag of a `<meta http-equiv="content-language">` pragma, as HTML sets the pragma-set default
 * language (a list of tags sets none), or null. Shared by preflight (the HTML_LANG_MISSING wording) and the offline
 * preparation, which applies the same rule inside the page: `page.evaluate` cannot call imported code, so offline.ts
 * receives {@link CONTENT_LANGUAGE_TAG}'s source and repeats the one-line rule — keep both in step.
 */
export const CONTENT_LANGUAGE_TAG = /^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$/;

export function contentLanguageTag(content: string): string | null {
  const value = content.trim();
  return !value.includes(",") && CONTENT_LANGUAGE_TAG.test(value) ? value : null;
}
