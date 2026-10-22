import { describe, expect, it } from 'vitest';
import {
  bandNotes,
  bandsFromRows,
  blankBand,
  cellOrder,
  editCell,
  generateBands,
  isBlock,
  pasteBlock,
  rowsFromBands,
} from './baseRateBands';

describe('the base rate grid', () => {
  it('holds one band as the female and male rows the server takes, and reads them back', () => {
    let bands = [blankBand('18', '30')];
    bands = editCell(bands, 0, 'female.nonSmoker', '1.2');
    bands = editCell(bands, 0, 'male.smoker', '2.5');
    const rows = rowsFromBands(bands);

    expect(rows).toEqual([
      { ageFrom: '18', ageTo: '30', termFromMonths: '', termToMonths: '', sex: 'FEMALE', nonSmoker: '1.2', smoker: '', unknown: '' },
      { ageFrom: '18', ageTo: '30', termFromMonths: '', termToMonths: '', sex: 'MALE', nonSmoker: '', smoker: '2.5', unknown: '' },
    ]);
    expect(bandsFromRows(rows)).toEqual(bands);
  });

  it('changing a band\'s ages changes both of its rows, so the pair cannot drift apart', () => {
    const rows = rowsFromBands(editCell([blankBand('18', '30')], 0, 'ageTo', '35'));
    expect(rows.map((r) => r.ageTo)).toEqual(['35', '35']);
  });

  it('keeps two bands with the same ages apart rather than merging them', () => {
    const rows = rowsFromBands([blankBand('18', '30'), blankBand('18', '30')]);
    expect(bandsFromRows(rows)).toHaveLength(2);
  });

  it('fills evenly spaced bands, the last one ending at the maximum age', () => {
    expect(generateBands(18, 30, 5).map((b) => `${b.ageFrom}-${b.ageTo}`)).toEqual(['18-22', '23-27', '28-30']);
    expect(generateBands(30, 18, 5)).toEqual([]);
    expect(generateBands(18, 30, 0)).toEqual([]);
  });

  it('pastes a spreadsheet block from the cell it lands in, adding bands as it runs out', () => {
    const order = cellOrder(false, false);
    const text = '18\t30\t1.10\t1.50\t1.30\t1.90\n31\t40\t1,250.5\t\t2.10\t2.60\n';
    const bands = pasteBlock([blankBand()], 0, 'ageFrom', text, order);

    expect(bands).toHaveLength(2);
    expect(bands[0]).toMatchObject({ ageFrom: '18', ageTo: '30', female: { nonSmoker: '1.10', smoker: '1.50' }, male: { nonSmoker: '1.30', smoker: '1.90' } });
    // Thousands separators dropped; a blank cell stays blank -- "not priced", never zero.
    expect(bands[1]).toMatchObject({ ageFrom: '31', female: { nonSmoker: '1250.5', smoker: '' } });
  });

  it('pastes rates only, from a rate cell, leaving the ages alone', () => {
    const order = cellOrder(false, false);
    const bands = pasteBlock([blankBand('18', '30')], 0, 'female.nonSmoker', '1.1\t1.5\t1.3\t1.9', order);
    expect(bands[0]).toMatchObject({ ageFrom: '18', ageTo: '30', male: { smoker: '1.9' } });
  });

  it('tells a pasted block from one typed value', () => {
    expect(isBlock('1.25')).toBe(false);
    expect(isBlock('1.25\t1.30')).toBe(true);
    expect(isBlock('1.25\n1.30')).toBe(true);
  });

  it('names gaps and overlaps on the band they concern', () => {
    const notes = bandNotes([blankBand('18', '30'), blankBand('35', '40'), blankBand('38', '50'), blankBand('60', '55')]);
    expect(notes).toEqual([null, 'Ages 31–34 not priced', 'Overlaps ages up to 40', 'Ends before it starts']);
  });
});
