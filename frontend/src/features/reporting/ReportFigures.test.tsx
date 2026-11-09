import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReportFigure } from '@/api/reporting';
import { bridgeSpans } from '@/components/charts/bridgeSpans';
import { BridgeChart } from '@/components/charts/BridgeChart';
import { changeWords, isGood } from './figureWords';
import { ReportFigures } from './ReportFigures';

const figure = (over: Partial<ReportFigure>): ReportFigure => ({
  key: 'profit', label: 'Profit for the period', value: '412.60', prior: '381.70', change: '30.90',
  changePercent: '8.1', goodWhen: 'UP', lead: true, ...over,
});

/** 2026-11-09: the answer on the figures band, every number the server's, its change in words. */
describe('report figures', () => {
  it('puts the change into words with its direction, from the server’s figures', () => {
    expect(changeWords(figure({}))).toBe('up 30.90 (8.1%) from 381.70');
    expect(changeWords(figure({ change: '-2.40', changePercent: '-5.2', prior: '45.80' }))).toBe('down 2.40 (5.2%) from 45.80');
    expect(changeWords(figure({ prior: null, change: null }))).toBeNull();
  });

  it('calls a rise good where rises are good, and bad where they are not', () => {
    expect(isGood(figure({}))).toBe(true);
    expect(isGood(figure({ goodWhen: 'DOWN' }))).toBe(false);
    expect(isGood(figure({ change: '-1.00', goodWhen: 'DOWN' }))).toBe(true);
  });

  it('leads with the band and gives the others a coloured pill', () => {
    render(<ReportFigures figures={[figure({}), figure({ key: 'expenses', label: 'Expenses', lead: false, goodWhen: 'DOWN' })]} />);
    expect(screen.getByLabelText('Headline figures')).toHaveTextContent('412.60');
    expect(screen.getByText('up 30.90 (8.1%) from 381.70', { selector: '[data-delta="bad"]' })).toBeInTheDocument();
  });
});

describe('the bridge chart', () => {
  const bars = [
    { label: 'September 2026', value: '381.70', kind: 'START' as const },
    { label: 'Insurance revenue', value: '72.40', kind: 'UP' as const },
    { label: 'Insurance service expenses', value: '52.40', kind: 'DOWN' as const },
    { label: 'October 2026', value: '401.70', kind: 'END' as const },
  ];

  it('stands each step on the level before it, and the ends on zero', () => {
    const spans = bridgeSpans(bars);
    expect(spans[1]).toEqual({ from: 381.7, to: 381.7 + 72.4 });
    expect(spans[2]!.from).toBeCloseTo(454.1);
    expect(spans[3]).toEqual({ from: 0, to: 401.7 });
  });

  it('labels every bar with the server’s figure and names rises and falls in words', () => {
    render(<BridgeChart bars={bars} description="How the profit moved" />);
    expect(screen.getByRole('img', { name: 'How the profit moved' })).toBeInTheDocument();
    expect(screen.getByText('Insurance revenue: up 72.40')).toBeInTheDocument();
    expect(screen.getByText('Insurance service expenses: down 52.40')).toBeInTheDocument();
  });
});
