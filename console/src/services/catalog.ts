enum PRODUCT_CATALOG_API {
    PAGE = '/api/productCatalog/page',
    DETAIL = '/api/productCatalog/',
}

interface ProductCatalogItem {
    modelNo: string;
    title: string;
    brand?: string;
    description?: string;
    productType?: string;
    modelName?: string;
    productLine?: string;
    countryOfOrigin?: string;
    gender?: string;
    color?: string;
    colorway?: string;
    upperMaterial?: string;
    imageUrls: string[];
    imageCount: number;
    source: string;
    sourceUpdatedAt?: string;
    manualOverride: boolean;
    gmtModified?: string;
}

const productCatalogDetailApi = (modelNo: string) =>
    `${PRODUCT_CATALOG_API.DETAIL}${encodeURIComponent(modelNo)}`;

export {PRODUCT_CATALOG_API, productCatalogDetailApi};
export type {ProductCatalogItem};
