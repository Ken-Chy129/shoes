import { request } from '@umijs/max';


enum PRICE_API {
    SAVE = '/api/app/save',
    LIST = '/api/app/list',
}

enum SETTING_API {
    QUERY_PRICE_SETTING = '/api/setting/queryPriceSetting',
    UPDATE_PRICE_SETTING = '/api/setting/updatePriceSetting',
    QUERY_BRAND_SETTING = '/api/setting/queryBrandSetting',
    UPDATE_BRAND_SETTING = '/api/setting/updateBrandSetting',
    QUERY_MUST_CRAWL_MODEL_NOS = '/api/setting/queryMustCrawlModelNos',
    UPDATE_MUST_CRAWL_MODEL_NOS = '/api/setting/updateMustCrawlModelNos',
    UPDATE_DEFAULT_CRAWL_CNT = '/api/setting/updateDefaultCrawlCnt',
}

export {PRICE_API, SETTING_API}