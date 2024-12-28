package cn.ken.shoes.client;

import cn.ken.shoes.common.KickScrewApiConstant;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewUploadItem;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class KickScrewClient {

    private static final String AGENT = "Algolia for JavaScript (4.24.0); Browser; instantsearch.js (4.74.0); react (18.3.1); react-instantsearch (7.13.0); react-instantsearch-core (7.13.0); next.js (14.2.10); JS Helper (3.22.4)";

    private static final String QUERY_CATEGORY_BODY = "{\"requests\":[{\"indexName\":\"prod_products\",\"params\":\"attributesToSnippet=%5B%22description%3A10%22%5D&clickAnalytics=true&facets=%5B%22brand%22%2C%22gender%22%2C%22lowest_price%22%2C%22main_color%22%2C%22product_type%22%2C%22release_year%22%2C%22sizes%22%5D&filters=NOT%20class%3A%200&highlightPostTag=__%2Fais-highlight__&highlightPreTag=__ais-highlight__&hitsPerPage=24&maxValuesPerFacet=10000&page=0&query=&removeWordsIfNoResults=allOptional&userToken=803310494-1734974059\"}]}";

    public KickScrewCategory queryCategory() {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.CATEGORY)
                .queryParam("x-algolia-agent", AGENT)
                .toUriString();
        String result = HttpUtil.doPost(url, QUERY_CATEGORY_BODY, Headers.of(
                "x-algolia-api-key", "173de9e561a4bc91ca6074d4dc6db17c",
                "x-algolia-application-id", "7CCJSEVCO9"
        ));
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getObject("facets", KickScrewCategory.class))
                .orElse(null);
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

    public String queryItemSizePrice(String handle) {
        String result = HttpUtil.doPost(KickScrewApiConstant.SEARCH_ITEM_SIZE_PRICE,
                JSON.toJSONString(Map.of(
                        "query", "\n  query getProduct(\n    $handle: String!\n    $country: CountryCode!\n    $inHomePage: Boolean = false\n  ) @inContext(country: $country) {\n    product(handle: $handle) {\n      ...product\n    }\n  }\n  \n  fragment product on Product {\n    id\n    vendor\n    ... on Product @skip(if: $inHomePage) {\n      \n  metafields(identifiers: [\n    { namespace: \"product\", key: \"description\" },\n    { namespace: \"product\", key: \"strap_material\" },\n    { namespace: \"product\", key: \"case_material\" },\n    { namespace: \"product\", key: \"watch_caliber\" },\n    { namespace: \"product\", key: \"closure\" },\n    { namespace: \"product\", key: \"upper_material\" },\n    { namespace: \"product\", key: \"version\" },\n    { namespace: \"product\", key: \"thickness\" },\n    { namespace: \"product\", key: \"release_date\" },\n    { namespace: \"product\", key: \"model_no\" },\n    { namespace: \"product\", key: \"style\" },\n    { namespace: \"product\", key: \"colorway\" },\n    { namespace: \"product\", key: \"heel_type\" },\n    { namespace: \"product\", key: \"toe_type\" },\n    { namespace: \"product\", key: \"upper\" },\n    { namespace: \"product\", key: \"sole_material\" },\n    { namespace: \"product\", key: \"functionality\" },\n    { namespace: \"product\", key: \"season\" },\n    { namespace: \"product\", key: \"color\" },\n    { namespace: \"product\", key: \"series\" },\n    { namespace: \"product\", key: \"series_handle\" },\n    { namespace: \"product\", key: \"sub_series\" },\n    { namespace: \"product\", key: \"sub_series_handle\" },\n    { namespace: \"product\", key: \"size_report\" },\n    { namespace: \"product\", key: \"has_brand_direct\" },\n    { namespace: \"product\", key: \"nickname\" },\n    { namespace: \"product\", key: \"model_no_list\" },\n    { namespace: \"product\", key: \"occasion\" },\n    { namespace: \"reviews\", key: \"rating\" },\n    { namespace: \"reviews\", key: \"rating_count\" },\n  ]) {\n    key\n    value\n  }\n\n    }\n    ... on Product @include(if: $inHomePage) {\n      metafields(identifiers: [{ namespace: \"product\", key: \"model_no\" }]) {\n        key\n        value\n      }\n    }\n    handle\n    availableForSale\n    title\n    description @skip(if: $inHomePage)\n    options {\n      id\n      name\n      values\n    }\n    compareAtPriceRange {\n      maxVariantPrice {\n        amount\n        currencyCode\n      }\n      minVariantPrice {\n        amount\n        currencyCode\n      }\n    }\n    priceRange {\n      maxVariantPrice {\n        amount\n        currencyCode\n      }\n      minVariantPrice {\n        amount\n        currencyCode\n      }\n    }\n    productType\n    variants(first: 250) {\n      edges {\n        node {\n          id\n          title\n          sku\n          availableForSale\n          ... on ProductVariant @skip(if: $inHomePage) {\n            barcode\n            selectedOptions {\n              name\n              value\n            }\n            metafields(\n              identifiers: [\n                { namespace: \"price_stock\", key: \"is_instant_ship\" },\n                { namespace: \"campaign\", key: \"name\" },\n              ]\n            ) {\n              key\n              namespace\n              value\n            }\n          }\n          price {\n            amount\n            currencyCode\n          }\n          compareAtPrice {\n            amount\n            currencyCode\n          }\n        }\n      }\n    }\n    featuredImage {\n      ...image\n    }\n    ... on Product @skip(if: $inHomePage) {\n      images(first: 20) {\n        edges {\n          node {\n            ...image\n          }\n        }\n      }\n    }\n    ... on Product @include(if: $inHomePage) {\n      images(first: 1) {\n        edges {\n          node {\n            ...image\n          }\n        }\n      }\n    }\n    seo {\n      ...seo\n    }\n    tags\n    updatedAt\n    onlineStoreUrl\n  }\n  \n  fragment image on Image {\n    url\n    altText\n    width\n    height\n  }\n\n  \n  fragment seo on SEO {\n    description\n    title\n  }\n\n\n",
                        "variables", Map.of(
                                "handle", handle,
                                "country", "US"
                        )
                )),
                Headers.of("x-shopify-storefront-access-token", "43a507be1a455a4018117e16f8969b7e")
        );
        List<KickScrewSizePrice> kickScrewSizePrices = Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONObject("data"))
                .map(json -> json.getJSONObject("product"))
                .map(json -> json.getJSONObject("variants"))
                .map(json -> json.getJSONArray("edges"))
                .map(jsonArray -> jsonArray.toJavaList(JSONObject.class))
                .orElse(Collections.emptyList())
                .stream()
                .map(json -> json.getObject("node", KickScrewSizePrice.class))
                .toList();
        return JSON.toJSONString(kickScrewSizePrices);
    }

    public void batchUploadItems(List<KickScrewUploadItem> items) {
        HttpUtil.doPost(KickScrewApiConstant.BATCH_UPLOAD_ITEMS,
            JSON.toJSONString(Collections.singletonMap("items", items)),
            Headers.of("x-api-key", KickScrewConfig.API_KEY)
        );
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
