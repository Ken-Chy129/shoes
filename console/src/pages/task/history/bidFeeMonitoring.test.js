const fs = require('fs');
const path = require('path');

describe('StockX bid fee monitoring UI', () => {
  const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
  const modal = fs.readFileSync(
    path.join(__dirname, '../components/TaskItemModal.tsx'),
    'utf8',
  );

  it('shows opt-in fee settings with the requested defaults', () => {
    expect(page).toContain('const BidFeeMonitorFields');
    expect(page).toContain("name=\"bidFeeMonitorEnabled\"");
    expect(page).toContain("name=\"bidMerchantFeeRate\"");
    expect(page).toContain('initialValue={0.07}');
    expect(page).toContain("name=\"bidMinMerchantFee\"");
    expect(page).toContain('initialValue={5.79}');
    expect(page).toContain("name=\"bidTransferFeeRate\"");
    expect(page).toContain('initialValue={0.03}');
    expect(page).toContain("name=\"processOutsideExcel\"");
  });

  it('uploads the fee settings for both create and update bid tasks', () => {
    expect(page).toContain('feeMonitorEnabled: String(Boolean(values.bidFeeMonitorEnabled))');
    expect(page).toContain("merchantFeeRate: String(values.bidMerchantFeeRate ?? 0.07)");
    expect(page).toContain("minMerchantFee: String(values.bidMinMerchantFee ?? 5.79)");
    expect(page).toContain("transferFeeRate: String(values.bidTransferFeeRate ?? 0.03)");
    expect(page).toContain(
      'processOutsideExcel: String(Boolean(values.bidFeeMonitorEnabled && values.processOutsideExcel))',
    );
    expect(page).toContain('费率配置是否启用');
  });

  it('shows spot-price context in monitored task details and readable parameters', () => {
    expect(modal).toContain('feeMonitorEnabled');
    expect(modal).toContain("title: '现货标价'");
    expect(modal).toContain("title: '盈利上限'");
    expect(page).toContain("feeMonitorEnabled: '费率监控'");
    expect(page).toContain("merchantFeeRate: '手续费'");
    expect(page).toContain("minMerchantFee: '最低手续费'");
    expect(page).toContain("transferFeeRate: '转账费'");
  });
});
