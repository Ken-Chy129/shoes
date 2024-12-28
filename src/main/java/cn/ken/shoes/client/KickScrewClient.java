package cn.ken.shoes.client;

import cn.ken.shoes.common.KickScrewApiConstant;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Component
public class KickScrewClient {

    private static final String AGENT = "Algolia for JavaScript (4.24.0); Browser; instantsearch.js (4.74.0); react (18.3.1); react-instantsearch (7.13.0); react-instantsearch-core (7.13.0); next.js (14.2.10); JS Helper (3.22.4)";

    private static final String BODY = "{\"requests\":[{\"indexName\":\"prod_products\",\"params\":\"attributesToSnippet=%5B%22description%3A10%22%5D&clickAnalytics=true&facets=%5B%22brand%22%2C%22gender%22%2C%22lowest_price%22%2C%22main_color%22%2C%22product_type%22%2C%22release_year%22%2C%22sizes%22%5D&filters=NOT%20class%3A%200&highlightPostTag=__%2Fais-highlight__&highlightPreTag=__ais-highlight__&hitsPerPage=24&maxValuesPerFacet=10000&page=0&query=&removeWordsIfNoResults=allOptional&userToken=803310494-1734974059\"}]}";

    public String queryCategory() {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.CATEGORY)
                .queryParam("x-algolia-agent", AGENT)
                .toUriString();
        String result = HttpUtil.doPost(url, BODY, Headers.of(
                "x-algolia-api-key", "173de9e561a4bc91ca6074d4dc6db17c",
                "x-algolia-application-id", "7CCJSEVCO9"
        ));
        System.out.println(result);
        KickScrewCategory kickScrewCategory = Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getObject("facets", KickScrewCategory.class))
                .orElse(null);
        return JSON.toJSONString(kickScrewCategory);
    }


    public String queryItemByBrand(String brand, Integer page) {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.SEARCH_ITEMS)
                .queryParam("brand", brand)
                .queryParam("page", page)
                .toUriString();
        String result = HttpUtil.doGet(url);
        JSONObject jsonObject = parseResult(result);
        return jsonObject.toJSONString();
    }

    public String queryItemByCategory(String brand, Integer page) {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.SEARCH_ITEMS)
                .queryParam("category", brand)
                .queryParam("page", page)
                .toUriString();
        String result = HttpUtil.doGet(url);
        JSONObject jsonObject = parseResult(result);
        return jsonObject.toJSONString();
    }

    private JSONObject parseResult(String result) {
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONObject("pageProps"))
                .map(json -> json.getJSONObject("serverState"))
                .map(json -> json.getJSONObject("initialResults"))
                .map(json -> json.getJSONObject("prod_products"))
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .orElse(new JSONObject());
    }
}
