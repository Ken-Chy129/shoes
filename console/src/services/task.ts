import { request } from '@umijs/max';


enum TASK_API {
    PAGE = '/api/task/page',
    QUERY_TASK_SETTING = '/api/task/querySetting',
    UPDATE_TASK_SETTING = '/api/task/updateSetting',
    RUN_KC = '/api/kc/startTask',
    QUERY_KC_TASK_STATUS = '/api/kc/queryTaskStatus',
    STOP_KC = '/api/kc/stopTask',
}

export {TASK_API}