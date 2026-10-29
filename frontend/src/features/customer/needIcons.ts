import { HeartHandshake, Lock, PiggyBank, ShieldCheck, Wallet, type LucideIcon } from 'lucide-react';
import type { Need } from './selfService';

/** The picture on each need's tile and question screen. */
export const NEED_ICON: Record<Need, LucideIcon> = {
  PROTECTION: ShieldCheck, CASH_VALUE: PiggyBank, SAVINGS_ACCOUNT: Wallet, FUNERAL: HeartHandshake, DEPOSIT: Lock,
};

/** A policy's picture on its card, by what it is -- a shield for cover, a piggy bank for savings. */
export const CATEGORY_ICON: Record<string, LucideIcon> = {
  TERM_LIFE: ShieldCheck, WHOLE_LIFE: ShieldCheck, CREDIT_LIFE: ShieldCheck, GROUP_LIFE: ShieldCheck,
  ENDOWMENT: PiggyBank, EDUCATION_SAVINGS: PiggyBank, ANNUITY: Wallet, UNIT_LINKED: Wallet, FUNERAL: HeartHandshake,
};
