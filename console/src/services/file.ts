enum KC_DOWNLOAD_API {
    ORDERS = "/api/order/kc/excel",
    LABELS = "/api/order/kc/label"
}

enum UPLOAD_API {
    UPLOAD = "/api/file/upload",
}

enum STOCKX_DOWNLOAD_API {
    DOWNLOAD_SEARCH = '/api/file/downloadSearchResult',  // 参数: searchTaskId
}

export {KC_DOWNLOAD_API, UPLOAD_API, STOCKX_DOWNLOAD_API}
