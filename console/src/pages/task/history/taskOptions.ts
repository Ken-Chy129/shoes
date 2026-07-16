export const TASK_TYPE_LABELS: Record<string, string> = {
  price_down: '压价',
  listing: '搜索上架',
  model_search: '货号搜索上架',
  excel_delist: 'Excel下架',
  fetch_listings: '获取上架商品',
  fetch_orders: '获取订单',
};

export const STOCKX_TASK_OPTIONS = [
  {label: TASK_TYPE_LABELS.price_down, value: 'price_down'},
  {label: TASK_TYPE_LABELS.listing, value: 'listing'},
  {label: TASK_TYPE_LABELS.model_search, value: 'model_search'},
  {label: TASK_TYPE_LABELS.excel_delist, value: 'excel_delist'},
  {label: TASK_TYPE_LABELS.fetch_listings, value: 'fetch_listings'},
  {label: TASK_TYPE_LABELS.fetch_orders, value: 'fetch_orders'},
];

export const STOCKX_ORDER_TYPE_OPTIONS = [
  {label: '已完成', value: 'completed'},
  {label: '已取消', value: 'cancelled'},
  {label: '待付款', value: 'pending_payout'},
];
