const fs = require('fs');
const path = require('path');

describe('StockX create-bids task progress', () => {
  it('shows purchase-specific counters and preprocessing progress', () => {
    const source = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

    expect(source).toContain("attrs.operation === 'create_bids'");
    expect(source).toContain('已提交 {attrs.submitted ?? 0}');
    expect(source).toContain("const taskSucceeded = record.status === 'success' || record.status === '执行成功';");
    expect(source).toContain("attrs.processed ?? (taskSucceeded ? (attrs.total ?? 0) : 0)");
    expect(source).toContain("attrs.modelTotal == null ? '货号 -'");
    expect(source).toContain('待提交 {attrs.pending ?? 0}');
    expect(source).toContain("taskSucceeded ? '已完成' : '准备中'");
  });
});
