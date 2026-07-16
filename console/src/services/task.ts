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
    STOCKX_UPLOAD_PRICE_DOWN_EXCEL = '/api/task/stockx/uploadPriceDownExcel',
    STOCKX_PRICE_DOWN_EXCEL_DATA = '/api/task/stockx/priceDownExcelData',
    // StockX Excel 多账号压价
    STOCKX_START_EXCEL_PRICE_DOWN = '/api/task/stockx/startExcelPriceDown',
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
    // StockX 获取上架商品
    START_FETCH_LISTINGS = '/api/task/stockx/startFetchListings',
    // StockX Excel下架
    UPLOAD_DELIST_EXCEL = '/api/task/stockx/uploadDelistExcel',
    DELIST_EXCEL_DATA = '/api/task/stockx/delistExcelData',
    START_EXCEL_DELIST = '/api/task/stockx/startExcelDelist',
    // StockX 获取订单（已完成/已取消/待付款）
    START_FETCH_ORDERS = '/api/task/stockx/startFetchOrders',
}

export {TASK_API, TASK_TYPE}
