const fs = require('fs');
const path = require('path');
const read = (file) => fs.readFileSync(path.join(__dirname, file), 'utf8');

describe('custodial fixed-price task', () => {
  it('exposes a separate task and explicit Excel price requirements', () => {
    expect(read('taskOptions.ts')).toContain("update_custodial_prices: '修改寄存商品价格'");
    const page = read('index.tsx');
    expect(page).toContain('name="custodialPriceExcel"');
    expect(page).toContain('listingId');
    expect(page).toContain('整数美元');
    expect(page).toContain('确认修改寄存商品价格');
    expect(page).toContain('TASK_API.START_UPDATE_CUSTODIAL_PRICES');
    expect(read('../../../services/task.ts')).toContain('/api/task/stockx/startUpdateCustodialPrices');
  });
  it('shows original and target prices with execution results', () => {
    const modal = read('../components/TaskItemModal.tsx');
    expect(modal).toContain('custodialPriceColumns');
    expect(modal).toContain("taskType === 'update_custodial_prices'");
    expect(modal).toContain("taskType !== 'update_custodial_prices'");
    expect(modal).toContain('寄存改价明细');
  });
});
