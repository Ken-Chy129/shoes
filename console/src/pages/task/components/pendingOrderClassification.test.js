const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'TaskItemModal.tsx'), 'utf8');

test('pending classification distinguishes false, true and missing values', () => {
  const column = source.match(/title: '是否补单'[\s\S]*?render: ([\s\S]*?),\s*\}/);
  expect(column).not.toBeNull();
  const render = new Function(`return (${column[1]
    .replace('(value: boolean | null | undefined, record: TaskItemRecord)', '(value, record)')})`)();
  expect(render(true, {orderStatus: '待处理'})).toBe('补单');
  expect(render(false, {orderStatus: '待处理'})).toBe('正常单');
  expect(render(null, {orderStatus: '待处理'})).toBe('未判定');
  expect(render(undefined, {orderStatus: '待处理'})).toBe('未判定');
  expect(render(true, {orderStatus: '销售完成'})).toBe('-');
  expect(render(false, {orderStatus: '已取消'})).toBe('-');
});

test('shipment columns do not inherit the order classification', () => {
  const shipmentColumns = source.match(/const shipmentColumns = \[([\s\S]*?)\n    \];/)[1];
  expect(shipmentColumns).not.toContain("'replenishmentOrder'");
});
