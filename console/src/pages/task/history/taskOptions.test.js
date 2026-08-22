const fs = require('fs');
const path = require('path');

describe('StockX purchase task options', () => {
  it('offers create bids as the fourth purchase operation', () => {
    const source = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');

    expect(source).toContain("{label: '创建出价', value: 'create_bids'}");
  });
});
