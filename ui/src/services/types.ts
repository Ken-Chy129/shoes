export interface PriceItem {
  id: string;
  image: string;
  name: string;
  brand: string;
  modelNumber: string;
  spuId: string;
}

export interface SizePrice {
  size: string;
  priceA: number;
  priceB: number;
  profit: number;
  profitRate: number;
}

export interface PriceUpdateConfig {
  priceType: 'normal' | 'flash' | 'fast';
  brand?: string;
  modelNumber?: string;
  minProfit: number;
  minProfitRate: number;
} 