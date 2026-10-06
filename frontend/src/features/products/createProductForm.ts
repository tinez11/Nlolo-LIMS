import { z } from 'zod';
import { PRODUCT_CATEGORIES, type CreateProductRequest } from '@/api/types';
import { PORTFOLIO_CODES, type PortfolioCode } from '@/lib/ifrs17';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/** The request's enum: `ProductCategory` is derived from ProductSummary, whose category is a bare string. */
type RequestCategory = CreateProductRequest['category'];

/** Zod schema for creating a product, mirroring `CreateProductRequest` exactly. */
export const createProductFormSchema = z.object({
  productCode: z.string().trim().min(1, 'Product code is required'),
  productName: z.string().trim().min(1, 'Product name is required'),
  // The one category list (api/types). A second hand-kept copy here once lacked FUNERAL, and the
  // form then refused a funeral product silently -- the select renders no error of its own.
  category: z.enum(PRODUCT_CATEGORIES as unknown as [RequestCategory, ...RequestCategory[]], {
    message: 'Choose a category',
  }),
  // IFRS 17 I2: the product's portfolio, preselected from the category and changeable (a with-profits endowment
  // is PAR, a savings plan SAV, a pension PEN).
  portfolioCode: z.enum(PORTFOLIO_CODES as unknown as [PortfolioCode, ...PortfolioCode[]], {
    message: 'Choose the IFRS 17 portfolio',
  }),
  defaultCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
});

export type CreateProductFormValues = z.infer<typeof createProductFormSchema>;

export function blankCreateProductForm(): CreateProductFormValues {
  return { productCode: '', productName: '', category: 'TERM_LIFE', portfolioCode: 'TERM', defaultCurrency: 'TZS' };
}

export function toApiRequest(values: CreateProductFormValues): CreateProductRequest {
  return {
    productCode: values.productCode.trim(),
    productName: values.productName.trim(),
    category: values.category,
    portfolioCode: values.portfolioCode,
    defaultCurrency: values.defaultCurrency,
  };
}
