enum PRICE_API {
    QUERY_BY_MODEL = '/api/price/queryByModelNo',
}

enum SETTING_API {
    KC = '/api/setting/kc',
    POISON = '/api/setting/poison',
    QUERY_BRAND_SETTING = '/api/setting/queryBrandSetting',
    UPDATE_BRAND_SETTING = '/api/setting/updateBrandSetting',
    UPDATE_DEFAULT_CRAWL_CNT = '/api/setting/updateDefaultCrawlCnt',
    QUERY_KC_TOKEN = '/api/setting/kc/getAuthorization',
    UPDATE_KC_TOKEN = '/api/setting/kc/updateAuthorization',
    STOCKX_ACCOUNTS = '/api/setting/stockx/accounts',
    SPECIAL_MODEL_PAGE = '/api/setting/specialModelPage',
    ADD_SPECIAL_MODEL = '/api/setting/addSpecialModel',
    DELETE_SPECIAL_MODEL = '/api/setting/deleteSpecialModel',
    IMPORT_SPECIAL_MODEL_EXCEL = '/api/setting/importSpecialModelExcel',
    SPECIAL_MODEL_TEMPLATE = '/api/setting/specialModelTemplate',
}

enum KC_API {
    START_TASK = '/api/kc/startTask',
}

enum SIZE_CHART_API {
    BRANDS = '/api/sizeChart/brands',
    LIST = '/api/sizeChart/list',
}

export {PRICE_API, SETTING_API, KC_API, SIZE_CHART_API}