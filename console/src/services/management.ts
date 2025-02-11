enum NAMESPACE_API {
    LIST_BY_APPID = '/api/management/namespace/selectByAppId',
}

enum FIELD_API {
    LIST_BY_NAMESPACE_ID = '/api/management/field/selectByNamespaceId',
    PAGE_BY_CONDITION = '/api/management/field/selectByCondition',
    GET = '/api/management/field/get',
    PUSH = '/api/management/field/push',
}

enum LOG_API {
    PAGE_BY_CONDITION = '/api/management/log/selectByCondition',
}

enum TEMPLATE_API {
    LIST_BY_APPID = '/api/management/template/selectByAppId',
    PAGE_FIELD_BY_CONDITION = '/api/management/template/selectFieldPageByCondition',

    NEW = '/api/management/template/insert',
    DELETE = '/api/management/template/deleteById',
    ADD_FIELD = '/api/management/template/addField',
    PUSH = '/api/management/template/push',

    UPDATE_FIELD = '/api/management/template/updateField',
    PUSH_FIELD = '/api/management/template/pushField',
    DELETE_FIELD = '/api/management/template/deleteField',
}

export {NAMESPACE_API, FIELD_API, LOG_API, TEMPLATE_API}
