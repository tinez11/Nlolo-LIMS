import { describe, expect, it } from 'vitest';
import {
  blankAnswer,
  completeAnswers,
  isComplete,
  toRecordDisclosuresRequest,
  type AnswerDraft,
} from './disclosureForm';

const filled = (over: Partial<AnswerDraft> = {}): AnswerDraft => ({
  questionCode: 'Q1',
  question: 'Have you ever been treated for heart disease?',
  answer: 'No',
  notes: '',
  ...over,
});

describe('isComplete', () => {
  it('accepts a row with a code, a question and an answer', () => {
    expect(isComplete(filled())).toBe(true);
  });

  it('accepts a row with no notes -- the one optional field', () => {
    expect(isComplete(filled({ notes: '' }))).toBe(true);
  });

  it('rejects a blank row, which is what "Add a declaration" creates', () => {
    expect(isComplete(blankAnswer())).toBe(false);
  });

  it.each([
    ['question code', { questionCode: '' }],
    ['question wording', { question: '' }],
    ['answer', { answer: '' }],
  ])('rejects a row missing its %s', (_label, over) => {
    expect(isComplete(filled(over))).toBe(false);
  });

  it.each([
    ['question code', { questionCode: '   ' }],
    ['question wording', { question: '   ' }],
    ['answer', { answer: '   ' }],
  ])('rejects a row whose %s is only whitespace', (_label, over) => {
    expect(isComplete(filled(over))).toBe(false);
  });
});

describe('completeAnswers', () => {
  it('drops incomplete rows and keeps the rest in order', () => {
    const rows = [filled({ questionCode: 'Q1' }), blankAnswer(), filled({ questionCode: 'Q2' })];
    expect(completeAnswers(rows).map((r) => r.questionCode)).toEqual(['Q1', 'Q2']);
  });

  it('is empty when nothing has been typed', () => {
    expect(completeAnswers([blankAnswer(), blankAnswer()])).toEqual([]);
  });
});

describe('toRecordDisclosuresRequest', () => {
  it('trims every field it sends', () => {
    const request = toRecordDisclosuresRequest([
      filled({ questionCode: '  Q1  ', question: '  Do you smoke?  ', answer: '  Yes  ' }),
    ]);
    expect(request.answers[0]).toEqual({
      questionCode: 'Q1',
      question: 'Do you smoke?',
      answer: 'Yes',
    });
  });

  it('omits notes entirely when blank, rather than sending an empty string', () => {
    const request = toRecordDisclosuresRequest([filled({ notes: '   ' })]);
    expect('notes' in request.answers[0]).toBe(false);
  });

  it('sends notes when there are any', () => {
    const request = toRecordDisclosuresRequest([filled({ notes: ' Volunteered ' })]);
    expect(request.answers[0].notes).toBe('Volunteered');
  });

  it('never sends a row missing the wording the applicant was actually read', () => {
    const request = toRecordDisclosuresRequest([filled({ question: '' }), filled()]);
    expect(request.answers).toHaveLength(1);
    expect(request.answers[0].question).toBe('Have you ever been treated for heart disease?');
  });
});
