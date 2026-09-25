import { afterEach, describe, expect, it, vi } from 'vitest';
import { saveBlob } from './download';

describe('saveBlob', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  function stubObjectUrl(): { create: ReturnType<typeof vi.fn>; revoke: ReturnType<typeof vi.fn> } {
    // jsdom implements neither, so they are defined rather than spied.
    const create = vi.fn(() => 'blob:stub');
    const revoke = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { value: create, configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true });
    return { create, revoke };
  }

  it('clicks an anchor carrying the blob and the file name', () => {
    stubObjectUrl();
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this);
    });

    saveBlob(new Blob(['line'], { type: 'text/csv' }), 'enrolment-report-7.csv');

    expect(clicked).toHaveLength(1);
    expect(clicked[0]?.download).toBe('enrolment-report-7.csv');
    expect(clicked[0]?.href).toContain('blob:stub');
  });

  it('is in the document when it is clicked, and gone afterwards', () => {
    stubObjectUrl();
    let attachedWhenClicked = false;
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      // Firefox ignores a click on a detached anchor, so this is the load-bearing half.
      attachedWhenClicked = document.body.contains(this);
    });

    saveBlob(new Blob(['line']), 'report.csv');

    expect(attachedWhenClicked).toBe(true);
    expect(document.querySelectorAll('a')).toHaveLength(0);
  });

  it('revokes the object URL after the click, not during it', () => {
    vi.useFakeTimers();
    const { revoke } = stubObjectUrl();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    saveBlob(new Blob(['line']), 'report.csv');

    // Revoking in the same frame cancels the download in Safari.
    expect(revoke).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(revoke).toHaveBeenCalledWith('blob:stub');
  });
});
