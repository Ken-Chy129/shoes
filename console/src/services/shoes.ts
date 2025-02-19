import { request } from '@umijs/max';


enum PRICE_API {
    SAVE = '/api/app/save',
    LIST = '/api/app/list',
}

enum SETTING_API {
    QUERY_PRICE_SETTING = '/api/setting/queryPriceSetting',
    UPDATE_PRICE_SETTING = '/api/setting/updatePriceSetting',
    QUERY_BRAND_SETTING = '/api/setting/queryBrandSetting',
}

export {PRICE_API, SETTING_API}