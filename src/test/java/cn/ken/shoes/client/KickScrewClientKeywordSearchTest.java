package cn.ken.shoes.client;

import cn.ken.shoes.model.kickscrew.KickScrewKeywordSearchPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KickScrewClientKeywordSearchTest {

    @Test
    void shoesSearchRestrictsProductTypes() {
        assertThat(KickScrewClient.buildKeywordQuery(" jordan 1 chicago ", "shoes"))
                .isEqualTo("(jordan 1 chicago) AND (product_type:Sneakers OR product_type:Shoes OR product_type:Slippers)");
        assertThat(KickScrewClient.buildKeywordQuery("jordan", "all")).isEqualTo("jordan");
    }

    @Test
    void parsesModelNoAndCursor() {
        String raw = """
                {"data":{"products":{"pageInfo":{"hasNextPage":true,"endCursor":"abc"},"edges":[
                  {"node":{"handle":"air-jordan-1-low-og-chicago-hq6998-600","title":"Air Jordan 1 Low OG 'Chicago' HQ6998-600",
                   "vendor":"Jordan","productType":"Sneakers","availableForSale":true,"metafield":{"value":" HQ6998-600 "}}},
                  {"node":{"handle":"no-model","title":"x","vendor":"y","productType":"Bags","availableForSale":false,"metafield":null}}
                ]}}}
                """;
        KickScrewKeywordSearchPage page = KickScrewClient.parseKeywordSearchPage(raw);
        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.endCursor()).isEqualTo("abc");
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).modelNo()).isEqualTo("HQ6998-600");
        assertThat(page.items().get(0).brand()).isEqualTo("Jordan");
        assertThat(page.items().get(1).modelNo()).isNull();
    }

    @Test
    void upstreamErrorsFailInsteadOfLookingEmpty() {
        assertThatThrownBy(() -> KickScrewClient.parseKeywordSearchPage(
                "{\"errors\":[{\"message\":\"Internal error\"}]}"))
                .hasMessageContaining("Internal error");
        assertThatThrownBy(() -> KickScrewClient.parseKeywordSearchPage("<html>Just a moment...</html>"))
                .hasMessageContaining("非JSON");
        assertThatThrownBy(() -> KickScrewClient.parseKeywordSearchPage(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
