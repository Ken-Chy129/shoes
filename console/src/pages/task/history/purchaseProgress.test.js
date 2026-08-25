const fs = require('fs');
const path = require('path');

describe('StockX create-bids task progress', () => {
  it('shows purchase-specific counters and preprocessing progress', () => {
    const source = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

    expect(source).toContain("attrs.operation === 'create_bids'");
    expect(source).toContain('已提交 {attrs.submitted ?? 0}');
    expect(source).toContain('已处理 {attrs.processed ?? 0}/{attrs.total ?? 0}');
    expect(source).toContain('货号 {attrs.modelsResolved ?? 0}/{attrs.modelTotal ?? 0}');
    expect(source).toContain('待提交 {attrs.pending ?? 0}');
  });
});
