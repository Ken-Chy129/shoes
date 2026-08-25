package cn.ken.shoes.model.stockx;

/** StockX 单条撤销出价结果。 */
public record StockXBidDeleteResult(String chainId, String status, boolean success) {
}
