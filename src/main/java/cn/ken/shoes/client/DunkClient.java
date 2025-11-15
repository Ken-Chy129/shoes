package cn.ken.shoes.client;

import cn.ken.shoes.common.DunkApiConstant;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.util.HttpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;

import java.util.Map;
import java.util.Objects;

/**
 * @author Ken-Chy129
 * @date 2025/11/14
 */
public class DunkClient {

    public void search(DunkSearchRequest searchRequest) {
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
