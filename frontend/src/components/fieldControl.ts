import { createContext, use } from 'react';

/**
 * How a form control inside a {@link FormField} learns its own id, whether it is
 * invalid, and which element describes it.
 *
 * Its own module rather than living in `FormField.tsx`, because a file that
 * exports both a component and a hook breaks React Fast Refresh — the whole
 * module re-evaluates on edit and component state is lost.
 *
 * The indirection buys the thing that matters: 98 hand-written inputs carried no
 * `aria-invalid` and no `aria-describedby`, and annotating them individually was
 * never going to happen or stay done. A control that reads this context gets it
 * right by existing.
 */

export interface FieldControlContext {
  id: string;
  invalid: boolean;
  errorId: string | undefined;
}

export const FieldControl = createContext<FieldControlContext | null>(null);

/**
 * The `id`, `aria-invalid` and `aria-describedby` for a control inside a
 * `FormField`. Returns nothing outside one, so these components stay usable on
 * their own — a search box in a toolbar has no FormField and needs no error
 * wiring.
 *
 * A caller-supplied `id` always wins: a form managing its own ids must not have
 * one silently overwritten.
 */
/**
 * Declared explicitly rather than inferred. The no-context branch returns `{}`,
 * and letting TypeScript infer the union from both branches gives a type with no
 * properties at all — so `const { id } = useFieldControl()` fails to compile at
 * every call site that wants only the id.
 */
export interface FieldControlAttributes {
  id?: string;
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

export function useFieldControl(ownId?: string | undefined): FieldControlAttributes {
  const context = use(FieldControl);
  if (!context) return {};
  return {
    id: ownId ?? context.id,
    ...(context.invalid ? { 'aria-invalid': true as const } : {}),
    ...(context.errorId ? { 'aria-describedby': context.errorId } : {}),
  };
}
