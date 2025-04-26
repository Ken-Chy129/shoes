const PREFIX = "http://localhost:8080"

enum KC_DOWNLOAD_API {
    ORDERS = PREFIX + "/order/kc/excel",
    LABELS = PREFIX + "/order/kc/label"
}

enum UPLOAD_API {
    UPLOAD = "/api/file/upload",
}

export {KC_DOWNLOAD_API, UPLOAD_API}