const PREFIX = "http://localhost:8080"

enum KC_DOWNLOAD_API {
    ORDERS = PREFIX + "/order/kc/excel",
    LABELS = PREFIX + "/order/kc/label"
}

export {KC_DOWNLOAD_API}