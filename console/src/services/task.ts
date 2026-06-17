// 任务类型常量
const TASK_TYPE = {
    LISTING: 'listing',
    PRICE_DOWN: 'price_down',
    FETCH_LISTINGS: 'fetch_listings',
    EXCEL_DELIST: 'excel_delist',
    MODEL_SEARCH: 'model_search',
};

enum TASK_API {
    PAGE = '/api/task/page',
    START = '/api/task/start',
    CANCEL = '/api/task/cancel',
    STATUS = '/api/task/status',
    INTERVAL = '/api/task/interval',
    CURRENT_TASK_ID = '/api/task/currentTaskId',
    CURRENT_ROUND = '/api/task/currentRound',
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
    // 按taskId取消任务
    CANCEL_BY_ID = '/api/task/cancelById',
    // StockX 搜索上架
    START_MODEL_NO_SEARCH_LIST = '/api/task/stockx/startModelNoSearchList',
    START_SEARCH_LIST = '/api/task/stockx/startSearchList',
    CANCEL_SEARCH_LIST = '/api/task/stockx/cancelSearchList',
    SEARCH_LIST_STATUS = '/api/task/stockx/searchListStatus',
    // StockX 获取上架商品
    START_FETCH_LISTINGS = '/api/task/stockx/startFetchListings',
    CANCEL_FETCH_LISTINGS = '/api/task/stockx/cancelFetchListings',
    FETCH_LISTINGS_STATUS = '/api/task/stockx/fetchListingsStatus',
    // StockX Excel下架
    UPLOAD_DELIST_EXCEL = '/api/task/stockx/uploadDelistExcel',
    DELIST_EXCEL_COUNT = '/api/task/stockx/delistExcelCount',
    DELIST_EXCEL_DATA = '/api/task/stockx/delistExcelData',
    START_EXCEL_DELIST = '/api/task/stockx/startExcelDelist',
    CANCEL_EXCEL_DELIST = '/api/task/stockx/cancelExcelDelist',
    EXCEL_DELIST_STATUS = '/api/task/stockx/excelDelistStatus',
}

export {TASK_API, TASK_TYPE}
