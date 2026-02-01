import { request } from '@umijs/max';


enum PRICE_API {
    SAVE = '/api/app/save',
    LIST = '/api/app/list',
    QUERY_BY_MODEL = '/api/price/queryByModelNo',
}

enum SETTING_API {
    KC = '/api/setting/kc',
    POISON = '/api/setting/poison',
    STOCKX = '/api/setting/stockx',
    QUERY_BRAND_SETTING = '/api/setting/queryBrandSetting',
    UPDATE_BRAND_SETTING = '/api/setting/updateBrandSetting',
    QUERY_MUST_CRAWL_MODEL_NOS = '/api/setting/queryMustCrawlModelNos',
    UPDATE_MUST_CRAWL_MODEL_NOS = '/api/setting/updateMustCrawlModelNos',
    QUERY_CUSTOM_MODEL_NOS = '/api/setting/queryCustomModelNos',
    UPDATE_CUSTOM_MODEL_NOS = '/api/setting/updateCustomModelNos',
    QUERY_SPECIAL_PRICE_MODEL_NOS = '/api/poison/querySpecialPrice',
    UPDATE_SPECIAL_PRICE_MODEL_NOS = '/api/poison/updateSpecialPrice',
    UPDATE_DEFAULT_CRAWL_CNT = '/api/setting/updateDefaultCrawlCnt',
    QUERY_STOCKX_CONFIG = '/api/setting/queryStockxConfig',
    AUTHORIZE_URL = '/api/setting/stockx/getAuthorizeUrl',
    INIT_TOKEN = '/api/setting/stockx/initToken',
    REFRESH_TOKEN = '/api/setting/stockx/refreshToken',
    QUERY_TOKEN = '/api/setting/stockx/getAuthorization',
    UPDATE_TOKEN = '/api/setting/stockx/updateAuthorization'
}

enum KC_API {
    START_TASK = '/api/kc/startTask',
}

enum POISON_API {
    REFRESH_PRICE = '/api/poison/refreshPrice',
}

enum SIZE_CHART_API {
    LIST = '/api/sizeChart/list',
    ADD = '/api/sizeChart/add',
    UPDATE = '/api/sizeChart/update',
    DELETE = '/api/sizeChart/delete',
}

export {PRICE_API, SETTING_API, KC_API, POISON_API, SIZE_CHART_API}