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

  it('shows eBay identifiers in task details', () => {
    const modal = fs.readFileSync(path.join(__dirname, '../components/TaskItemModal.tsx'), 'utf8');

    expect(modal).toContain("title: 'SKU'");
    expect(modal).toContain("title: 'Offer ID'");
    expect(modal).toContain("taskType === 'ebay_bulk_listing'");
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
});
