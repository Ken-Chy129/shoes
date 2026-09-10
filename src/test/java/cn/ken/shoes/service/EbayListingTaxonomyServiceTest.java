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
    void mapsSoccerCleatUsSizeAspectToTheRequestedSize() {
        when(client.getItemAspectsForCategory("0", "109133"))
                .thenReturn(aspects("Brand", "US Size", "Color"));

        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Nike Phantom 6 Low Elite FG SE Travis Scott");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "109133", "IQ8140-900", metadata, "USM", "8.5");

        assertThat(resolved.aspects())
                .containsEntry("US Size", List.of("8.5"));
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
    void defaultsEuSizesToMensShoesWhenGenderIsUnknown() {
        // 资料库缺性别时不能让整个货号上架失败：EU 码按男鞋处理并换算男码。
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Nike");
        chart.setGender("MENS");
        chart.setEuSize("42");
        chart.setMenUSSize("8.5");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getCategorySuggestions("0", "Nike Dunk Low Retro White Black Men Shoes"))
                .thenReturn(new JSONObject());
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(aspects("Brand", "Department", "US Shoe Size"));
        EbayProductMetadata metadata = metadata();
        metadata.setGender(null);

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "DD1391-100", metadata, "EU", "42");

        assertThat(resolved.categoryId()).isEqualTo("15709");
        assertThat(resolved.aspects())
                .containsEntry("Department", List.of("Men"))
                .containsEntry("US Shoe Size", List.of("8.5"));
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
    void usesTheMensStandardSizeWhenEuSizeChartHasBoth() {
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

        assertThat(resolved.aspects()).containsEntry("US Shoe Size", List.of("9"));
    }

    @Test
    void avoidsTheCombinedSizeWhenTheCategoryOnlyRecommendsPlainNumbers() {
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
                        .fluentPut("aspectMode", "FREE_TEXT"))
                .fluentPut("aspectValues", List.of(
                        new JSONObject(true).fluentPut("localizedValue", "9"),
                        new JSONObject(true).fluentPut("localizedValue", "10.5")));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(sizeAspect)));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry("US Shoe Size", List.of("9"));
    }

    @Test
    void displaysMensAndWomensUsSizesTogetherWhenTheCategoryListsCombinedLabels() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Onitsuka Tiger");
        chart.setGender("MENS");
        chart.setEuSize("42.5");
        chart.setMenUSSize("9");
        chart.setWomenUSSize("10.5");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(
                        selectionAspect("US Shoe Size", true,
                                "8.5 Men/10 Women", "9 Men/10.5 Women"))));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry(
                "US Shoe Size", List.of("9 Men/10.5 Women"));
    }

    @Test
    void alignsTheSizeToTheCategoryStandardValues() {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("Onitsuka Tiger");
        chart.setGender("MENS");
        chart.setEuSize("42.5");
        chart.setMenUSSize("9");
        chart.setWomenUSSize("10.5");
        SizeConvertUtil.initCache(List.of(chart));
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(
                        selectionAspect("US Shoe Size", true, "9", "10.5"))));

        EbayProductMetadata metadata = metadata();
        metadata.setBrand("Onitsuka Tiger");
        metadata.setGender("mens");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "1183C102-751", metadata, "EU", "42.5");

        assertThat(resolved.aspects()).containsEntry("US Shoe Size", List.of("9"));
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
                .containsEntry("US Shoe Size", List.of("10.5"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolvedFromWomenSize = service.resolve(
                "15709", "AH7860-139", metadata, "USW", "10.5");

        assertThat(resolvedFromWomenSize.aspects())
                .containsEntry("Department", List.of("Women"))
                .containsEntry("US Shoe Size", List.of("10.5"));
    }

    @Test
    void infersWomenFromTheTitleWhenLegacyMetadataHasNoGender() {
        JSONObject department = new JSONObject(true)
                .fluentPut("localizedAspectName", "Department")
                .fluentPut("aspectConstraint", new JSONObject(true)
                        .fluentPut("aspectRequired", true)
                        .fluentPut("aspectMode", "SELECTION_ONLY"))
                .fluentPut("aspectValues", List.of(
                        new JSONObject(true).fluentPut("localizedValue", "Women"),
                        new JSONObject(true).fluentPut("localizedValue", "Unisex Adults")));
        when(client.getItemAspectsForCategory("0", "95672"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(department)));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("(WMNS) Nike Air Zoom Vomero 5 Photon Dust");
        metadata.setGender(null);

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "95672", "FD0884-025", metadata, "USM", "7");

        assertThat(resolved.aspects()).containsEntry("Department", List.of("Women"));
    }

    @Test
    void derivesRequiredLegacyAspectsFromTheShoeTitle() {
        JSONObject style = selectionAspect("Style", true, "Sneaker");
        JSONObject color = freeTextAspect("Color", true, "Black", "White", "Multicolor");
        JSONObject upper = freeTextAspect("Upper Material", true, "Leather", "Synthetic");
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true)
                        .fluentPut("aspects", List.of(style, color, upper)));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Jordan 4 Retro White Thunder");
        metadata.setProductType(null);
        metadata.setColor(null);
        metadata.setColorway(null);
        metadata.setUpperMaterial(null);

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "FQ8138-001", metadata, "USM", "10");

        assertThat(resolved.aspects())
                .containsEntry("Style", List.of("Sneaker"))
                .containsEntry("Color", List.of("White"))
                .containsEntry("Upper Material", List.of("Leather"));
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
    void fallsBackToTheEnglishTitleWhenTheCachedColorIsNotAnEbayValue() {
        JSONObject colorAspect = selectionAspect(
                "Color", true, "Black", "White", "Multicolor");
        when(client.getItemAspectsForCategory("0", "15709"))
                .thenReturn(new JSONObject(true).fluentPut(
                        "aspects", List.of(colorAspect)));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("adidas Yeezy Boost 350 V2 Cream White Triple White");
        metadata.setColor("白色");
        metadata.setColorway("Cwhite/Cwhite/Cwhite");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                "15709", "CP9366", metadata, "USM", "12");

        assertThat(resolved.aspects()).containsEntry("Color", List.of("White"));
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

    private JSONObject selectionAspect(String name, boolean required, String... values) {
        return aspect(name, required, "SELECTION_ONLY", values);
    }

    private JSONObject freeTextAspect(String name, boolean required, String... values) {
        return aspect(name, required, "FREE_TEXT", values);
    }

    private JSONObject aspect(String name, boolean required, String mode, String... values) {
        return new JSONObject(true)
                .fluentPut("localizedAspectName", name)
                .fluentPut("aspectConstraint", new JSONObject(true)
                        .fluentPut("aspectRequired", required)
                        .fluentPut("aspectMode", mode))
                .fluentPut("aspectValues", java.util.Arrays.stream(values)
                        .map(value -> new JSONObject(true).fluentPut("localizedValue", value))
                        .toList());
    }

    @Test
    void listsGradeSchoolShoesInTheKidsCategoryWithYouthSizesConvertedFromEu() {
        // (GS) = Grade School 大童款：走童鞋类目、Department 为 Unisex Kids，
        // EU 码按 KIDS 尺码表换算并去掉 Y 后缀（eBay 童鞋类目的标准值只是数字）。
        SizeChartDO kids = new SizeChartDO();
        kids.setBrand("Nike");
        kids.setGender("KIDS");
        kids.setEuSize("38");
        kids.setUsSize("5.5Y");
        SizeChartDO mens = new SizeChartDO();
        mens.setBrand("Nike");
        mens.setGender("MENS");
        mens.setEuSize("38");
        mens.setMenUSSize("5.5");
        mens.setWomenUSSize("7");
        SizeConvertUtil.initCache(List.of(kids, mens));
        when(client.getCategorySuggestions("0",
                "Jordan 4 Retro Red Cement (GS) Unisex Kids Shoes"))
                .thenReturn(new JSONObject());
        when(client.getItemAspectsForCategory("0", "155202"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(
                        freeTextAspect("Brand", true),
                        selectionAspect("Department", false, "Unisex Kids"),
                        freeTextAspect("US Shoe Size", true, "5", "5.5", "6"))));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Jordan 4 Retro Red Cement (GS)");
        metadata.setGender("kids");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "408452-161", metadata, "EU", "38");

        assertThat(resolved.categoryId()).isEqualTo("155202");
        assertThat(resolved.aspects())
                .containsEntry("Department", List.of("Unisex Kids"))
                .containsEntry("US Shoe Size", List.of("5.5"));
    }

    @Test
    void recognisesGradeSchoolFromTheTitleEvenWhenGenderIsMissing() {
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Jordan 11 Retro Legend Blue (2024) (GS)");
        metadata.setGender(null);
        when(client.getCategorySuggestions("0",
                "Jordan 11 Retro Legend Blue (2024) (GS) Unisex Kids Shoes"))
                .thenReturn(new JSONObject());
        when(client.getItemAspectsForCategory("0", "155202"))
                .thenReturn(aspects("Brand", "Department"));

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "378038-104", metadata, "EU", "38");

        assertThat(resolved.categoryId()).isEqualTo("155202");
        assertThat(resolved.aspects()).containsEntry("Department", List.of("Unisex Kids"));
    }

    @Test
    void listsPreschoolAndToddlerShoesInTheBabyCategoryWithCSizes() {
        // (PS)/(TD) 小童、婴童款用 C 码，走 Baby Shoes 类目。
        SizeChartDO baby = new SizeChartDO();
        baby.setBrand("Nike");
        baby.setGender("BABY");
        baby.setEuSize("27");
        baby.setUsSize("10C");
        SizeConvertUtil.initCache(List.of(baby));
        when(client.getCategorySuggestions("0",
                "Jordan 4 Retro Red Cement (PS) Unisex Baby & Toddler Shoes"))
                .thenReturn(new JSONObject());
        when(client.getItemAspectsForCategory("0", "147285"))
                .thenReturn(new JSONObject(true).fluentPut("aspects", List.of(
                        freeTextAspect("Brand", true),
                        selectionAspect("Department", true,
                                "Unisex Baby & Toddler", "Boys", "Girls"),
                        freeTextAspect("US Shoe Size", true, "9.5", "10"))));
        EbayProductMetadata metadata = metadata();
        metadata.setTitle("Jordan 4 Retro Red Cement (PS)");
        metadata.setGender("kids");

        EbayListingTaxonomyService.ResolvedTaxonomy resolved = service.resolve(
                null, "BQ7669-161", metadata, "EU", "27");

        assertThat(resolved.categoryId()).isEqualTo("147285");
        assertThat(resolved.aspects())
                .containsEntry("Department", List.of("Unisex Baby & Toddler"))
                .containsEntry("US Shoe Size", List.of("10"));
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
