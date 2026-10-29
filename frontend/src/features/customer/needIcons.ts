import { HeartHandshake, Lock, PiggyBank, ShieldCheck, type LucideIcon } from 'lucide-react';
import type { Need } from './selfService';

/** The picture on each need's tile and question screen. */
export const NEED_ICON: Record<Need, LucideIcon> = {
  PROTECTION: ShieldCheck, CASH_VALUE: PiggyBank, FUNERAL: HeartHandshake, DEPOSIT: Lock,
};
