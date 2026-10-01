package cn.ken.shoes.client;

/**
 * eBay 按应用每日限额的接口（如 UploadSiteHostedPictures 每天 5000 次）额度已用完。
 * 在额度重置前继续调用只会继续失败，批量任务应当停止后续调用。
 */
public class EbayQuotaExceededException extends EbayApiException {

    public EbayQuotaExceededException(String message) {
        super(message);
    }
}
