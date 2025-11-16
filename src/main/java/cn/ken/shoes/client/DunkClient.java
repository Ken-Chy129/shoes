package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.common.DunkApiConstant;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.model.dunk.DunkItem;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            product.setBrandId(supershipLog.getString("brandId"));
            product.setCategoryId(supershipLog.getString("categoryId"));
            product.setItemId(supershipLog.getString("itemId"));
        }
        return Pair.of(pageCount, products);
    }

    public void queryPrice(String modelNo) {
        String url = PoisonApiConstant.PRICE_BY_MODEL_NO
                .replace("{modelNo}", modelNo);
        String rawResult = HttpUtil.doGet(url);
    }

    private void buildDuckSearchRequest(String keyword, String sortKey, Integer page, Integer perPage) {
        DunkSearchRequest request = new DunkSearchRequest();
        request.setKeyword(keyword);
        request.setSortKey(sortKey);
        request.setPage(page);
        request.setPerPage(perPage);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> stringObjectMap = mapper.convertValue(request, new TypeReference<Map<String, Object>>() {
        });
    }
}
