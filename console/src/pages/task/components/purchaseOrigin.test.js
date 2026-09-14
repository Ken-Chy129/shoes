const fs = require('fs');
const path = require('path');

describe('purchase origin columns', () => {
  it('shares original order and original-currency price columns between sales and custodial listings', () => {
    const source = fs.readFileSync(path.join(__dirname, 'TaskItemModal.tsx'), 'utf8');
    expect(source).toContain("title: '原购买订单号'");
    expect(source).toContain("dataIndex: 'purchasePrice'");
    expect(source).toContain('formatOrderMoney(value, record.purchaseCurrencyCode)');
    expect(source).toContain('...purchaseOriginColumns');
    expect(source).toContain("taskType !== 'fetch_listings'");
    expect(source).toContain("inventoryType === 'CUSTODIAL'");
    expect(source).toContain('isCustodialListings ? [...productColumns, ...purchaseOriginColumns]');
  });
});
