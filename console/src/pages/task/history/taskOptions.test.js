const fs = require('fs');
const path = require('path');

describe('StockX purchase task options', () => {
  it('offers create and update bid purchase operations', () => {
    const source = fs.readFileSync(path.join(__dirname, 'taskOptions.ts'), 'utf8');

    expect(source).toContain("{label: '创建出价', value: 'create_bids'}");
    expect(source).toContain("{label: '修改出价', value: 'update_bids'}");
  });
});
