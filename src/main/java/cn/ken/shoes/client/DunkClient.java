package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.common.DunkApiConstant;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.model.dunk.DunkItem;
import cn.ken.shoes.model.dunk.DunkSalesHistory;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.model.excel.DunkPriceExcel;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * @author Ken-Chy129
 * @date 2025/11/14
 */
@Slf4j
@Component
public class DunkClient {

    public Pair<Integer, List<DunkItem>> search(DunkSearchRequest searchRequest) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(DunkApiConstant.SEARCH))
                .newBuilder()
                .addQueryParameter("func", searchRequest.getFunc())
                .addQueryParameter("refId", searchRequest.getRefId())
                .addQueryParameter("keyword", searchRequest.getKeyword())
                .addQueryParameter("sortKey", searchRequest.getSortKey())
                .addQueryParameter("page", String.valueOf(searchRequest.getPage()))
                .addQueryParameter("perPage", String.valueOf(searchRequest.getPerPage()))
                .build();
        String rawResult = HttpUtil.doGet(url);
        JSONObject jsonObject = JSONObject.parseObject(rawResult);
        JSONObject pageInfo = jsonObject.getJSONObject("supershipLog");
        if (pageInfo == null) {
            log.error("search error, result is null, result: {}", rawResult);
            return Pair.of(0, Collections.emptyList());
        }
        Integer pageCount = pageInfo.getInteger("rankingTotalHits");
        JSONObject search = jsonObject.getJSONObject("search");
        if (search == null) {
            log.error("search error, result is null, result: {}", rawResult);
            return Pair.of(pageCount, Collections.emptyList());
        }
        List<DunkItem> products = search.getJSONArray("products").toJavaList(DunkItem.class);
        for (DunkItem product : products) {
            JSONObject supershipLog = product.getSupershipLog();
            if (supershipLog == null) {
                continue;
            }
            String link = product.getLink();
            String[] split = link.split("/");
            product.setModelNo(split[split.length - 1]);
            product.setBrandId(supershipLog.getString("brandId"));
            product.setCategoryId(supershipLog.getString("categoryId"));
            product.setItemId(supershipLog.getString("itemId"));
        }
        return Pair.of(pageCount, products);
    }

    public List<DunkPriceExcel> queryPrice(String modelNo) {
        String url = DunkApiConstant.PRICE
                .replace("{modelNo}", modelNo);
        String rawResult = HttpUtil.doGet(url);
        JSONObject jsonObject = JSONObject.parseObject(rawResult);
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        String status = jsonObject.getString("status");
        if (!"success".equals(status)) {
            log.error("queryPrice error, result: {}", rawResult);
            return Collections.emptyList();
        }
        JSONObject data = jsonObject.getJSONObject("data");
        Map<Integer, DunkPriceExcel> size2PriceMap = new LinkedHashMap<>();
        List<JSONObject> maxPriceList = data.getJSONArray("maxPriceOfSizeList").toJavaList(JSONObject.class);
        for (JSONObject priceObject : maxPriceList) {
            Integer size = priceObject.getInteger("size");
            Integer price = priceObject.getInteger("price");
            String sizeText = priceObject.getString("sizeText");
            DunkPriceExcel dunkPriceExcel = new DunkPriceExcel();
            dunkPriceExcel.setModelNo(modelNo);
            dunkPriceExcel.setSize(size);
            dunkPriceExcel.setSizeText(sizeText);
            dunkPriceExcel.setHighPrice(price);
            size2PriceMap.put(size, dunkPriceExcel);
        }
        List<JSONObject> minPriceList = data.getJSONArray("minPriceOfSizeList").toJavaList(JSONObject.class);
        for (JSONObject priceObject : minPriceList) {
            Integer size = priceObject.getInteger("size");
            Integer price = priceObject.getInteger("price");
            DunkPriceExcel dunkPriceExcel = size2PriceMap.get(size);
            if (dunkPriceExcel == null) {
                continue;
            }
            dunkPriceExcel.setLowPrice(price);
        }
        JSONObject listingItemCountMap = data.getJSONObject("listingItemCountMap");
        JSONObject offeringItemCountMap = data.getJSONObject("offeringItemCountMap");
        List<DunkPriceExcel> result = size2PriceMap.values().stream().toList();
        for (DunkPriceExcel dunkPriceExcel : result) {
            Integer size = dunkPriceExcel.getSize();
            Integer itemCount = listingItemCountMap.getInteger(String.valueOf(size));
            Integer offeringItemCount = offeringItemCountMap.getInteger(String.valueOf(size));
            dunkPriceExcel.setInventory(itemCount);
            dunkPriceExcel.setBuyCount(offeringItemCount);
        }
        return result;
    }

    public List<DunkSalesHistory> querySalesHistory(String modelNo, Integer sizeId) {
        String baseUrl = DunkApiConstant.SALES_HISTORY.replace("{modelNo}", modelNo);
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(baseUrl))
                .newBuilder()
                .addQueryParameter("size_id", String.valueOf(sizeId))
                .addQueryParameter("page", String.valueOf(1))
                .addQueryParameter("per_page", String.valueOf(5))
                .build();
        String rawResult = HttpUtil.doGet(url);
        JSONObject jsonObject = JSONObject.parseObject(rawResult);
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<DunkSalesHistory> history = jsonObject.getJSONArray("history").toJavaList(DunkSalesHistory.class);
        for (DunkSalesHistory dunkSalesHistory : history) {
            dunkSalesHistory.setModelNo(modelNo);
            dunkSalesHistory.setSizeId(sizeId);
        }
        return history;
    }
}
