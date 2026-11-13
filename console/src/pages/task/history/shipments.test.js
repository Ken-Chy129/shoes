const fs = require('fs');
const path = require('path');

const read = (file) => fs.readFileSync(path.join(__dirname, file), 'utf8');

describe('StockX shipment task integration', () => {
  it('offers an independent shipment task and exactly the three shipment types', () => {
    const options = read('taskOptions.ts');
    expect(options).toContain("fetch_shipments: '获取货运信息'");
    expect(options).toContain("value: 'fetch_shipments'");
    const shipmentOptions = options.match(/STOCKX_SHIPMENT_TYPE_OPTIONS = \[([\s\S]*?)\]/)?.[1] || '';
    expect(shipmentOptions).toContain("{label: '已打包', value: 'packed'}");
    expect(shipmentOptions).toContain("{label: '已发货', value: 'shipped'}");
    expect(shipmentOptions).toContain("{label: '已收到', value: 'received'}");
    expect(shipmentOptions).not.toContain('pending');
  });

  it('posts shipmentTypes to the dedicated endpoint with multi-select validation', () => {
    const page = read('index.tsx');
    expect(page).toContain('TASK_API.START_FETCH_SHIPMENTS');
    expect(page).toContain('shipmentTypes: values.shipmentTypes');
    expect(page).toContain('name="shipmentTypes"');
    expect(page).toContain('mode="multiple" options={STOCKX_SHIPMENT_TYPE_OPTIONS}');
    expect(page).toContain('请至少选择一种货运类型');
    expect(read('../../../services/task.ts')).toContain(
      "START_FETCH_SHIPMENTS = '/api/task/stockx/startFetchShipments'",
    );
  });

  it('shows shipment fields and hides the irrelevant round filter', () => {
    const modal = read('../components/TaskItemModal.tsx');
    expect(modal).toContain("taskType === 'fetch_shipments'");
    expect(modal).toContain("taskType !== 'fetch_shipments'");
    expect(modal).toContain('shipmentColumns');
    expect(modal).toContain("dataIndex: 'shipmentId'");
    expect(modal).toContain("dataIndex: 'trackingNumber'");
    expect(modal).toContain('货运明细');
  });
});
