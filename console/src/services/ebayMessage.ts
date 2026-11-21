enum EBAY_MESSAGE_API {
    STATUS = '/api/task/ebay/messages/status',
    LIST = '/api/task/ebay/messages/list',
    LATEST = '/api/task/ebay/messages/latest',
    SETTINGS = '/api/task/ebay/messages/settings',
    PREFERENCES = '/api/task/ebay/messages/notificationPreferences',
    SUBSCRIBE = '/api/task/ebay/messages/subscribe',
    RECONCILE = '/api/task/ebay/messages/reconcile',
}

interface EbayMemberMessage {
    messageId: string;
    itemId: string;
    itemTitle?: string;
    senderId: string;
    subject?: string;
    body?: string;
    status: string;
    matchedRule?: string;
    reply?: string;
    error?: string;
    source?: string;
    messageTime?: string;
    repliedTime?: string;
    gmtCreate: string;
}

const EBAY_MESSAGE_STATUS: Record<string, {label: string; color: string}> = {
    processing: {label: '处理中', color: 'processing'},
    replied: {label: '已自动回复', color: 'success'},
    manual: {label: '需人工回复', color: 'warning'},
    failed: {label: '回复失败', color: 'error'},
    skipped: {label: '已跳过', color: 'default'},
};

export {EBAY_MESSAGE_API, EBAY_MESSAGE_STATUS};
export type {EbayMemberMessage};
