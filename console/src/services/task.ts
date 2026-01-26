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
}

export {TASK_API, TASK_TYPE}