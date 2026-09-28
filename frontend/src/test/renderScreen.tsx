import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';

/**
 * Render a whole feature screen the way the app mounts it.
 *
 * WHY THIS EXISTS. Three redesign lanes shipped with test files covering only pure functions --
 * the claims lane had six of them and not one rendered a screen -- so a green unit suite said
 * nothing about whether a rewritten page still drew. That gap cost four Playwright control runs
 * to diagnose a set of failures that turned out not to be code at all. The friction that
 * produced it was small and entirely mechanical: every feature screen links somewhere, so a
 * bare `render()` throws on the first `<Link>`, and wiring a router per test is just enough
 * work to not bother.
 *
 * WHAT IT DELIBERATELY DOES NOT DO:
 *
 * - **It does not mock the store.** The store is where a screen's real behaviour lives, so a
 *   test that mocks it asserts on its own fixture. Set state directly with
 *   `useXStore.setState(...)` in the test, exactly as `BeneficiariesPanel.test.tsx` does.
 * - **It does not mock auth.** Most screens read no identity at all. The ones that do are
 *   role-gated, and a helper that guesses an identity shape for them would be wrong in a way
 *   the gate itself cannot catch. Mock `react-oidc-context` in the test that needs it, where
 *   the roles being asserted are visible beside the assertion.
 *
 * The caller still mocks `@/api/<module>`: a screen's mount effects fetch, and an unmocked
 * fetch in jsdom is a slow failure rather than a fast one.
 */
export function renderScreen(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  return render(<MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>);
}
