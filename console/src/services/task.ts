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
    STATUS = '/api/task/status',
    INTERVAL = '/api/task/interval',
    // StockX任务配置
    STOCKX_CONFIG = '/api/task/stockx/config',
    STOCKX_SORT_OPTIONS = '/api/task/stockx/sortOptions',
}

export {TASK_API, TASK_TYPE}