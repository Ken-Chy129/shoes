// 任务类型常量
const TASK_TYPE = {
    KC: 'kc',
    STOCKX_LISTING: 'stockx_listing',
    STOCKX_PRICE_DOWN: 'stockx_price_down',
};

enum TASK_API {
    PAGE = '/api/task/page',
    START = '/api/task/start',
    STOP = '/api/task/stop',
    CANCEL = '/api/task/cancel',
    STATUS = '/api/task/status',
    INTERVAL = '/api/task/interval',
    CURRENT_TASK_ID = '/api/task/currentTaskId',
    CURRENT_ROUND = '/api/task/currentRound',
    // StockX任务配置
    STOCKX_CONFIG = '/api/task/stockx/config',
    STOCKX_SORT_OPTIONS = '/api/task/stockx/sortOptions',
    // 任务明细
    TASK_ITEM_PAGE = '/api/taskItem/page',
}

export {TASK_API, TASK_TYPE}
