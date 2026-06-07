// 任务类型常量
const TASK_TYPE = {
    KC_LISTING: 'kc_listing',
    KC_PRICE_DOWN: 'kc_price_down',
    STOCKX_STANDARD_PRICE_DOWN: 'stockx_standard_price_down',
    STOCKX_CUSTODIAL_PRICE_DOWN: 'stockx_custodial_price_down',
};

enum TASK_API {
    PAGE = '/api/task/page',
    START = '/api/task/start',
    CANCEL = '/api/task/cancel',
    STATUS = '/api/task/status',
    INTERVAL = '/api/task/interval',
    CURRENT_TASK_ID = '/api/task/currentTaskId',
    CURRENT_ROUND = '/api/task/currentRound',
    // StockX任务配置
    STOCKX_CONFIG = '/api/task/stockx/config',
    STOCKX_SORT_OPTIONS = '/api/task/stockx/sortOptions',
    STOCKX_UPLOAD_PRICE_DOWN_EXCEL = '/api/task/stockx/uploadPriceDownExcel',
    STOCKX_PRICE_DOWN_EXCEL_COUNT = '/api/task/stockx/priceDownExcelCount',
    STOCKX_PRICE_DOWN_EXCEL_DATA = '/api/task/stockx/priceDownExcelData',
    // StockX Excel 多账号压价
    STOCKX_START_EXCEL_PRICE_DOWN = '/api/task/stockx/startExcelPriceDown',
    STOCKX_CANCEL_EXCEL_PRICE_DOWN = '/api/task/stockx/cancelExcelPriceDown',
    STOCKX_EXCEL_PRICE_DOWN_STATUS = '/api/task/stockx/excelPriceDownStatus',
    STOCKX_SET_EXCEL_PRICE_DOWN_INTERVAL = '/api/task/stockx/setExcelPriceDownInterval',
    // 任务明细
    TASK_ITEM_PAGE = '/api/taskItem/page',
    TASK_ITEM_EXPORT = '/api/taskItem/export',
    TASK_ITEM_OPERATE_RESULTS = '/api/taskItem/operateResults',
    // 删除任务
    DELETE = '/api/task/delete',
}

export {TASK_API, TASK_TYPE}
