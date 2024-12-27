import { request } from '@umijs/max';
import type { PriceItem, SizePrice, PriceUpdateConfig } from './types';

export async function queryPriceList(params: {
  pageSize: number;
  current: number;
  brand?: string;
  modelNumber?: string;
  name?: string;
  priceType?: string;
}) {
  return request<{
    data: PriceItem[];
    total: number;
    success: boolean;
  }>('/api/price/list', {
    method: 'GET',
    params,
  });
}

export async function querySizePriceList(params: {
  id: string;
  priceType: string;
}) {
  return request<{
    data: SizePrice[];
    success: boolean;
  }>('/api/price/size-list', {
    method: 'GET',
    params,
  });
}

export async function updatePriceConfig(data: PriceUpdateConfig) {
  return request<{
    success: boolean;
  }>('/api/price/update-config', {
    method: 'POST',
    data,
  });
} 