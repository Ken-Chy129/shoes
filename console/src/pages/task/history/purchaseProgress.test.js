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

  it('shows scoped delete confirmation, Excel input, and task progress', () => {
    const source = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

    expect(source).toContain("operation === 'delete_bids'");
    expect(source).toContain('name="deleteBidsMode"');
    expect(source).toContain('value="style_ids"');
    expect(source).toContain('name="deleteBidsExcelFile"');
    expect(source).toContain("TASK_API.START_DELETE_BIDS");
    expect(source).toContain('确认撤销所有出价？');
    expect(source).toContain('确认撤销指定货号出价？');
    expect(source).toContain('确认全部撤销');
    expect(source).toContain("attrs.operation === 'delete_bids'");
    expect(source).toContain("attrs.deleteMode === 'style_ids'");
    expect(source).toContain('未匹配货号 {attrs.unmatchedStyleCount ?? 0}');
    expect(source).toContain('剩余 {attrs.remaining ?? 0}');
    expect(source).toContain('已撤销 {attrs.deleted ?? 0}');
    expect(source).toContain('失败 {attrs.failed ?? 0}');
  });
});
