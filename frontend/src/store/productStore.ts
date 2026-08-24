import { create } from 'zustand';
import { getActiveSnapshot, listProducts } from '@/api/products';
import type { ProductSnapshot, ProductSummary } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `product` domain store. Minimal on purpose: this platform has no product
 * management UI in this slice, only a read of the catalog (for the policy-issue
 * form's product picker) and the active-snapshot lookup that picker needs to
 * resolve a `productVersionId`.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface ProductState {
  list: Resource<ProductSummary[]>;
  snapshots: Keyed<ProductSnapshot>;

  loadList: () => Promise<void>;
  loadSnapshot: (productId: string) => Promise<void>;
}

export const useProductStore = create<ProductState>((set, getState) => ({
  list: idle(),
  snapshots: {},

  loadList: () =>
    track(
      'product.list',
      getState().list,
      (next) => set({ list: next }),
      () => listProducts(),
    ),

  loadSnapshot: (productId) =>
    track(
      `product.snapshot.${productId}`,
      getState().snapshots[productId] ?? idle<ProductSnapshot>(),
      (next) => set((s) => ({ snapshots: { ...s.snapshots, [productId]: next } })),
      () => getActiveSnapshot(productId),
    ),
}));

export const selectProductSnapshot = (productId: string) => (s: ProductState) =>
  s.snapshots[productId] ?? idle<ProductSnapshot>();
