import { z } from 'zod';
import type { CreateProductRequest } from '@/api/types';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/** Zod schema for creating a product, mirroring `CreateProductRequest` exactly. */
export const createProductFormSchema = z.object({
  productCode: z.string().trim().min(1, 'Product code is required'),
  productName: z.string().trim().min(1, 'Product name is required'),
  category: z.enum([
    'TERM_LIFE',
    'ENDOWMENT',
    'WHOLE_LIFE',
    'ANNUITY',
    'UNIT_LINKED',
    'GROUP_LIFE',
    'EDUCATION_SAVINGS',
    'CREDIT_LIFE',
  ]),
  defaultCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
});

export type CreateProductFormValues = z.infer<typeof createProductFormSchema>;

export function blankCreateProductForm(): CreateProductFormValues {
  return { productCode: '', productName: '', category: 'TERM_LIFE', defaultCurrency: 'TZS' };
}

export function toApiRequest(values: CreateProductFormValues): CreateProductRequest {
  return {
    productCode: values.productCode.trim(),
    productName: values.productName.trim(),
    category: values.category,
    defaultCurrency: values.defaultCurrency,
  };
}
