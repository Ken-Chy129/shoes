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
    UPDATE_DEFAULT_CRAWL_CNT = '/api/setting/updateDefaultCrawlCnt',
    QUERY_STOCKX_CONFIG = '/api/setting/queryStockxConfig',
    AUTHORIZE_URL = '/api/setting/stockx/getAuthorizeUrl',
    INIT_TOKEN = '/api/setting/stockx/initToken',
    REFRESH_TOKEN = '/api/setting/stockx/refreshToken'
}

enum KC_API {
    REFRESH_ITEM = '/api/kc/refreshItems',
}

enum POISON_API {
    REFRESH_PRICE = '/api/poison/refreshPrice',
}

export {PRICE_API, SETTING_API, KC_API, POISON_API}