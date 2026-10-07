import { get, post } from '@/lib/http';
import type { YearEndCloseView } from './types';

/**
 * The year-end close (IFRS 17 I6, guide 5.7): the year's classes 4-8 closed to 3310, the result to retained earnings
 * (M-07) and dividends declared too (M-11), prepared by finance and approved by a second person.
 */

export function previewYearEnd(year: number): Promise<YearEndCloseView> {
  return get<YearEndCloseView>(`/ifrs17/year-end/${year}/preview`);
}

export function prepareYearEnd(year: number): Promise<YearEndCloseView> {
  return post<YearEndCloseView>(`/ifrs17/year-end/${year}/closes`, {});
}

export function listYearEndCloses(year: number): Promise<YearEndCloseView[]> {
  return get<YearEndCloseView[]>(`/ifrs17/year-end/${year}/closes`);
}

export function getYearEndClose(id: string): Promise<YearEndCloseView> {
  return get<YearEndCloseView>(`/ifrs17/year-end-closes/${encodeURIComponent(id)}`);
}

export function approveYearEndClose(id: string): Promise<YearEndCloseView> {
  return post<YearEndCloseView>(`/ifrs17/year-end-closes/${encodeURIComponent(id)}/approval`, {});
}

export function rejectYearEndClose(id: string, reason: string): Promise<YearEndCloseView> {
  return post<YearEndCloseView>(`/ifrs17/year-end-closes/${encodeURIComponent(id)}/rejection`, { reason });
}
