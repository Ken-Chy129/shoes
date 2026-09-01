package cn.ken.shoes.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EbayTitleColorExtractorTest {

    @ParameterizedTest
    @CsvSource({
            "'Jordan 5 Retro Grape (2025)', Purple",
            "'Jordan 6 Retro Xuanwu', Black",
            "'Nike Kobe 5 Protro Dodgers', Blue",
            "'Jordan 4 Retro Military Blue (2024) (GS)', Blue",
            "'Jordan 5 Retro Medium Soft Pink', Pink",
            "'Jordan 4 Retro SE Paris Olympics Wet Cement', Gray",
            "'Puma Speedcat OG Black White', Black/White",
            "'Jordan 5 Retro Black University Blue (2026)', Black/Blue",
            "'Jordan 1 Retro Low OG SP Travis Scott Shy Pink', Pink",
            "'Jordan 5 Retro Tokyo T23 (2025)', Yellow",
            "'Nike Air Force 1 Low ''07 White', White",
            "'Salomon XT-6 White Vanilla Ice Plum', White/Purple",
            "'Jordan 5 Retro Raging Bull Red (2021)', Red",
            "'Onitsuka Tiger Mexico 66 Kill Bill', Yellow",
            "'Onitsuka Tiger Mexico 66 Birch Peacoat', Beige/Blue",
            "'Jordan 3 Retro Fire Red (2022) (GS)', Red",
            "'Jordan 3 Retro True Blue (2026)', Blue",
            "'Lime/White', Green/White"
    })
    void extractsStandardColorsAndKnownSneakerColorNames(String title, String expected) {
        assertThat(EbayTitleColorExtractor.extract(title)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "'Converse Chuck 70 Hi Dragon Ball Z Shenron'",
            "'Jordan 3 Retro Family Affair'",
            "''"
    })
    void returnsNullWhenNoReliableColorCanBeExtracted(String title) {
        assertThat(EbayTitleColorExtractor.extract(title)).isNull();
    }
}
