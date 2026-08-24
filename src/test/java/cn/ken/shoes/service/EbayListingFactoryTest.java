package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.excel.EbayListingExcel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbayListingFactoryTest {

    private EbayListingFactory factory;

    @BeforeEach
    void setUp() {
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMerchantLocationKey("shantou_chenghai");
        properties.setDefaultFulfillmentPolicyId("6246174000");
        properties.setDefaultPaymentPolicyId("6246171000");
        properties.setDefaultReturnPolicyId("6246169000");
        factory = new EbayListingFactory(properties);
    }

    @Test
    void createsStableSkuAndMensListingFromCompactSize() {
        EbayListingExcel row = row("DD1391-100", "USM10", "129.99");

        EbayListingRequest request = factory.create(row, metadata());

        assertThat(request.getSku()).isEqualTo("EBAY-DD1391-100-USM10-NEW");
        assertThat(request.getCategoryId()).isEqualTo("15709");
        assertThat(request.getAspects())
                .containsEntry("US Shoe Size", List.of("10"))
                .containsEntry("Department", List.of("Men"));
        assertThat(request.getMerchantLocationKey()).isEqualTo("shantou_chenghai");
        assertThat(request.getFulfillmentPolicyId()).isEqualTo("6246174000");
        assertThat(request.getCondition()).isEqualTo("NEW");
    }

    @Test
    void supportsWomensAndEuSizeSystems() {
        EbayListingRequest womens = factory.create(row("STYLE-1", "USW8.5", "99"), metadata());
        EbayListingRequest eu = factory.create(row("STYLE-2", "EU42.5", "99"), metadata());

        assertThat(womens.getCategoryId()).isEqualTo("95672");
        assertThat(womens.getAspects())
                .containsEntry("US Shoe Size", List.of("8.5"))
                .containsEntry("Department", List.of("Women"));
        assertThat(eu.getAspects()).containsEntry("EU Shoe Size", List.of("42.5"));
    }

    @Test
    void rejectsRowsMissingRequiredFieldsOrUsingAmbiguousSizes() {
        EbayListingExcel missingPrice = row("STYLE-1", "USM10", "99");
        missingPrice.setPrice(null);

        assertThatThrownBy(() -> factory.create(missingPrice, metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上架价格");
        assertThatThrownBy(() -> factory.create(row("STYLE-1", "10", "99"), metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USM10");
    }

    private EbayListingExcel row(String styleId, String size, String price) {
        EbayListingExcel row = new EbayListingExcel();
        row.setStyleId(styleId);
        row.setSize(size);
        row.setQuantity(1);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    private EbayProductMetadata metadata() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro White Black");
        metadata.setDescription("Brand new authentic sneakers.");
        metadata.setBrand("Nike");
        metadata.setProductType("Sneakers");
        metadata.setColor("White");
        metadata.setColorway("White/Black");
        metadata.setUpperMaterial("Leather");
        metadata.setImageUrls(List.of("https://cdn.example.com/dunk.jpg"));
        return metadata;
    }
}
