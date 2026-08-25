package cn.ken.shoes.service;

import cn.ken.shoes.model.ebay.EbayProductMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EbayTitleServiceTest {

    private final EbayTitleService service = new EbayTitleService();

    @Test
    void appendsTheStyleIdToTheKcTitleWithoutAddingOtherSearchTerms() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro White Black");
        metadata.setBrand("Nike");
        metadata.setColorway("White/Black");

        String title = service.generate("DD1391-100", "USM", metadata);

        assertThat(title).isEqualTo("Nike Dunk Low Retro White Black DD1391-100");
    }

    @Test
    void truncatesTheKcTitleButKeepsTheStyleIdWithinEbayLimit() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("adidas Yeezy Boost 350 V2 Extremely Long Limited Edition "
                + "Authentic Lifestyle Running Sports Shoes With Collectible Packaging");
        metadata.setBrand("adidas");
        metadata.setGender("Women");

        String title = service.generate("HQ6316", "USW", metadata);

        assertThat(title).hasSizeLessThanOrEqualTo(80)
                .endsWith("HQ6316")
                .startsWith("adidas Yeezy Boost 350 V2");
    }

    @Test
    void doesNotRepeatAStyleIdAlreadyPresentInTheKcTitle() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro White Black DD1391-100");

        String title = service.generate("dd1391-100", "USM", metadata);

        assertThat(title).isEqualTo("Nike Dunk Low Retro White Black DD1391-100");
    }

    @Test
    void preservesAnExcelOrCatalogManualTitleExceptForWhitespaceAndLength() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("  Nike Air Max 1 Premium Men's Shoes New   ");
        metadata.setManualTitle(true);

        String title = service.generate("FD5088-200", "USM", metadata);

        assertThat(title).isEqualTo("Nike Air Max 1 Premium Men's Shoes New");
    }
}
