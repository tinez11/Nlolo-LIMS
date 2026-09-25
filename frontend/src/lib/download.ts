/**
 * Hand a fetched file to the browser as a save.
 *
 * <b>Why this exists rather than an `<a href download>` pointing at the endpoint.</b> This SPA
 * holds its tokens in memory — never in a cookie and never in storage — so a plain anchor is an
 * anonymous request. Against the API it is a 401; against a relative path it is worse, because
 * the dev server answers every unknown path with `index.html` and the browser dutifully saves the
 * console's own HTML under the file name the person was expecting. That is not a failure anybody
 * reads as one: they get a file, it is the wrong file, and they forward it.
 *
 * So a download here is an ordinary authenticated request for a blob, and this turns the blob
 * into a save. `downloadClaimEvidence` already established the fetch half of that pattern.
 *
 * The object URL is revoked on the next tick rather than immediately: the click has to reach the
 * browser's download machinery first, and revoking in the same frame cancels it in Safari.
 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  // Appended, because Firefox ignores a click on an anchor that is not in the document.
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
