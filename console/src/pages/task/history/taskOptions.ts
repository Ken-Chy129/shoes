export const TASK_TYPE_LABELS: Record<string, string> = {
  price_down: '压价',
  listing: '搜索上架',
  model_search: '货号搜索上架',
  excel_delist: '下架',
  fetch_listings: '获取上架商品',
  fetch_orders: '获取订单',
  purchase: '购买',
  extend_shipping: '订单延期',
  replenishment: '补单',
  ebay_bulk_listing: '批量上架',
};

export const EBAY_TASK_OPTIONS = [
  {label: TASK_TYPE_LABELS.ebay_bulk_listing, value: 'ebay_bulk_listing'},
];

export const STOCKX_TASK_OPTIONS = [
  {label: TASK_TYPE_LABELS.price_down, value: 'price_down'},
  {label: TASK_TYPE_LABELS.listing, value: 'listing'},
  {label: TASK_TYPE_LABELS.model_search, value: 'model_search'},
  {label: TASK_TYPE_LABELS.excel_delist, value: 'excel_delist'},
  {label: TASK_TYPE_LABELS.fetch_listings, value: 'fetch_listings'},
  {label: TASK_TYPE_LABELS.fetch_orders, value: 'fetch_orders'},
  {label: TASK_TYPE_LABELS.purchase, value: 'purchase'},
  {label: TASK_TYPE_LABELS.extend_shipping, value: 'extend_shipping'},
  {label: TASK_TYPE_LABELS.replenishment, value: 'replenishment'},
];

export const ALL_TASK_OPTIONS = [
  ...STOCKX_TASK_OPTIONS,
  ...EBAY_TASK_OPTIONS,
];

export const STOCKX_ORDER_TYPE_OPTIONS = [
  {label: '待处理', value: 'pending'},
  {label: '已完成', value: 'completed'},
  {label: '已取消', value: 'cancelled'},
  {label: '待付款', value: 'pending_payout'},
];

export const STOCKX_PURCHASE_OPERATION_OPTIONS = [
  {label: '获取出价', value: 'bids'},
  {label: '获取订单', value: 'orders'},
  {label: '获取历史记录', value: 'history'},
  {label: '创建出价', value: 'create_bids'},
  {label: '修改出价', value: 'update_bids'},
];
