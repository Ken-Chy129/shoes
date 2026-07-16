package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.ken.shoes.common.SearchTypeEnum;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXOrderExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.util.BrandUtil;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.SizeConvertUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.charset.Charset;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.StringTemplate.STR;

@Slf4j
@Component
public class StockXClient {

    @Value("${stockx.clientId}")
    private String clientId;

    @Value("${stockx.redirectUri}")
    private String redirectUri;

    @Value("${stockx.state}")
    private String state;

    @Value("${stockx.clientSecret}")
    private String clientSecret;

    @Value("${stockx.apiKey}")
    private String apiKey;

    @Value("${stockx.authorization}")
    private String authorization;

    private final String expireTime = "2027-01-06T23:22:45+0800";

    public JSONObject queryOrders(String after) {
        JSONObject jsonObject = queryPro(buildOrder(after));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject(true);
        List<StockXOrderExcel> orders = new ArrayList<>();
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
        JSONObject pageInfo = ask.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));
        result.put("endCursor", pageInfo.getString("endCursor"));
        result.put("orders", orders);
        for (JSONObject edge : ask.getJSONArray("edges").toJavaList(JSONObject.class)) {
            StockXOrderExcel stockXOrderExcel = new StockXOrderExcel();
            JSONObject node = edge.getJSONObject("node");
            stockXOrderExcel.setOrderNumber(node.getString("orderNumber"));
            stockXOrderExcel.setUsd(node.getInteger("amount"));
            stockXOrderExcel.setSoldOn(TimeUtil.formatISO(node.getString("soldOn"), TimeUtil.MM_DD_YYYY));
            JSONObject productVariant = node.getJSONObject("productVariant");
            JSONObject product = productVariant.getJSONObject("product");
            stockXOrderExcel.setTitle(product.getString("title"));
            stockXOrderExcel.setName(product.getString("name"));
            stockXOrderExcel.setStyleId(product.getString("styleId"));
            List<JSONObject> sizeList = productVariant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class);
            for (JSONObject sizeObject : sizeList) {
                String size = sizeObject.getString("size");
                if (size.contains("US")) {
                    stockXOrderExcel.setUsSize(ShoesUtil.getShoesSizeFrom(size));
                } else if (size.contains("EU")) {
                    stockXOrderExcel.setEuSize(ShoesUtil.getShoesSizeFrom(size));
                }
            }
            orders.add(stockXOrderExcel);
        }
        return result;
    }

    /**
     * 查询 StockX Pro 历史记录列表。请求结构来自 pro.stockx.com/history/* 的 SellerListings persisted query。
     */
    public JSONObject queryOrderListings(StockXOrderCategory category, int pageNumber, StockXAccount account) {
        String country = StrUtil.isNotBlank(account.getCountry()) ? account.getCountry() : "US";
        JSONObject response = queryPro(
                buildOrderListingsRequest(category, pageNumber, country).toJSONString(),
                buildViperHeaders(account),
                account.getName());
        if (response == null) {
            return null;
        }
        if ("Unauthorized".equals(response.getString("message"))) {
            JSONObject unauthorized = new JSONObject();
            unauthorized.put("_unauthorized", true);
            return unauthorized;
        }
        JSONObject data = response.getJSONObject("data");
        JSONObject viewer = data != null ? data.getJSONObject("viewer") : null;
        JSONObject sellerListings = viewer != null ? viewer.getJSONObject("sellerListings") : null;
        if (sellerListings == null) {
            log.error("queryOrderListings[{}] 响应无sellerListings, category:{}, page:{}, response:{}",
                    account.getName(), category, pageNumber,
                    response.toJSONString().substring(0, Math.min(300, response.toJSONString().length())));
        }
        return sellerListings;
    }

    static JSONObject buildOrderListingsRequest(StockXOrderCategory category, int pageNumber, String country) {
        JSONObject request = new JSONObject(true);
        request.put("operationName", "SellerListings");

        JSONObject variables = new JSONObject(true);
        variables.put("skipGuidance", true);
        variables.put("skipFlexEligible", true);
        variables.put("pageSize", 50);
        variables.put("sort", "MATCHED_AT");
        variables.put("order", "DESC");
        variables.put("country", country);
        variables.put("market", country);
        variables.put("pageNumber", pageNumber);
        variables.put("currencyCode", "USD");

        JSONObject filters = new JSONObject(true);
        filters.put("listingStatus", Map.of("in", category.getListingStatuses()));
        if (!category.getOrderStatuses().isEmpty()) {
            filters.put("orderStatus", Map.of("in", category.getOrderStatuses()));
        }
        filters.put("spreadCurrency", "USD");
        variables.put("filters", filters);
        request.put("variables", variables);
        request.put("extensions", persistedQuery("0be46d884e6e6945514543ade66ea6f8c7d081bdd799623ac1d7b4e16348b733"));
        return request;
    }

    /** 查询 StockX Pro 待处理订单，返回 viewer.asks 分页对象。 */
    public JSONObject queryPendingAsks(String after, StockXAccount account) {
        String country = StrUtil.isNotBlank(account.getCountry()) ? account.getCountry() : "US";
        JSONObject response = queryPro(
                buildPendingAsksRequest(after, country).toJSONString(),
                buildViperHeaders(account),
                account.getName());
        if (response == null) {
            return null;
        }
        if ("Unauthorized".equals(response.getString("message"))) {
            return new JSONObject(true).fluentPut("_unauthorized", true);
        }
        JSONObject data = response.getJSONObject("data");
        JSONObject viewer = data != null ? data.getJSONObject("viewer") : null;
        JSONObject asks = viewer != null ? viewer.getJSONObject("asks") : null;
        if (asks == null) {
            log.error("queryPendingAsks[{}] 响应无viewer.asks, reason:{}",
                    account.getName(), extractGraphqlError(response));
        }
        return asks;
    }

    /** 请求单个订单延期。chainId 必须使用 ViewerAsks.node.id（即 askId）。 */
    public boolean extendShipDate(String orderId, String askId, StockXAccount account) {
        LimiterHelper.limitStockxGraphql(account.getName());
        JSONObject response = queryPro(
                buildExtendShipDateRequest(orderId, askId).toJSONString(),
                buildViperHeaders(account),
                account.getName());
        if (response == null) {
            return false;
        }
        if ("Unauthorized".equals(response.getString("message"))) {
            throw new IllegalStateException("TOKEN_EXPIRED");
        }
        JSONObject data = response.getJSONObject("data");
        JSONObject extension = data != null ? data.getJSONObject("requestSellerShippingExtension") : null;
        if (extension != null && extension.getBooleanValue("approved")) {
            return true;
        }
        log.warn("extendShipDate[{}] 被拒绝, reason:{}", account.getName(), extractGraphqlError(response));
        return false;
    }

    static JSONObject buildPendingAsksRequest(String after, String country) {
        JSONObject request = new JSONObject(true);
        request.put("operationName", "ViewerAsks");

        JSONObject variables = new JSONObject(true);
        variables.put("skipGuidance", true);
        variables.put("skipFlexEligible", true);
        variables.put("includeHasAttributedAd", true);
        variables.put("pageSize", 30);
        variables.put("sort", "MATCHED_AT");
        variables.put("order", "DESC");
        variables.put("country", country);
        variables.put("market", country);
        variables.put("state", "PENDING");
        variables.put("query", "");
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }

        JSONObject filters = new JSONObject(true);
        filters.put("vertical", Map.of("in", List.of()));
        filters.put("shipmentId", Map.of("in", List.of()));
        filters.put("lowestAsk", new JSONObject(true));
        filters.put("expired", new JSONObject(true));
        filters.put("includeBulkShipmentItems", new JSONObject(true));
        filters.put("statesList", Map.of("in", List.of(410, 411, 415)));
        filters.put("productId", Map.of("in", List.of()));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD")));
        filters.put("askType", Map.of("in", List.of("STANDARD", "AUCTION")));
        variables.put("filters", filters);

        request.put("variables", variables);
        request.put("extensions", persistedQuery("960abd9d0f94d676dc187d3eca887f6de57d182c1fbbed124da1927ad1d3581c"));
        return request;
    }

    static JSONObject buildExtendShipDateRequest(String orderId, String askId) {
        JSONObject request = new JSONObject(true);
        request.put("operationName", "ExtendShipDate");
        request.put("variables", new JSONObject(true)
                .fluentPut("orderId", orderId)
                .fluentPut("note", "Seller self-serve shipping extension")
                .fluentPut("chainId", askId));
        request.put("extensions", persistedQuery("356331b5cf0da7f170d55185e49db5188ba5201cf97645ac3ef3f4de1ccd3149"));
        return request;
    }

    private static JSONObject persistedQuery(String sha256Hash) {
        JSONObject extension = new JSONObject(true);
        extension.put("persistedQuery", new JSONObject(true)
                .fluentPut("version", 1)
                .fluentPut("sha256Hash", sha256Hash));
        return extension;
    }

    public JSONObject queryToDeal(String after) {
        JSONObject jsonObject = queryPro(buildOrder(after));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject(true);
        if (jsonObject.getJSONObject("data") == null || jsonObject.getJSONObject("data").getJSONObject("viewer") == null) {
            log.error("queryToDeal error, {}", jsonObject);
            return null;
        }
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
        if (!ask.containsKey("edges")) {
            log.error("queryToDeal error, no edges, result:{}", jsonObject);
            return null;
        }
        JSONObject pageInfo = ask.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));
        result.put("endCursor", pageInfo.getString("endCursor"));
        List<JSONObject> nodes = new ArrayList<>();
        result.put("nodes", nodes);
        for (JSONObject edge : ask.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (Boolean.parseBoolean(node.getString("shippingExtensionRequested"))) {
                continue;
            }
            nodes.add(node);
        }
        return result;
    }

    public void extendItem(String chainId, String orderId) {
        JSONObject body = new JSONObject(true);
        body.put("operationName", "ExtendShipDate");
        JSONObject variables = new JSONObject(true);
        body.put("variables", variables);
        variables.put("chainId", chainId);
        variables.put("orderId", orderId);
        variables.put("note", "Seller self-serve shipping extension");
        body.put("query", "mutation ExtendShipDate($chainId: String, $orderId: String) {\n  requestSellerShippingExtension(\n    input: {chainId: $chainId, orderId: $orderId}\n  ) {\n    approved\n    shipByDateExtendedTo\n    __typename\n  }\n}");
        queryPro(body.toJSONString());
    }

    public String deleteItems(List<String> idList) {
        return deleteItems(idList, null);
    }

    /**
     * 批量下架，返回 StockX 批次id(QUEUED)；失败返回 null。
     * 注意：返回非null只代表"已受理"，是否真正下架需用 {@link #verifyDeleteBatch} 按 batchId 回查。
     */
    public String deleteItems(List<String> idList, StockXAccount account) {
        if (idList.isEmpty()) {
            return null;
        }
        String accName = account != null ? account.getName() : null;
        LimiterHelper.limitStockxBatch(accName, idList.size());
        JSONObject body = new JSONObject(true);
        body.put("operationName", "BulkDeleteSellerListings");
        JSONObject variables = new JSONObject(true);
        List<Map<String, String>> input = new ArrayList<>();
        for (String id : idList) {
            input.add(Map.of("id", id));
        }
        variables.put("input", input);
        body.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "c6e5f5ce76a05d8877c3f61c5e98678c31855ee46276011e9b8f047eefdad036");
        extensions.put("persistedQuery", persistedQuery);
        body.put("extensions", extensions);
        Headers headers = account != null ? buildViperHeaders(account) : buildProHeaders();
        JSONObject jsonObject = queryPro(body.toJSONString(), headers, accName, true);
        log.info("deleteItems, result:{}", jsonObject);
        if (jsonObject == null) {
            throw new RuntimeException("下架失败:无响应(网络异常或被拦截)");
        }
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            throw new RuntimeException("TOKEN_EXPIRED");
        }
        JSONObject data = jsonObject.getJSONObject("data");
        JSONObject deleteBatch = data != null ? data.getJSONObject("deleteBatchListings") : null;
        if (deleteBatch != null && deleteBatch.getString("id") != null) {
            return deleteBatch.getString("id");
        }
        String reason = extractGraphqlError(jsonObject);
        log.error("deleteItems[{}] failed, reason:{}", accName, reason);
        throw new RuntimeException("下架失败:" + reason);
    }

    /** 下架校验轮询参数 */
    private static final int DELETE_VERIFY_MAX_ATTEMPTS = 5;
    private static final long DELETE_VERIFY_DELAY_MS = 2000;

    /**
     * 按 listingId 回查校验批量下架是否真正生效。
     * <p>Ground truth(已实测)：删除成功的 listing 会以 <b>status=DELETED</b> 返回(或查不到)；
     * 仍是 status=ACTIVE 说明没删掉。改用 listingId 精确查当前状态(不再用会衰减的 batchId 视图，
     * 那会把"还没落地"误判成"已删除")，与官方 Pro 网页判定一致。
     *
     * @param batchId 仅用于日志定位(不再参与查询)
     * @param cancelled 取消判定（可空），轮询期间响应取消
     * @return Map&lt;listingId, 结果文案&gt;：下架成功 / 下架失败 / 下架失败:{原因} / 下架未确认
     */
    public Map<String, String> verifyDeleteBatch(String batchId, List<String> listingIds, StockXAccount account,
                                                 java.util.function.Supplier<Boolean> cancelled) {
        Set<String> pending = new HashSet<>(listingIds);
        Map<String, JSONObject> states = new HashMap<>(); // 累积每条最近一次读到的 node
        boolean everRead = false;
        int attemptsUsed = 0, queryFails = 0;
        for (int attempt = 1; attempt <= DELETE_VERIFY_MAX_ATTEMPTS && !pending.isEmpty(); attempt++) {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
                break;
            }
            attemptsUsed = attempt;
            Map<String, JSONObject> current = verifyListingsByListingIds(new ArrayList<>(pending), account);
            if (current == null) {
                // 校验查询失败，本轮无法判定：不可当作"已删除"，重试
                queryFails++;
                log.warn("verifyDeleteBatch 本轮查询失败, account:{}, batchId:{}, attempt:{}, 待确认{}条", account.getName(), batchId, attempt, pending.size());
            } else {
                everRead = true;
                states.putAll(current);
                // 已删除 = 查不到 或 status 已非 ACTIVE(DELETED/CANCELED/INACTIVE)
                pending.removeIf(id -> {
                    JSONObject node = current.get(id);
                    return node == null || !"ACTIVE".equalsIgnoreCase(node.getString("status"));
                });
            }
            if (pending.isEmpty() || attempt == DELETE_VERIFY_MAX_ATTEMPTS) {
                break;
            }
            long waited = 0;
            while (waited < DELETE_VERIFY_DELAY_MS) {
                if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(500, DELETE_VERIFY_DELAY_MS - waited));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waited += 500;
            }
        }

        Map<String, String> result = new HashMap<>();
        int ok = 0, fail = 0, unconfirmed = 0;
        for (String id : listingIds) {
            if (!everRead) {
                // 从未成功读到校验结果，不能误判成功/失败
                result.put(id, "下架未确认");
                unconfirmed++;
                continue;
            }
            if (!pending.contains(id)) {
                // 轮询中已确认删除(某轮查不到 或 status 已非 ACTIVE)。以 pending 归属为准，
                // 不再读 states——states 可能残留更早一轮的 ACTIVE 快照，会把已删的误判成"仍在架"。
                result.put(id, "下架成功");
                ok++;
                continue;
            }
            // 仍在 pending = 多轮后仍是 ACTIVE(或该条始终没被单独读到)
            JSONObject node = states.get(id);
            if (node == null) {
                result.put(id, "下架未确认");
                unconfirmed++;
                continue;
            }
            JSONObject lastOp = node.getJSONObject("lastOperation");
            String errCode = lastOp != null ? lastOp.getString("errorCode") : null;
            if (errCode != null) {
                String msg = lastOp.getString("displayMessage");
                result.put(id, "下架失败:" + (StrUtil.isNotBlank(msg) ? msg : errCode));
                fail++;
            } else if (Boolean.TRUE.equals(node.getBoolean("synced"))) {
                result.put(id, "下架失败"); // 仍在架且已同步，没删掉
                fail++;
            } else {
                result.put(id, "下架未确认"); // 仍在处理
                unconfirmed++;
            }
        }
        log.info("verifyDeleteBatch 完成, account:{}, batchId:{}, 总数:{}, 用了{}/{}轮(查询失败{}次), 下架成功:{}, 失败:{}, 未确认:{}",
                account.getName(), batchId, listingIds.size(), attemptsUsed, DELETE_VERIFY_MAX_ATTEMPTS, queryFails, ok, fail, unconfirmed);
        return result;
    }

    public List<StockXPriceDO> queryPrice(String productId) {
        JSONObject jsonObject = queryPro(buildPriceQueryRequest(productId));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> variants = jsonObject.getJSONObject("data").getJSONObject("product").getJSONArray("variants").toJavaList(JSONObject.class);
        String modelNo = jsonObject.getJSONObject("data").getJSONObject("product").getString("styleId");
        if (modelNo == null) {
            log.error("queryPrice.modelNo is null, productId:{}", productId);
            return Collections.emptyList();
        }
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject variant : variants) {
            StockXPriceDO stockXPriceDO = new StockXPriceDO();
            stockXPriceDO.setProductId(productId);
            String variantId = variant.getString("id");
            stockXPriceDO.setVariantId(variantId);
            JSONObject euOption = variant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class).stream().filter(option -> option.getString("type").equals("eu")).findFirst().orElse(null);
            if (euOption == null) {
                log.error("queryPrice.euOption is null, productId:{}, variantId:{}, variant:{}", productId, variantId, variant);
                continue;
            }
            stockXPriceDO.setEuSize(ShoesUtil.getShoesSizeFrom(euOption.getString("size")));
            JSONObject highestBid = variant.getJSONObject("market").getJSONObject("state").getJSONObject("highestBid");
            stockXPriceDO.setSellNowAmount(Optional.ofNullable(highestBid).map(bid -> bid.getInteger("amount")).orElse(0));
            JSONObject guidance = variant.getJSONObject("pricingGuidance").getJSONObject("marketConsensusGuidance").getJSONObject("standardSellerGuidance");
            stockXPriceDO.setEarnMoreAmount(guidance.getInteger("earnMore"));
            stockXPriceDO.setSellFasterAmount(guidance.getInteger("sellFaster"));
            stockXPriceDO.setModelNo(modelNo);
            result.add(stockXPriceDO);
        }
        return result;
    }

    public boolean queryListing(String batchId) {
        String rawResult = HttpUtil.doGet(StockXConfig.GET_LISTING_STATUS.replace("{batchId}", batchId), buildHeaders());
        if (rawResult == null) {
            return false;
        }
        JSONObject result = JSON.parseObject(rawResult);
        return "COMPLETED".equals(result.getString("status"));
    }

    public boolean queryListing(String batchId, StockXAccount account) {
        LimiterHelper.limitStockxApi(account.getName());
        String url = StockXConfig.GET_LISTING_STATUS.replace("{batchId}", batchId);
        String rawResult = HttpUtil.doGet(url, buildHeaders(account));
        if (rawResult == null) {
            return false;
        }
        try {
            JSONObject result = JSON.parseObject(rawResult);
            return "COMPLETED".equals(result.getString("status"));
        } catch (Exception e) {
            log.error("queryListing[{}] response非JSON, batchId:{}", account.getName(), batchId);
            return false;
        }
    }

    public String createListing(List<Pair<String, Integer>> items) {
        List<Map<String, Object>> toCreate = new ArrayList<>();
        for (Pair<String, Integer> item : items) {
            String variantId = item.getKey();
            Integer price = item.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("variantId", variantId);
            map.put("amount", String.valueOf(price));
            map.put("quantity", 1);
            map.put("expiresAt", expireTime);
            toCreate.add(map);
        }
        JSONObject jsonObject = new JSONObject(true);
        jsonObject.put("items", toCreate);
        String rawResult = HttpUtil.doPost(StockXConfig.CREATE_LISTING, jsonObject.toJSONString(), buildHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject result = JSON.parseObject(rawResult);
        String batchId = result.getString("batchId");
        String totalItems = result.getString("totalItems");
        return batchId;
    }

    public Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(String query, Integer pageIndex, String sort, String searchType, String country) {
        return searchItemWithPrice(query, pageIndex, sort, searchType, country, null);
    }

    public Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(String query, Integer pageIndex, String sort, String searchType, String country, StockXAccount account) {
        if (pageIndex == null) {
            pageIndex = 1;
        }
        SearchTypeEnum searchTypeEnum = SearchTypeEnum.from(searchType);
        if (searchTypeEnum == null) {
            return Pair.of(0, Collections.emptyList());
        }
        String finalCountry = country != null ? country : "HK";
        Headers headers = account != null ? buildProHeaders(account, finalCountry) : buildProHeaders();
        String accName = account != null ? account.getName() : null;
        JSONObject jsonObject = queryPro(buildItemSearchRequest(query, pageIndex, sort, finalCountry), headers, accName);
        if (jsonObject == null) {
            return Pair.of(0, Collections.emptyList());
        }
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            log.error("searchItemWithPrice|Token已过期或无效，请更新Token");
            return null;
        }
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null || data.getJSONObject("browse") == null || data.getJSONObject("browse").getJSONObject("results") == null) {
            log.error("searchItemWithPrice unexpected response, query:{}, response:{}", query, jsonObject.toJSONString());
            return Pair.of(0, Collections.emptyList());
        }
        JSONObject results = data.getJSONObject("browse").getJSONObject("results");
        JSONObject pageInfo = results.getJSONObject("pageInfo");
        Integer pageCount = pageInfo.getInteger("pageCount");
        if (pageCount == null) {
            pageCount = (int) Math.ceil(pageInfo.getDouble("total") / pageInfo.getInteger("limit"));
        }
        List<JSONObject> itemList = results.getJSONArray("edges").toJavaList(JSONObject.class);
        List<StockXPriceExcel> result = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(itemList.size());
        for (JSONObject item : itemList) {
            Thread.startVirtualThread(() -> {
                try {
                    JSONObject node = item.getJSONObject("node");
                    String title = node.getString("title");
                    String urlKey = node.getString("urlKey");
                    List<StockXPriceExcel> itemResult = fetchItemDetail(urlKey, title, searchTypeEnum, finalCountry, headers, accName);
                    result.addAll(itemResult);
                } catch (Exception e) {
                    log.error("searchItemWithPrice fetchItemDetail error", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Pair.of(pageCount, result);
    }

    private List<StockXPriceExcel> fetchItemDetail(String urlKey, String title, SearchTypeEnum searchTypeEnum, String country, Headers headers, String accountName) {
        JSONObject[] responses = new JSONObject[2];
        CountDownLatch detailLatch = new CountDownLatch(2);
        Thread.startVirtualThread(() -> {
            try {
                responses[0] = queryPro(buildGetProductRequest(urlKey), headers, accountName);
            } finally {
                detailLatch.countDown();
            }
        });
        Thread.startVirtualThread(() -> {
            try {
                responses[1] = queryPro(buildGetMarketDataRequest(urlKey, country), headers, accountName);
            } finally {
                detailLatch.countDown();
            }
        });
        try {
            detailLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        JSONObject productResp = responses[0];
        JSONObject marketResp = responses[1];
        if (productResp == null || marketResp == null) {
            log.info("fetchItemDetail response is null, urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONObject productData = productResp.getJSONObject("data");
        JSONObject marketData = marketResp.getJSONObject("data");
        if (productData == null || productData.getJSONObject("product") == null
                || marketData == null || marketData.getJSONObject("product") == null) {
            log.info("fetchItemDetail product is null, urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONObject product = productData.getJSONObject("product");
        JSONObject marketProduct = marketData.getJSONObject("product");
        String modelNo = product.getString("styleId");
        if (StrUtil.isBlank(modelNo)) {
            log.info("缺少货号，urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONArray productVariants = product.getJSONArray("variants");
        JSONArray marketVariants = marketProduct.getJSONArray("variants");
        if (CollectionUtils.isEmpty(productVariants) || CollectionUtils.isEmpty(marketVariants)) {
            log.info("缺少尺码，urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        Map<String, JSONObject> marketVariantMap = new HashMap<>();
        for (JSONObject mv : marketVariants.toJavaList(JSONObject.class)) {
            marketVariantMap.put(mv.getString("id"), mv);
        }
        List<StockXPriceExcel> itemResult = new ArrayList<>();
        for (JSONObject variant : productVariants.toJavaList(JSONObject.class)) {
            String variantId = variant.getString("id");
            JSONObject sizeChart = variant.getJSONObject("sizeChart");
            if (sizeChart == null) {
                continue;
            }
            JSONObject marketVariant = marketVariantMap.get(variantId);
            if (marketVariant == null) {
                continue;
            }
            StockXPriceExcel excel = new StockXPriceExcel();
            excel.setTitle(title);
            excel.setBrand(product.getString("brand"));
            excel.setId(variantId);
            excel.setUk(urlKey);
            excel.setModelNo(modelNo);
            Map<String, String> sizeMap = sizeChart.getJSONArray("displayOptions").toJavaList(JSONObject.class)
                    .stream().collect(Collectors.toMap(o -> o.getString("type"), o -> o.getString("size")));
            excel.setEuSize(ShoesUtil.getShoesSizeFrom(sizeMap.get("eu")));
            excel.setUsmSize(getUsSize(searchTypeEnum, sizeMap));
            JSONObject mvMarket = marketVariant.getJSONObject("market");
            if (mvMarket != null && mvMarket.getJSONObject("state") != null) {
                JSONObject state = mvMarket.getJSONObject("state");
                excel.setPrice(Optional.ofNullable(state.getJSONObject("lowestAsk")).map(a -> a.getInteger("amount")).orElse(0));
                excel.setPurchasePrice(Optional.ofNullable(state.getJSONObject("highestBid")).map(b -> b.getInteger("amount")).orElse(0));
            }
            if (mvMarket != null && mvMarket.getJSONObject("salesInformation") != null) {
                excel.setLast72HoursSales(Optional.ofNullable(mvMarket.getJSONObject("salesInformation").getInteger("salesLast72Hours")).orElse(0));
            }
            itemResult.add(excel);
        }
        return itemResult;
    }

    private String getUsSize(SearchTypeEnum searchTypeEnum, Map<String, String> sizeMap) {
        return searchTypeEnum == SearchTypeEnum.SHOES ? getUsShoesSize(sizeMap) : getUsClothesSize(sizeMap);
    }

    private String getUsShoesSize(Map<String, String> sizeMap) {
        String usSize;
        usSize = ShoesUtil.getShoesSizeFrom(sizeMap.get("us"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        usSize = ShoesUtil.getShoesSizeFrom(sizeMap.get("us m"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        return ShoesUtil.getShoesSizeFrom(sizeMap.get("us w"));
    }

    private String getUsClothesSize(Map<String, String> sizeMap) {
        String usSize;
        usSize = ShoesUtil.getClothesSize(sizeMap.get("us"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        usSize = ShoesUtil.getClothesSize(sizeMap.get("us m"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        return ShoesUtil.getClothesSize(sizeMap.get("us w"));
    }

    public List<BrandDO> queryBrands() {
        JSONObject jsonObject = queryPro(buildBrandQueryRequest("nike", 1, 1));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> quickList = jsonObject.getJSONObject("data").getJSONObject("browse").getJSONObject("filtersConfig").getJSONArray("quick").toJavaList(JSONObject.class);
        JSONObject brandJson = quickList.stream().filter(json -> json.getString("name").equals("BRANDS")).findFirst().orElse(null);
        if (brandJson == null) {
            return Collections.emptyList();
        }
        List<BrandDO> result = new ArrayList<>();
        for (JSONObject option : brandJson.getJSONArray("options").toJavaList(JSONObject.class)) {
            BrandDO brand = new BrandDO();
            brand.setName(option.getString("value"));
            brand.setTotal(option.getInteger("count"));
            brand.setCrawlCnt(Math.min(brand.getTotal(), 1000));
            brand.setNeedCrawl(true);
            brand.setPlatform("stockx");
            result.add(brand);
        }
        return result;
    }

    private String buildItemSearchRequest(String query, Integer index, String sort, String country) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "getDiscoveryData");
        JSONObject variables = new JSONObject(true);
        variables.put("country", country);
        variables.put("currency", "USD");
        variables.put("flow", "SEARCH_RESULTS");
        variables.put("market", country);
        variables.put("page", Map.of("index", index, "limit", 40));
        variables.put("unifiedDiscoveryEnabled", false);
        variables.put("filters", List.of());
        variables.put("query", query);
        variables.put("sort", Map.of("id", sort));
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "a425201c8e4ccc83ecad211836645be32d6306ad42894b34f2a4b15de3408d20");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildGetProductRequest(String urlKey) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "GetProduct");
        JSONObject variables = new JSONObject(true);
        variables.put("id", urlKey);
        variables.put("skipBreadcrumbs", true);
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "9e0faa98b5745bd79ef47e2479239514b41084c29a86d3f6dacce68543281914");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildGetMarketDataRequest(String urlKey, String country) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "GetMarketData");
        JSONObject variables = new JSONObject(true);
        variables.put("id", urlKey);
        variables.put("currencyCode", "USD");
        variables.put("marketName", country);
        variables.put("viewerContext", "BUYER");
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "589955a4e8c0e714c09999d857089ebc54569d0fe216e5e8538cade33572eb16");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildBrandQueryRequest(String brand, Integer index, Integer limit) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "fragment FiltersFragment on BrowseFilter {\n  id\n  name\n  type\n  ... on BrowseFilterTree {\n    isCollapsed\n    multiSelectEnabled\n    options {\n      id\n      name\n      count\n      selected\n      children\n      level\n      value\n    }\n  }\n  ... on BrowseFilterList {\n    isCollapsed\n    multiSelectEnabled\n    listFilterStyle: style\n    options {\n      id\n      name\n      count\n      selected\n      value\n    }\n  }\n  ... on BrowseFilterBoolean {\n    id\n    name\n    type\n    selected\n    booleanFilterStyle: style\n  }\n  ... on BrowseFilterRange {\n    id\n    isCollapsed\n    name\n    type\n    minimum {\n      value\n    }\n    maximum {\n      value\n    }\n  }\n  ... on BrowseFilterColor {\n    id\n    isCollapsed\n    name\n    type\n    options {\n      name\n      value\n      count\n      selected\n      swatchColor\n      borderColor\n    }\n  }\n}\n\nquery getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n    experiments: {ads: {enabled: true}, dynamicFilter: {enabled: true}, dynamicFilterDefinitions: {enabled: true}, multiselect: {enabled: true}, openSearch: {enabled: $enableOpenSearch}}\n  ) {\n    filtersConfig {\n      quick {\n        ...FiltersFragment\n      }\n      advanced {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n    seo {\n      title\n      blurb\n      richBlurb\n      meta {\n        name\n        value\n      }\n    }\n    sort {\n      id\n      name\n      description\n      seoUrlKey\n      short\n    }\n  }\n}");
        JSONObject variables = new JSONObject(true);
        variables.put("country", "HK");
        variables.put("currency", "USD");
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes", "sneakers")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
        variables.put("flow", "CATEGORY");
        variables.put("market", "HK");
        variables.put("page", Map.of("index", index, "limit", limit));
        variables.put("sort", Map.of("id", "most-active"));
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildPriceQueryRequest(String id) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "ProductVariants");
        requestJson.put("query", "query ProductVariants($id: String!, $currency: CurrencyCode!, $country: String!, $market: String!, $skipFlexEligible: Boolean!, $skipGuidance: Boolean!) {\n  product(id: $id) {\n    id\n    styleId\n    traits {\n      value\n      name\n     }\n    variants {\n      id\n      isFlexEligible @skip(if: $skipFlexEligible)\n      sizeChart {\n        displayOptions {\n          size\n          type\n          }\n        }\n      market(currencyCode: $currency) {\n        state(country: $country, market: $market) {\n          highestBid {\n            amount\n            }\n          }\n        }\n      pricingGuidance(country: $country, market: $market, currencyCode: $currency) @skip(if: $skipGuidance) {\n        marketConsensusGuidance {\n          standardSellerGuidance {\n            sellFaster\n            earnMore\n            }\n          }\n        }\n      }\n      }\n}");
        JSONObject variables = new JSONObject(true);
        variables.put("id", id);
        variables.put("currency", "USD");
        variables.put("country", "HK");
        variables.put("market", "HK");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildOrder(String after) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "ViewerAsks");
        requestJson.put("query", "query ViewerAsks($query: String, $after: String, $pageSize: Int, $currencyCode: CurrencyCode, $state: AsksGeneralState, $filters: AsksFiltersInput, $sort: AsksSortInput, $order: AscDescOrderInput, $country: String!, $market: String!, $skipGuidance: Boolean = true, $skipFlexEligible: Boolean = true, $includeHasAttributedAd: Boolean = false) {\n  viewer {\n    asks(\n      query: $query\n      after: $after\n      first: $pageSize\n      includeHasAttributedAd: $includeHasAttributedAd\n      currencyCode: $currencyCode\n      state: $state\n      filters: $filters\n      sort: $sort\n      order: $order\n    ) {\n      pageInfo {\n        endCursor\n        hasNextPage\n        totalCount\n        __typename\n      }\n      edges {\n        node {\n          ...AskAttributes\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}\n\nfragment AskAttributes on Ask {\n  id\n  amount\n  created\n  bidAskSpread\n  currentCurrency\n  expires\n  soldOn\n  orderNumber\n  state\n  dateToShipBy\n  authCenter\n  inventoryType\n  shippingExtensionRequested\n  associatedAutomation {\n    status\n    id\n    fields {\n      price\n      __typename\n    }\n    __typename\n  }\n  pricingGuidance(country: $country, market: $market) @skip(if: $skipGuidance) {\n    marketConsensusGuidance {\n      standardSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      flexSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n  shipment {\n    id\n    bulk\n    deleted\n    displayId\n    trackingNumber\n    trackingUrl\n    deliveryDate\n    commercialInvoiceUrl\n    documents {\n      sellerShippingInstructions\n      sellerShippingInstructionsThermal\n      __typename\n    }\n    __typename\n  }\n  productVariant {\n    id\n    isFlexEligible @skip(if: $skipFlexEligible)\n    traits {\n      size\n      sizeDescriptor\n      __typename\n    }\n    sizeChart {\n      displayOptions {\n        size\n        __typename\n      }\n      baseType\n      __typename\n    }\n    market(currencyCode: $currencyCode) {\n      state(country: $country, market: $market) {\n        bidInventoryTypes {\n          standard {\n            highest {\n              amount\n              chainId\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        askServiceLevels {\n          standard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          expressStandard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    product {\n      id\n      name\n      styleId\n      model\n      title\n      productCategory\n      contentGroup\n      browseVerticals\n      primaryCategory\n      minimumBid(currencyCode: $currencyCode)\n      traits {\n        name\n        value\n        __typename\n      }\n      taxInformation {\n        id\n        code\n        __typename\n      }\n      media {\n        thumbUrl\n        __typename\n      }\n      sizeDescriptor\n      hazardousMaterial {\n        lithiumIonBucket\n        __typename\n      }\n      lockSelling\n      listingType\n      __typename\n    }\n    __typename\n  }\n  hasAttributedAd\n  __typename\n}");
        JSONObject variables = new JSONObject(true);
        variables.put("country", "HK");
        variables.put("market", "HK");
        variables.put("pageSize", 30);
        variables.put("sort", "MATCHED_AT");
        variables.put("order", "DESC");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", true);
        variables.put("includeHasAttributedAd", true);
        variables.put("query", "");
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }
        JSONObject filters = new JSONObject(true);
        filters.put("vertical", Map.of("in", List.of()));
        filters.put("shipmentId", Map.of("in", List.of()));
        filters.put("lowestAsk", new JSONObject(true));
        filters.put("expired", new JSONObject(true));
        filters.put("includeBulkShipmentItems", new JSONObject(true));
        filters.put("statesList", Map.of("in", List.of(410, 411, 415)));
        filters.put("productId", Map.of("in", List.of()));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD")));
        variables.put("filters", filters);
        variables.put("state", "PENDING");
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private JSONObject queryPro(String body) {
        LimiterHelper.limitStockxGraphql(null);
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, body, buildProHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject;
        try {
            jsonObject = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryPro response非JSON, response:{}", rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        if (jsonObject.containsKey("status")) {
            log.error("queryHotItemsByBrandWithPrice error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryHotItemsByBrandWithPrice|查询被拦截");
            return null;
        }
        return jsonObject;
    }

    private Headers buildProHeaders(StockXAccount account, String country) {
        return Headers.of(
                "Content-Type", "application/json",
                "accept", "application/json",
                "accept-language", "zh-CN",
                "authorization", account.getAuthorization().strip(),
                "apollographql-client-name", "Iron",
                "apollographql-client-version", "2026.06.11.00",
                "app-platform", "Iron",
                "app-version", "2026.06.11.00",
                "selected-country", country,
                "origin", "https://stockx.com",
                "referer", "https://stockx.com/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
                "sec-ch-ua", "\"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile", "?0",
                "sec-ch-ua-platform", "\"Windows\"",
                "sec-fetch-dest", "empty",
                "sec-fetch-mode", "cors",
                "sec-fetch-site", "same-site",
                "x-stockx-device-id", HttpUtil.getStockXDeviceId()
        );
    }

    private Headers buildProHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "accept", "application/json",
                "accept-language", "zh-CN",
                "authorization", getAuthorization(),
                "apollographql-client-name", "Iron",
                "apollographql-client-version", "2026.06.11.00",
                "app-platform", "Iron",
                "app-version", "2026.06.11.00",
                "selected-country", "HK",
                "origin", "https://stockx.com",
                "referer", "https://stockx.com/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
                "sec-ch-ua", "\"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile", "?0",
                "sec-ch-ua-platform", "\"Windows\"",
                "sec-fetch-dest", "empty",
                "sec-fetch-mode", "cors",
                "sec-fetch-site", "same-site",
                "x-stockx-device-id", HttpUtil.getStockXDeviceId()
        );
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", getAuthorization(),
                "x-api-key", apiKey
        );
    }

    private String getAuthorization() {
        List<StockXAccount> accounts = StockXConfig.getEnabledAccounts();
        if (!accounts.isEmpty()) {
            return accounts.get(0).getAuthorization().strip();
        }
        return authorization;
    }

    /**
     * 使用 persisted query 格式查询在售商品（区分库存类型）
     */
    public JSONObject querySellingItemsByInventoryType(String inventoryType, Integer pageNumber) {
        return doQuerySellingItemsByInventoryType(inventoryType, pageNumber, buildViperHeaders(), "US", null);
    }

    private JSONObject doQuerySellingItemsByInventoryType(String inventoryType, Integer pageNumber, Headers headers, String country, String accountName) {
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "SellerListings");

        JSONObject variables = new JSONObject(true);
        variables.put("skipGuidance", false);
        variables.put("skipFlexEligible", true);
        variables.put("pageSize", 100);
        variables.put("sort", "CREATED_AT");
        variables.put("order", "DESC");
        variables.put("country", country);
        variables.put("market", country);
        variables.put("pageNumber", pageNumber != null ? pageNumber : 1);
        variables.put("currencyCode", "USD");

        JSONObject filters = new JSONObject(true);
        filters.put("spreadCurrency", "USD");
        filters.put("inventoryType", Map.of("in", List.of(inventoryType)));
        if ("CUSTODIAL".equals(inventoryType)) {
            filters.put("listingStatus", Map.of("in", List.of("ACTIVE", "ON_HOLD")));
        } else {
            filters.put("listingStatus", Map.of("in", List.of("ACTIVE")));
            filters.put("listingType", Map.of("in", List.of("VERIFIED")));
        }
        variables.put("filters", filters);
        requestJson.put("variables", variables);

        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "ab3842937c803f03d6296570ef963b062e5b01d2e7695ccf3c7aff590a17abef");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);

        int maxRetries = 3;
        JSONObject sellerListings = null;
        for (int retry = 0; retry <= maxRetries; retry++) {
            JSONObject jsonObject = queryPro(requestJson.toJSONString(), headers, accountName);
            if (jsonObject == null) {
                return null;
            }
            if ("Unauthorized".equals(jsonObject.getString("message"))) {
                log.error("querySellingItemsByInventoryType|Token已过期或无效，请更新Token");
                JSONObject errorResult = new JSONObject(true);
                errorResult.put("_unauthorized", true);
                return errorResult;
            }
            JSONObject data = jsonObject.getJSONObject("data");
            JSONObject viewer = data != null ? data.getJSONObject("viewer") : null;
            sellerListings = viewer != null ? viewer.getJSONObject("sellerListings") : null;
            if (sellerListings != null) {
                break;
            }
            boolean hasConnReset = jsonObject.containsKey("errors") && jsonObject.toJSONString().contains("ECONNRESET");
            if (hasConnReset && retry < maxRetries) {
                log.warn("querySellingItemsByInventoryType ECONNRESET, retry {}/{}, page:{}", retry + 1, maxRetries, pageNumber);
                try { Thread.sleep(3000L * (retry + 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                continue;
            }
            log.error("querySellingItemsByInventoryType response invalid, page:{}, response:{}", pageNumber, jsonObject.toJSONString());
            return null;
        }
        if (sellerListings == null) {
            return null;
        }

        JSONObject result = new JSONObject(true);
        JSONObject pageInfo = sellerListings.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));

        List<JSONObject> items = new ArrayList<>();
        result.put("items", items);
        for (JSONObject edge : sellerListings.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            JSONObject productVariant = node.getJSONObject("productVariant");
            if (productVariant == null) {
                continue;
            }
            JSONObject item = new JSONObject(true);
            item.put("id", node.getString("id"));
            item.put("amount", node.getInteger("amount"));
            item.put("isExpired", node.getBoolean("isExpired"));
            item.put("variantId", productVariant.getString("id"));

            JSONObject product = productVariant.getJSONObject("product");
            if (product == null) {
                continue;
            }
            item.put("styleId", product.getString("styleId"));
            item.put("productName", product.getString("model"));
            String itemBrand = BrandUtil.extractStockXBrand(product.getString("model"));
            if (itemBrand == null) {
                itemBrand = BrandUtil.extractStockXBrand(product.getString("primaryCategory"));
            }
            item.put("brand", itemBrand);

            String size = Optional.ofNullable(productVariant.getJSONObject("traits"))
                    .map(traits -> traits.getString("size"))
                    .orElse(null);
            item.put("size", size);

            Optional<String> euSizeFromChart = Optional.ofNullable(productVariant.getJSONObject("sizeChart"))
                    .map(sc -> sc.getJSONArray("displayOptions"))
                    .map(arr -> arr.toJavaList(JSONObject.class))
                    .flatMap(list -> list.stream()
                            .filter(option -> option.getString("size").startsWith("EU"))
                            .findFirst()
                            .map(euOption -> ShoesUtil.getShoesSizeFrom(euOption.getString("size"))));
            if (euSizeFromChart.isPresent()) {
                item.put("euSize", euSizeFromChart.get());
            } else if (size != null) {
                String primaryCategory = product.getString("primaryCategory");
                String brand = BrandUtil.extractStockXBrand(product.getString("model"));
                if (brand == null) {
                    brand = BrandUtil.extractStockXBrand(primaryCategory);
                }
                if (brand != null) {
                    String euSize = SizeConvertUtil.getStockXEuSize(brand, size);
                    if (euSize != null) {
                        item.put("euSize", euSize);
                    }
                }
            }

            // 解析两种最低价
            Optional.ofNullable(productVariant.getJSONObject("market"))
                    .map(m -> m.getJSONObject("state"))
                    .map(s -> s.getJSONObject("askServiceLevels"))
                    .ifPresent(askLevels -> {
                        Optional.ofNullable(askLevels.getJSONObject("standard"))
                                .map(s -> s.getJSONObject("lowest"))
                                .map(l -> l.getInteger("amount"))
                                .ifPresent(amount -> item.put("standardLowest", amount));
                        Optional.ofNullable(askLevels.getJSONObject("expressStandard"))
                                .map(s -> s.getJSONObject("lowest"))
                                .map(l -> l.getInteger("amount"))
                                .ifPresent(amount -> item.put("expressStandardLowest", amount));
                    });

            items.add(item);
        }
        return result;
    }

    /**
     * V2 API 批量更新 listing 价格
     */
    public String batchUpdateListings(List<Map<String, String>> items) {
        LimiterHelper.limitStockxBatch(null, items.size());
        LimiterHelper.limitStockxApi(null);
        JSONObject body = new JSONObject(true);
        body.put("items", items);
        String rawResult = HttpUtil.doPost(StockXConfig.BATCH_UPDATE_LISTING, body.toJSONString(), buildHeaders());
        if (rawResult == null) {
            log.error("batchUpdateListings failed, response is null");
            return null;
        }
        JSONObject result;
        try {
            result = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("batchUpdateListings response非JSON, totalItems:{}, response:{}", items.size(), rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        String batchId = result.getString("batchId");
        if (batchId == null) {
            log.warn("batchUpdateListings response无batchId, totalItems:{}, response:{}", items.size(), rawResult);
        } else {
            log.info("batchUpdateListings success, batchId:{}, totalItems:{}", batchId, items.size());
        }
        return batchId;
    }

    /**
     * V2 API 查询批量更新状态
     */
    public JSONObject queryBatchUpdateStatus(String batchId) {
        String url = StockXConfig.BATCH_UPDATE_LISTING_STATUS.replace("{batchId}", batchId);
        String rawResult = HttpUtil.doGet(url, buildHeaders());
        if (rawResult == null) {
            return null;
        }
        try {
            return JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryBatchUpdateStatus response非JSON, batchId:{}, response:{}", batchId, rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
    }

    private JSONObject queryPro(String body, Headers headers, String accountName) {
        // 默认按"读"处理：不进限流冷却 Guard。StockX 的 "Batch usage limit" 429 只对批量写计数，读不消耗该配额。
        return queryPro(body, headers, accountName, false);
    }

    /**
     * @param rateLimited true 仅用于批量写操作(deleteItems 下架 / batchUpdateListingsGraphql 压价 / createListingV2 上架)：
     *                    经 {@link StockXRateLimitGuard} 做 429 退避+冷却。读操作传 false，直接发请求，不受写配额冷却影响。
     */
    private JSONObject queryPro(String body, Headers headers, String accountName, boolean rateLimited) {
        return queryPro(body, headers, accountName, rateLimited, null);
    }

    private JSONObject queryPro(String body, Headers headers, String accountName, boolean rateLimited, Runnable onFirstRateLimit) {
        String rawResult;
        if (rateLimited) {
            // 只有批量写(压价/上架/下架)才占用 GraphQL 令牌并走 429 冷却 Guard。
            // 读(校验/对账/搜索)不占令牌：已实测 StockX 不对读计 Batch 配额、也不 429 读，
            // 让读与写抢同一令牌桶只会互相拖慢(校验读被写饿死 → 大量"未确认")。
            LimiterHelper.limitStockxGraphql(accountName);
            String label = null;
            try {
                label = JSON.parseObject(body).getString("operationName");
            } catch (Exception ignore) {
                // label 仅用于限流日志定位，解析失败忽略
            }
            rawResult = StockXRateLimitGuard.execute(
                    () -> HttpUtil.doPost(StockXConfig.GRAPHQL, body, headers),
                    accountName, label, onFirstRateLimit);
        } else {
            rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, body, headers);
        }
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject;
        try {
            jsonObject = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryPro response非JSON, response:{}", rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        if (jsonObject == null) {
            log.error("queryPro 响应体为空或解析为null, account:{}, rawLen:{}, raw:[{}]", accountName, rawResult.length(),
                    rawResult.substring(0, Math.min(500, rawResult.length())));
            return null;
        }
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            log.error("queryPro|Token已过期或无效，请更新Token");
            return jsonObject;
        }
        if (jsonObject.containsKey("status")) {
            log.error("queryPro error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryPro|查询被拦截");
            return null;
        }
        return jsonObject;
    }

    private Headers buildViperHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "accept", "*/*",
                "accept-language", "zh-CN",
                "authorization", getAuthorization(),
                "apollographql-client-name", "Viper",
                "apollographql-client-version", "2026.06.11.00",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
                "origin", "https://pro.stockx.com",
                "referer", "https://pro.stockx.com/",
                "sec-ch-ua", "\"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile", "?0",
                "sec-ch-ua-platform", "\"Windows\"",
                "sec-fetch-dest", "empty",
                "sec-fetch-mode", "cors",
                "sec-fetch-site", "same-site",
                "x-stockx-device-id", HttpUtil.getStockXDeviceId()
        );
    }

    // ==================== 多账号支持：带 StockXAccount 参数的方法重载 ====================

    private Headers buildHeaders(StockXAccount account) {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", account.getAuthorization().strip(),
                "x-api-key", account.getApiKey().strip()
        );
    }

    private Headers buildViperHeaders(StockXAccount account) {
        return Headers.of(
                "Content-Type", "application/json",
                "accept", "*/*",
                "accept-language", "zh-CN",
                "authorization", account.getAuthorization().strip(),
                "apollographql-client-name", "Viper",
                "apollographql-client-version", "2026.06.11.00",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
                "origin", "https://pro.stockx.com",
                "referer", "https://pro.stockx.com/",
                "sec-ch-ua", "\"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile", "?0",
                "sec-ch-ua-platform", "\"Windows\"",
                "sec-fetch-dest", "empty",
                "sec-fetch-mode", "cors",
                "sec-fetch-site", "same-site",
                "x-stockx-device-id", HttpUtil.getStockXDeviceId()
        );
    }

    public JSONObject querySellingItemsByInventoryType(String inventoryType, Integer pageNumber, StockXAccount account) {
        String country = account.getCountry() != null ? account.getCountry() : "US";
        return doQuerySellingItemsByInventoryType(inventoryType, pageNumber, buildViperHeaders(account), country, account.getName());
    }

    public String batchUpdateListings(List<Map<String, String>> items, StockXAccount account) {
        LimiterHelper.limitStockxBatch(account.getName(), items.size());
        LimiterHelper.limitStockxApi(account.getName());
        JSONObject body = new JSONObject(true);
        body.put("items", items);
        String rawResult = HttpUtil.doPost(StockXConfig.BATCH_UPDATE_LISTING, body.toJSONString(), buildHeaders(account));
        if (rawResult == null) {
            log.error("batchUpdateListings[{}] failed, response is null", account.getName());
            return null;
        }
        JSONObject result;
        try {
            result = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("batchUpdateListings[{}] response非JSON, totalItems:{}, response:{}", account.getName(), items.size(), rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        String batchId = result.getString("batchId");
        if (batchId == null) {
            log.warn("batchUpdateListings[{}] response无batchId, totalItems:{}, response:{}", account.getName(), items.size(), rawResult);
        } else {
            log.info("batchUpdateListings[{}] success, batchId:{}, totalItems:{}", account.getName(), batchId, items.size());
        }
        return batchId;
    }

    /**
     * 批量压价(GraphQL)，返回 StockX 批次id(QUEUED)；失败返回 null。
     * 注意：返回非null只代表"已受理"，是否真正同步需用 {@link #verifyListingsByListingIds} 按 listingId 回查。
     */
    public String batchUpdateListingsGraphql(List<Map<String, String>> items, StockXAccount account) {
        LimiterHelper.limitStockxBatch(account.getName(), items.size());
        LimiterHelper.limitStockxGraphql(account.getName());
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "BulkUpdateListings");
        JSONObject variables = new JSONObject(true);
        List<JSONObject> graphqlItems = new ArrayList<>();
        for (Map<String, String> item : items) {
            JSONObject gi = new JSONObject(true);
            gi.put("id", item.get("listingId"));
            gi.put("amount", item.get("amount"));
            gi.put("expiresAt", expireTime);
            gi.put("currency", "USD");
            gi.put("checkoutTraceId", UUID.randomUUID().toString());
            gi.put("actionContext", "ASK");
            graphqlItems.add(gi);
        }
        variables.put("items", graphqlItems);
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "9b512eb2b0884486754955fca273c6410f9ca690f3e9b04c509bb4f1efe26f45");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);

        JSONObject result = queryPro(requestJson.toJSONString(), buildViperHeaders(account), account.getName(), true);
        if (result == null) {
            log.error("batchUpdateListingsGraphql[{}] failed, response is null, totalItems:{}", account.getName(), items.size());
            throw new RuntimeException("提交失败:无响应(网络异常或被拦截)");
        }
        if ("Unauthorized".equals(result.getString("message"))) {
            throw new RuntimeException("TOKEN_EXPIRED");
        }
        JSONObject data = result.getJSONObject("data");
        JSONObject updateBatch = data != null ? data.getJSONObject("updateBatchListings") : null;
        if (updateBatch != null && updateBatch.getString("id") != null) {
            String batchId = updateBatch.getString("id");
            log.info("batchUpdateListingsGraphql[{}] queued, batchId:{}, totalItems:{}, status:{}",
                    account.getName(), batchId, items.size(), updateBatch.getString("status"));
            return batchId;
        }
        String reason = extractGraphqlError(result);
        log.error("batchUpdateListingsGraphql[{}] failed, totalItems:{}, reason:{}, response:{}", account.getName(), items.size(),
                reason, result.toJSONString().substring(0, Math.min(300, result.toJSONString().length())));
        throw new RuntimeException("提交失败:" + reason);
    }

    /** 从 GraphQL 响应中提取简要错误信息(优先 errors[0].message)，用于写入明细 */
    private String extractGraphqlError(JSONObject result) {
        String reason = null;
        try {
            JSONArray errors = result.getJSONArray("errors");
            if (errors != null && !errors.isEmpty()) {
                reason = errors.getJSONObject(0).getString("message");
            }
        } catch (Exception ignore) {
            // fallthrough
        }
        if (StrUtil.isBlank(reason)) {
            reason = result.getString("message");
        }
        if (StrUtil.isBlank(reason)) {
            reason = result.toJSONString();
        }
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }

    /**
     * 按 variantID 列表回查当前挂单状态（返回 Map 的 key = variantID）。
     * <p>相比按 batchId 过滤更可靠：SellerListings(batchId) 是临时视图，提交后异步落地慢且会随时间衰减
     * （已实测同一批 50 条提交后只逐渐出现、又陆续从该 batchId 视图消失），按 batchId 会把"还没落地"误判成失败；
     * 而按 variantID 查的是该 variant 当前真实挂单（synced / status=ACTIVE / lastOperation.errorCode）。
     * <p>返回 null = 查询失败(无法判定)；空 Map = 这些 variant 当前都无挂单。
     */
    public Map<String, JSONObject> verifyListingsByVariantIds(List<String> variantIds, StockXAccount account) {
        Map<String, JSONObject> resultMap = new HashMap<>();
        if (CollectionUtils.isEmpty(variantIds) || account == null) {
            return resultMap;
        }
        String country = StrUtil.isNotBlank(account.getCountry()) ? account.getCountry() : "US";
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "SellerListings");
        JSONObject variables = new JSONObject(true);
        variables.put("skipGuidance", false);
        variables.put("skipFlexEligible", true);
        variables.put("country", country);
        variables.put("market", country);
        variables.put("currencyCode", "USD");
        variables.put("pageSize", 100);
        JSONObject filters = new JSONObject(true);
        filters.put("variantId", Map.of("in", variantIds));
        variables.put("filters", filters);
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "0be46d884e6e6945514543ade66ea6f8c7d081bdd799623ac1d7b4e16348b733");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);

        JSONObject jsonObject = queryPro(requestJson.toJSONString(), buildViperHeaders(account), account.getName());
        if (jsonObject == null) {
            log.warn("verifyListingsByVariantIds 查询失败(响应为null), account:{}, variantCnt:{}", account.getName(), variantIds.size());
            return null;
        }
        JSONObject data = jsonObject.getJSONObject("data");
        JSONObject viewer = data != null ? data.getJSONObject("viewer") : null;
        JSONObject sellerListings = viewer != null ? viewer.getJSONObject("sellerListings") : null;
        if (sellerListings == null) {
            log.warn("verifyListingsByVariantIds 响应无 sellerListings(无法判定), account:{}, resp:{}",
                    account.getName(), jsonObject.toJSONString().substring(0, Math.min(200, jsonObject.toJSONString().length())));
            return null;
        }
        if (sellerListings.getJSONArray("edges") == null) {
            return resultMap; // 有 sellerListings 但无 edges = 这些 variant 当前都无挂单（空结果，可信）
        }
        // 同一 variant 可能同时返回多条挂单(历史 DELETED/INACTIVE/CANCELED + 新建 ACTIVE)，
        // 且死挂单的 synced 也是 true。若简单"后写覆盖"，死挂单会盖掉真正的 ACTIVE 挂单，
        // 导致上架校验/对账把已上架误判、或把死挂单当成结果。按 variant 归并时优先保留 ACTIVE，
        // 都不是 ACTIVE 时保留 listingUpdated 最新的一条(以便 errorCode 等终态信息仍可被读到)。
        for (JSONObject edge : sellerListings.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (node == null) {
                continue;
            }
            String vid = node.getString("variantID");
            if (vid == null) {
                continue;
            }
            JSONObject existing = resultMap.get(vid);
            if (existing == null || preferListingNode(node, existing)) {
                resultMap.put(vid, node);
            }
        }
        return resultMap;
    }

    /** 归并同一 variant 的多条挂单：candidate 是否应取代 existing。ACTIVE 优先，其次取 listingUpdated 更晚者。 */
    private static boolean preferListingNode(JSONObject candidate, JSONObject existing) {
        boolean candActive = "ACTIVE".equalsIgnoreCase(candidate.getString("status"));
        boolean existActive = "ACTIVE".equalsIgnoreCase(existing.getString("status"));
        if (candActive != existActive) {
            return candActive;
        }
        String c = candidate.getString("listingUpdated");
        String e = existing.getString("listingUpdated");
        if (c == null) {
            return false;
        }
        if (e == null) {
            return true;
        }
        return c.compareTo(e) > 0; // ISO-8601 时间串可直接字典序比较
    }

    /**
     * 按 listingId 列表回查挂单当前真实状态（返回 Map 的 key = listingId）。
     * <p>相比按 batchId 查(临时视图)：batchId、异步落地慢且会随时间衰减，
     * 校验窗口内常查不到→误判"未确认"；而按 listingId 精确查这几条挂单的<b>当前真实状态</b>，
     * 1:1 返回、不带该 variant 的历史挂单、不撞 pageSize，且已删除的挂单会以 status=DELETED 返回（权威）。
     * 官方 Pro 网页判定挂单状态用的也是"查当前状态"，本方法与之对齐。
     * <p>返回 null = 查询失败(无法判定)；空 Map = 这些 listing 当前都查不到。
     */
    public Map<String, JSONObject> verifyListingsByListingIds(List<String> listingIds, StockXAccount account) {
        Map<String, JSONObject> resultMap = new HashMap<>();
        if (CollectionUtils.isEmpty(listingIds) || account == null) {
            return resultMap;
        }
        String country = StrUtil.isNotBlank(account.getCountry()) ? account.getCountry() : "US";
        JSONObject requestJson = new JSONObject(true);
        requestJson.put("operationName", "SellerListings");
        JSONObject variables = new JSONObject(true);
        variables.put("skipGuidance", false);
        variables.put("skipFlexEligible", true);
        variables.put("country", country);
        variables.put("market", country);
        variables.put("currencyCode", "USD");
        variables.put("pageSize", 100);
        JSONObject filters = new JSONObject(true);
        filters.put("listingIds", Map.of("in", listingIds)); // 实测 StockX 该查询的过滤字段名为 listingIds
        variables.put("filters", filters);
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject(true);
        JSONObject persistedQuery = new JSONObject(true);
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "0be46d884e6e6945514543ade66ea6f8c7d081bdd799623ac1d7b4e16348b733");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);

        JSONObject jsonObject = queryPro(requestJson.toJSONString(), buildViperHeaders(account), account.getName());
        if (jsonObject == null) {
            log.warn("verifyListingsByListingIds 查询失败(响应为null), account:{}, cnt:{}", account.getName(), listingIds.size());
            return null;
        }
        JSONObject data = jsonObject.getJSONObject("data");
        JSONObject viewer = data != null ? data.getJSONObject("viewer") : null;
        JSONObject sellerListings = viewer != null ? viewer.getJSONObject("sellerListings") : null;
        if (sellerListings == null) {
            log.warn("verifyListingsByListingIds 响应无 sellerListings(无法判定), account:{}, resp:{}",
                    account.getName(), jsonObject.toJSONString().substring(0, Math.min(200, jsonObject.toJSONString().length())));
            return null;
        }
        if (sellerListings.getJSONArray("edges") == null) {
            return resultMap; // 有 sellerListings 但无 edges = 这些 listing 当前都查不到（空结果，可信）
        }
        for (JSONObject edge : sellerListings.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (node == null) {
                continue;
            }
            String id = node.getString("id");
            if (id != null) {
                resultMap.put(id, node); // listingId 唯一，无需归并
            }
        }
        log.info("verifyListingsByListingIds[{}] 查询完成: 请求{}条, 命中{}条", account.getName(), listingIds.size(), resultMap.size());
        return resultMap;
    }

    public JSONObject queryBatchUpdateStatus(String batchId, StockXAccount account) {
        LimiterHelper.limitStockxApi(account.getName());
        String url = StockXConfig.BATCH_UPDATE_LISTING_STATUS.replace("{batchId}", batchId);
        String rawResult = HttpUtil.doGet(url, buildHeaders(account));
        if (rawResult == null) {
            return null;
        }
        try {
            return JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryBatchUpdateStatus[{}] response非JSON, batchId:{}, response:{}", account.getName(), batchId, rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
    }

    public String createListingV2(List<Pair<String, Integer>> itemList, StockXAccount account) {
        if (CollectionUtils.isEmpty(itemList)) {
            return null;
        }
        // 上架与压价共用 StockX 账号的 "Batch usage limit"，必须同样计入 5 分钟批量写窗口，
        // 否则上架会绕过共享配额、悄悄吃光额度，导致压价被 429 挤到冷却失败。
        LimiterHelper.limitStockxBatch(account.getName(), itemList.size());
        LimiterHelper.limitStockxApi(account.getName());
        JSONObject body = new JSONObject();
        body.put("operationName", "CreateBatchListings");
        JSONObject variables = new JSONObject();
        body.put("variables", variables);
        List<Map<String, Object>> data = new ArrayList<>();
        variables.put("items", data);
        for (Pair<String, Integer> item : itemList) {
            String variantId = item.getKey();
            String amount = String.valueOf(item.getValue());
            data.add(Map.of(
                    "active", true,
                    "amount", amount,
                    "currency", "USD",
                    "expiresAt", expireTime,
                    "quantity", 1,
                    "variantID", variantId,
                    "inventoryType", "STANDARD",
                    "actionContext", "ASK"
            ));
        }
        JSONObject extensions = new JSONObject();
        JSONObject persistedQuery = new JSONObject();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "6cffac72ff965d13c139e02f75a23484e9dd06676b9b8d3ace038d43f3ddfa23");
        extensions.put("persistedQuery", persistedQuery);
        body.put("extensions", extensions);
        // 上架(批量创建listing)同样是 asks 批量写，受 "Batch usage limit" 429 约束 → 走限流 Guard
        JSONObject jsonObject = queryPro(body.toJSONString(), buildViperHeaders(account), account.getName(), true);
        if (jsonObject == null) {
            throw new RuntimeException("上架失败:无响应(网络异常或被拦截)");
        }
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            throw new RuntimeException("TOKEN_EXPIRED");
        }
        JSONObject respData = jsonObject.getJSONObject("data");
        if (respData != null) {
            JSONObject batch = respData.getJSONObject("createBatchListings");
            if (batch != null && batch.getString("id") != null) {
                String batchId = batch.getString("id");
                log.info("[{}] createListingV2 success, batchId:{}, response:{}", account.getName(), batchId, jsonObject.toJSONString());
                return batchId;
            }
        }
        String reason = extractGraphqlError(jsonObject);
        log.error("[{}] createListingV2 failed, reason:{}, response:{}", account.getName(), reason,
                jsonObject.toJSONString().substring(0, Math.min(200, jsonObject.toJSONString().length())));
        throw new RuntimeException("上架失败:" + reason);
    }

    @Data
    private static class Filter {

        private String id;

        private List<String> selectedValues;

        public Filter(String id, List<String> selectedValues) {
            this.id = id;
            this.selectedValues = selectedValues;
        }
    }
}
