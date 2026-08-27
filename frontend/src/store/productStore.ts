import { create } from 'zustand';
import {
  createProduct,
  getActiveSnapshot,
  getVersionRating,
  listProducts,
  publishVersion,
} from '@/api/products';
import type {
  CreateProductRequest,
  ProductCategory,
  ProductSnapshot,
  ProductSummary,
  ProductVersionSpec,
  VersionRatingView,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `product` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface ProductState {
  list: Resource<ProductSummary[]>;
  snapshots: Keyed<ProductSnapshot>;
  // Keyed by versionId, NOT productId: the rating basis belongs to a version,
  // and keying it by product would silently serve one version's rate table for
  // another the day a second version becomes reachable.
  ratings: Keyed<VersionRatingView>;
  // A single slot, not keyed: creation makes a NEW product, so there is no
  // existing id to key against yet -- same shape as claims' `registering`.
  creating: Resource<ProductSummary>;
  // Keyed by productId: publishing targets an EXISTING product, and a failed
  // publish on one product must not corrupt another's state.
  publishing: Keyed<true>;

  loadList: (category?: ProductCategory) => Promise<void>;
  loadSnapshot: (productId: string) => Promise<void>;
  loadRating: (productId: string, versionId: string) => Promise<void>;
  createProduct: (request: CreateProductRequest) => Promise<void>;
  resetCreateProduct: () => void;
  publishVersion: (productId: string, spec: ProductVersionSpec) => Promise<void>;
  resetPublishVersion: (productId: string) => void;
}

export const useProductStore = create<ProductState>((set, getState) => ({
  list: idle(),
  snapshots: {},
  ratings: {},
  creating: idle(),
  publishing: {},

  loadList: (category) =>
    track(
      'product.list',
      getState().list,
      (next) => set({ list: next }),
      () => listProducts(category),
    ),

  loadSnapshot: (productId) =>
    track(
      `product.snapshot.${productId}`,
      getState().snapshots[productId] ?? idle<ProductSnapshot>(),
      (next) => set((s) => ({ snapshots: { ...s.snapshots, [productId]: next } })),
      () => getActiveSnapshot(productId),
    ),

  loadRating: (productId, versionId) =>
    track(
      `product.rating.${versionId}`,
      getState().ratings[versionId] ?? idle<VersionRatingView>(),
      (next) => set((s) => ({ ratings: { ...s.ratings, [versionId]: next } })),
      () => getVersionRating(productId, versionId),
    ),

  createProduct: (request) =>
    track(
      'product.create',
      getState().creating,
      (next) => set({ creating: next }),
      () => createProduct(request),
    ),

  resetCreateProduct: () => set({ creating: idle() }),

  // A distinct key from `product.snapshot.${productId}`: publishing and the
  // eventual snapshot refresh are independent tracked operations.
  publishVersion: (productId, spec) =>
    track(
      `product.publish.${productId}`,
      getState().publishing[productId] ?? idle<true>(),
      (next) => set((s) => ({ publishing: { ...s.publishing, [productId]: next } })),
      // Explicit Promise<true>: see policyStore.saveBeneficiaries for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await publishVersion(productId, spec);
        // publishVersion flips the product to ACTIVE, which changes what the
        // list and the snapshot both show -- refresh both so the UI reflects it
        // without a manual reload.
        await Promise.all([getState().loadList(), getState().loadSnapshot(productId)]);
        return true;
      },
    ),

  resetPublishVersion: (productId) =>
    set((s) => {
      if (!(productId in s.publishing)) return s;
      const { [productId]: _discard, ...rest } = s.publishing;
      return { publishing: rest };
    }),
}));

export const selectProductSnapshot = (productId: string) => (s: ProductState) =>
  s.snapshots[productId] ?? idle<ProductSnapshot>();
/** Keyed by versionId. `null` versionId means the snapshot has not resolved one yet. */
export const selectVersionRating = (versionId: string | null | undefined) => (s: ProductState) =>
  (versionId ? s.ratings[versionId] : undefined) ?? idle<VersionRatingView>();
export const selectPublishing = (productId: string) => (s: ProductState) =>
  s.publishing[productId] ?? idle<true>();
