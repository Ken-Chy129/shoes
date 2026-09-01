package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayTaxonomyApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.util.SizeConvertUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayListingTaxonomyServiceTest {

    private EbayTaxonomyApiClient client;
    private EbayListingTaxonomyService service;

    @BeforeEach
    void setUp() {
        client = mock(EbayTaxonomyApiClient.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultCategoryTreeId("0");
        service = new EbayListingTaxonomyService(properties, client);
        SizeConvertUtil.initCache(List.of());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SizeConvertUtil.initCache(List.of());
    }

    @Test
    void selectsAShoeCategoryAndBuildsOnlySupportedItemSpecifics() {
        when(client.getCategorySuggestions("0", "Nike Dunk Low Retro White Black Men Shoes"))
                .thenReturn(JSON.parseObject("""
                        {"categorySuggestions":[{
                          "category":{"categoryId":"15709","categoryName":"Athletic Shoes"},
                          "categoryTreeNodeAncestors":[
                            {"categoryId":"93427","categoryName":"Men's Shoes"},
                            {"categoryId":"11450","categoryName":"Clothing, Shoes & Accessories"}
                          ],
                          "relevancy":"99.8"
                        }]}
                        """));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(aspects("Brand", "Department", "US Shoe Size", "Color",
                        "Upper Material", "Type", "Style Code", "Model", "Product Line"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "DD1391-100", metadata(), "USM", "10");

        assertThat(resolved.categoryId()).isEqualTo("15709");
        assertThat(resolved.categoryName()).isEqualTo("Athletic Shoes");
        assertThat(resolved.aspects())
                .containsEntry("Brand", List.of("Nike"))
                .containsEntry("Department", List.of("Men"))
                .containsEntry("US Shoe Size", List.of("10"))
                .containsEntry("Color", List.of("White"))
                .containsEntry("Upper Material", List.of("Leather"))
                .containsEntry("Type", List.of("Sneakers"))
                .containsEntry("Style Code", List.of("DD1391-100"))
                .containsEntry("Model", List.of("Dunk Low"))
                .containsEntry("Product Line", List.of("Nike Dunk"));
    }

    @Test
    void preservesTheOptionalExcelCategoryOverride() {
        when(client.getItemAspectsForCategory("0", "12345"))
                .thenReturn(aspects("Brand", "EU Shoe Size"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "12345", "STYLE-1", metadata(), "EU", "42.5");

        assertThat(resolved.categoryId()).isEqualTo("12345");
        assertThat(resolved.aspects())
                .containsEntry("Brand", List.of("Nike"))
                .containsEntry("EU Shoe Size", List.of("42.5"));
        verify(client, never()).getCategorySuggestions("0", "Nike Dunk Low Retro White Black Shoes");
    }

    @Test
    void reportsARequiredCategoryFieldThatCannotBeDerived() {
        when(client.getCategorySuggestions("0", "Nike Dunk Low Retro White Black Men Shoes"))
                .thenReturn(JSON.parseObject("""
                        {"categorySuggestions":[{
                          "category":{"categoryId":"15709","categoryName":"Athletic Shoes"},
                          "categoryTreeNodeAncestors":[{"categoryName":"Men's Shoes"}]
                        }]}
                        """));
        JSONObject required = new JSONObject(true);
        required.put("localizedAspectName", "Country of Origin");
        required.put("aspectConstraint", new JSONObject(true).fluentPut("aspectRequired", true));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(required)));

        assertThatThrownBy(() -> service.resolve(
                null, "DD1391-100", metadata(), "USM", "10"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Country of Origin")
                .hasMessageContaining("商品资料库");
    }

    @Test
    void requiresGenderForEuSizesInsteadOfSilentlySelectingMensShoes() {
        EbayProductMetadata metadata = metadata();
        metadata.setGender(null);

        assertThatThrownBy(() -> service.resolve(null, "DD1391-100", metadata, "EU", "42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("性别")
                .hasMessageContaining("分类ID");
    }

    @Test
    void convertsEuSizeToMensUsSizeWhenCategoryRequiresUsShoeSize() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Onitsuka Tiger");
        chart.setGender("MENS");
        chart.setEuSize("42.5");
        chart.setMenUSSize("9");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(aspects("US Shoe Size"));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry("US Shoe Size", List.of("9"));
    }

    @Test
    void displaysMensAndWomensUsSizesTogetherWhenEuSizeChartHasBoth() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Onitsuka Tiger");
        chart.setGender("MENS");
        chart.setEuSize("42.5");
        chart.setMenUSSize("9");
        chart.setWomenUSSize("10.5");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(aspects("US Shoe Size"));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry(
                "US Shoe Size", List.of("9 Men/10.5 Women"));
    }

    @Test
    void keepsCombinedSizeWhenEbayCategoryAllowsIt() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Onitsuka Tiger");
        chart.setGender("MENS");
        chart.setEuSize("42.5");
        chart.setMenUSSize("9");
        chart.setWomenUSSize("10.5");
        SizeConvertUtil.initCache(List.of(chart));
        JSONObject sizeAspect = new JSONObject(true)
                .fluentPut("localizedAspectName", "US Shoe Size")
                .fluentPut("aspectConstraint", new JSONObject(true)
                        .fluentPut("aspectRequired", true)
                        .fluentPut("aspectMode", "SELECTION_ONLY"))
                .fluentPut("aspectValues", List.of(new JSONObject(true)
                        .fluentPut("localizedValue", "9 Men/10.5 Women")));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(sizeAspect)));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry(
                "US Shoe Size", List.of("9 Men/10.5 Women"));
    }

    @Test
    void treatsUsPrefixAsSizeInputAndUsesKcGenderForDepartment() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Air Jordan");
        chart.setStockxBrand("Jordan");
        chart.setGender("WOMENS");
        chart.setEuSize("42.5");
        chart.setUsSize("10.5");
        chart.setMenUSSize("9");
        chart.setWomenUSSize("10.5");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(aspects("Department", "US Shoe Size"));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Jordan 11 Retro Low Citrus (2021) (Women's)");
        metadata.setBrand("Jordan");
        metadata.setGender("women");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "AH7860-139", metadata, "USM", "9");

        assertThat(resolved.aspects())
                .containsEntry("Department", List.of("Women"))
                .containsEntry("US Shoe Size", List.of("9 Men/10.5 Women"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolvedFromWomenSize = service.resolve(
                "15709", "AH7860-139", metadata, "USW", "10.5");

        assertThat(resolvedFromWomenSize.aspects())
                .containsEntry("Department", List.of("Women"))
                .containsEntry("US Shoe Size", List.of("9 Men/10.5 Women"));
    }

    @Test
    void mapsACombinedCatalogColorToTheFirstAllowedEbayColor() {
        JSONObject colorAspect = new JSONObject(true)
                .fluentPut("localizedAspectName", "Color")
                .fluentPut("aspectConstraint", new JSONObject(true)
                        .fluentPut("aspectRequired", true)
                        .fluentPut("aspectMode", "SELECTION_ONLY"))
                .fluentPut("aspectValues", List.of(
                        new JSONObject(true).fluentPut("localizedValue", "Black"),
                        new JSONObject(true).fluentPut("localizedValue", "White")));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(colorAspect)));
        EbayProductMetadata metadata = metadata();
        metadata.setColor("Black/White");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "398846-01", metadata, "USM", "9");

        assertThat(resolved.aspects()).containsEntry("Color", List.of("Black"));
    }

    @Test
    void keepsStandardShoeFallbackWhenApplicationTokenIsTemporarilyUnavailable() {
        when(client.getCategorySuggestions("0", "Nike Dunk Low Retro White Black Men Shoes"))
                .thenThrow(new IllegalStateException("application token unavailable"));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenThrow(new IllegalStateException("application token unavailable"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "DD1391-100", metadata(), "USM", "10");

        assertThat(resolved.categoryId()).isEqualTo("15709");
        assertThat(resolved.aspects())
                .containsEntry("Brand", List.of("Nike"))
                .containsEntry("US Shoe Size", List.of("10"));
    }

    private JSONObject aspects(String... names) {
        return new JSONObject(true).fluentPut("aspects", java.util.Arrays.stream(names)
                .map(name -> new JSONObject(true)
                        .fluentPut("localizedAspectName", name)
                        .fluentPut("aspectConstraint", new JSONObject(true)
                                .fluentPut("aspectRequired", false)
                                .fluentPut("aspectMode", "FREE_TEXT")))
                .toList());
    }

    private EbayProductMetadata metadata() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro White Black");
        metadata.setBrand("Nike");
        metadata.setProductType("Sneakers");
        metadata.setGender("men");
        metadata.setColor("White");
        metadata.setUpperMaterial("Leather");
        metadata.setModelName("Dunk Low");
        metadata.setProductLine("Nike Dunk");
        return metadata;
    }
}
