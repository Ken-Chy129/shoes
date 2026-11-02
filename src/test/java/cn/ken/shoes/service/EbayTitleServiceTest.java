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

        assertThat(title).isEqualTo("Nike Dunk Low Retro White Black Men's DD1391-100");
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

        assertThat(title).isEqualTo("Nike Dunk Low Retro White Black Men's DD1391-100");
    }

    @Test
    void preservesAnExcelOrCatalogManualTitleExceptForWhitespaceAndLength() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("  Nike Air Max 1 Premium Men's Shoes New   ");
        metadata.setManualTitle(true);

        String title = service.generate("FD5088-200", "USM", metadata);

        assertThat(title).isEqualTo("Nike Air Max 1 Premium Men's Shoes New");
    }

    @Test
    void insertsGenderBeforeTheStyleId() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro White Black");

        assertThat(service.generate("DD1391-100", "USM", metadata, "Men"))
                .isEqualTo("Nike Dunk Low Retro White Black Men's DD1391-100");
        assertThat(service.generate("DD1391-100", "USW", metadata, "Women"))
                .isEqualTo("Nike Dunk Low Retro White Black Women's DD1391-100");
        assertThat(service.generate("DD1391-100", "EU", metadata, "Unisex Adults"))
                .isEqualTo("Nike Dunk Low Retro White Black Men's DD1391-100");
        assertThat(service.generate("DD1391-100", "EU", metadata, "Unisex Kids"))
                .isEqualTo("Nike Dunk Low Retro White Black Grade School DD1391-100");
        assertThat(service.generate("DD1391-100", "EU", metadata, "Unisex Baby & Toddler"))
                .isEqualTo("Nike Dunk Low Retro White Black Toddler DD1391-100");
    }

    @Test
    void splitsKidsIntoGradeSchoolPreschoolAndToddlerByTitleMarker() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Jordan 4 Retro Red Cement (GS)");
        assertThat(service.generate("408452-161", "EU", metadata, "Unisex Kids"))
                .isEqualTo("Jordan 4 Retro Red Cement (GS) Grade School 408452-161");

        metadata.setTitle("Jordan 4 Retro Red Cement (PS)");
        assertThat(service.generate("BQ7669-161", "EU", metadata, "Unisex Baby & Toddler"))
                .isEqualTo("Jordan 4 Retro Red Cement (PS) Preschool BQ7669-161");

        metadata.setTitle("Jordan 4 Retro Red Cement (TD)");
        assertThat(service.generate("BQ7670-161", "EU", metadata, "Unisex Baby & Toddler"))
                .isEqualTo("Jordan 4 Retro Red Cement (TD) Toddler BQ7670-161");

        metadata.setTitle("Jordan 4 Retro Red Cement Toddler");
        assertThat(service.generate("BQ7670-161", "EU", metadata, "Unisex Baby & Toddler"))
                .isEqualTo("Jordan 4 Retro Red Cement Toddler BQ7670-161");
    }

    @Test
    void insertsGenderBeforeAStyleIdAlreadyInTheTitleWithoutDuplicating() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro Women's DD1391-100");

        assertThat(service.generate("DD1391-100", "USW", metadata, "Women"))
                .isEqualTo("Nike Dunk Low Retro Women's DD1391-100");

        metadata.setTitle("Nike Dunk Low Retro DD1391-100");
        assertThat(service.generate("DD1391-100", "USM", metadata, "Men"))
                .isEqualTo("Nike Dunk Low Retro Men's DD1391-100");
    }

    @Test
    void keepsGenderAndStyleIdWithinEbayLimit() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("adidas Yeezy Boost 350 V2 Extremely Long Limited Edition "
                + "Authentic Lifestyle Running Sports Shoes With Collectible Packaging");

        String title = service.generate("HQ6316", "USW", metadata, "Women");

        assertThat(title).hasSizeLessThanOrEqualTo(80).endsWith(" Women's HQ6316");
    }

    @Test
    void closesAQuotedColorwayCutByTruncation() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("(WMNS) ASICS Gel-Nimbus 26 Sneakers 'Light Celadon Safety Yellow' 1012B601-301");

        String title = service.generate("1012B601-301", "USW", metadata, "Women");

        assertThat(title).hasSizeLessThanOrEqualTo(80)
                .isEqualTo("(WMNS) ASICS Gel-Nimbus 26 Sneakers 'Light Celadon Safety' Women's 1012B601-301");
    }

    @Test
    void replacesATrailingParenthesizedGender() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("ASICS GT-1000 13 White Coral Reef (Women's) 1012B663-101");

        assertThat(service.generate("1012B663-101", "USW", metadata, "Women"))
                .isEqualTo("ASICS GT-1000 13 White Coral Reef Women's 1012B663-101");
    }
}
