import { request } from '@umijs/max';


enum PRICE_API {
    SAVE = '/api/app/save',
    LIST = '/api/app/list',
}

enum SETTING_API {
    PRICE = '/api/setting/queryPriceSetting',
}

export {PRICE_API, SETTING_API}