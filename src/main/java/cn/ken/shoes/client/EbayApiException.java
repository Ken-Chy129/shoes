package cn.ken.shoes.client;

public class EbayApiException extends RuntimeException {

    public EbayApiException(String message) {
        super(message);
    }

    public EbayApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
