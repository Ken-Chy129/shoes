package cn.ken.shoes.model.stockx;

/** StockX 撤销出价请求项。chainId 对应购买出价查询返回的 node.id。 */
public record StockXBidDeleteItem(String chainId, String currencyCode) {
}
