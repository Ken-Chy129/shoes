package cn.ken.shoes.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KickScrewClientHandleMatchTest {

    @Test
    void acceptsOnlyHandlesEndingWithTheExactModelNo() {
        assertThat(KickScrewClient.matchHandle("FV7472-100", List.of(
                "gs-nike-dunk-low-black-croc-swoosh-fv7472-001",
                "gs-nike-dunk-low-se-sail-croc-fv7472-100")))
                .isEqualTo("gs-nike-dunk-low-se-sail-croc-fv7472-100");
        assertThat(KickScrewClient.matchHandle("1011B867-101", List.of(
                "asics-gel-kayano-31-1011b867-300",
                "asics-gel-kayano-31-1011b867-001"))).isNull();
        assertThat(KickScrewClient.matchHandle("IF1580", List.of(
                "adidas-superstar-if1580"))).isEqualTo("adidas-superstar-if1580");
    }
}
