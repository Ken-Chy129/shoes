
enum ORDER_API {
    EXTEND_ALL = '/api/stockx/extendAllItems',
}

enum STOCKX_API {
    SEARCH = '/api/stockx/searchItems',
}

enum SEARCH_TASK_API {
    CREATE = '/api/stockx/createSearchTask',      // 创建搜索任务
    GET_TASKS = '/api/stockx/getSearchTasks',     // 查询任务列表
    GET_TASK = '/api/stockx/searchTask',          // 查询单个任务详情
}

export {ORDER_API, STOCKX_API, SEARCH_TASK_API}