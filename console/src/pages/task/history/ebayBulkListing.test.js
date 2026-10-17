const fs = require('fs');
const path = require('path');

describe('eBay bulk listing task UI', () => {
  it('offers the eBay platform, template and Excel upload endpoint', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const options = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(page).toContain('<Select.Option value="ebay">eBay</Select.Option>');
    expect(page).toContain('EBAY_START_BULK_LISTING');
    expect(page).toContain('EBAY_BULK_LISTING_TEMPLATE');
    expect(options).toContain("ebay_bulk_listing: '批量上架'");
    expect(service).toContain("EBAY_START_BULK_LISTING = '/api/task/ebay/startBulkListing'");
  });

  it('lets the user choose the HK or US shipping region', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

    expect(page).toContain('name="ebayRegion" label="发货区域" initialValue="hk"');
    expect(page).toContain('<Radio.Button value="hk">港区</Radio.Button>');
    expect(page).toContain('<Radio.Button value="us">美区</Radio.Button>');
    expect(page).toContain('doUploadRequestWithParams(TASK_API.EBAY_START_BULK_LISTING, file, {region}');
  });

  it('shows eBay identifiers in task details', () => {
    const modal = fs.readFileSync(path.join(__dirname, '../components/TaskItemModal.tsx'), 'utf8');

    expect(modal).toContain("title: 'SKU'");
    expect(modal).toContain("title: 'Offer ID'");
    expect(modal).toContain("taskType === 'ebay_bulk_listing'");
  });

  it('allows a running bulk listing task to be cancelled', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

    expect(page).toContain('handleCancelTask(record)');
    expect(page).not.toContain("record.taskType !== 'ebay_bulk_listing'");
  });

  it('starts eBay delisting through its dedicated endpoint', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(page).toContain("createPlatform === 'ebay' && createTaskType === 'ebay_delist'");
    expect(page).toContain('doPostRequest(TASK_API.EBAY_START_DELIST, {styleIds: []}');
    expect(page).toContain('eBay下架任务已创建');
    expect(service).toContain("EBAY_START_DELIST = '/api/task/ebay/startDelist'");
  });

  it('offers inventory zeroing as a separate task type', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const options = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(options).toContain("ebay_zero_stock: 'eBay库存清零'");
    expect(page).toContain("createTaskType === 'ebay_zero_stock'");
    expect(page).toContain('doPostRequest(TASK_API.EBAY_START_ZERO_STOCK, {styleIds: []}');
    expect(service).toContain("EBAY_START_ZERO_STOCK = '/api/task/ebay/startZeroStock'");
  });

  it('offers fetching eBay orders as a separate task type', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const options = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(options).toContain("ebay_fetch_orders: 'eBay获取订单'");
    expect(page).toContain("createTaskType === 'ebay_fetch_orders'");
    expect(page).toContain('doPostRequest(TASK_API.EBAY_START_FETCH_ORDERS');
    expect(service).toContain("EBAY_START_FETCH_ORDERS = '/api/task/ebay/startFetchOrders'");
  });

  it('offers adding eBay tracking numbers from an uploaded Excel', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const options = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(options).toContain("ebay_add_tracking: 'eBay添加追踪号码'");
    expect(page).toContain("createTaskType === 'ebay_add_tracking'");
    expect(page).toContain('doUploadRequestWithParams(TASK_API.EBAY_START_ADD_TRACKING');
    expect(service).toContain("EBAY_START_ADD_TRACKING = '/api/task/ebay/startAddTracking'");
    expect(service).toContain("EBAY_TRACKING_TEMPLATE = '/api/task/ebay/trackingTemplate'");
  });

  it('offers the eBay profit-based repricing task with optional per-style targets', () => {
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const options = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../../services/task.ts'), 'utf8');

    expect(options).toContain("ebay_profit_price: 'eBay盈利改价'");
    expect(page).toContain("createTaskType === 'ebay_profit_price'");
    expect(page).toContain('request(TASK_API.EBAY_START_PROFIT_PRICE');
    expect(page).toContain("formData.append('defaultProfit'");
    expect(page).toContain("formData.append('freight'");
    expect(page).toContain('name="profitFreight"');
    expect(page).toContain('只有得物没价格才将库存置0');
    expect(service).toContain("EBAY_START_PROFIT_PRICE = '/api/task/ebay/startProfitPrice'");
    expect(service).toContain("EBAY_PROFIT_TARGET_TEMPLATE = '/api/task/ebay/profitTargetTemplate'");
  });
});
