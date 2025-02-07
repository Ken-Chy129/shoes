import { request } from '@umijs/max';
import type {PriceItem, SizePrice, PriceUpdateConfig, PriceSetting} from './types';

export async function queryPriceSetting() {
  return request<{
    data: PriceSetting;
    msg: string;
    code: number;
  }>('/api/setting/price', {
    method: 'GET',
  });
}

export async function updatePriceSetting(data: PriceSetting) {
  return request<{
    data: boolean;
    msg: string;
    code: number;
  }>('/api/setting/price', {
    method: 'POST',
    data
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