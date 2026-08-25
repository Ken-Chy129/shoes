package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayPictureApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayPictureServiceTest {

    @Test
    void uploadsThirdPartyImagesAndKeepsExistingEbayHostedImages() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "SKU-1-1"))
                .thenReturn("https://i.ebayimg.com/images/g/new/s-l1600.jpg");
        EbayPictureService service = new EbayPictureService(client);

        List<String> result = service.hostImages(List.of(
                "https://cdn.example.com/shoe.jpg",
                "https://i.ebayimg.com/images/g/existing/s-l1600.jpg"), "SKU-1");

        assertThat(result).containsExactly(
                "https://i.ebayimg.com/images/g/new/s-l1600.jpg",
                "https://i.ebayimg.com/images/g/existing/s-l1600.jpg");
        verify(client, never()).uploadExternalPicture(
                "https://i.ebayimg.com/images/g/existing/s-l1600.jpg", "SKU-1-2");
    }

    @Test
    void rejectsUnsafeOrNonHttpsImageUrlsBeforeCallingEbay() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        EbayPictureService service = new EbayPictureService(client);

        assertThatThrownBy(() -> service.hostImages(
                List.of("file:///etc/passwd"), "SKU-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片链接");
        assertThatThrownBy(() -> service.hostImages(
                List.of("http://cdn.example.com/shoe.jpg"), "SKU-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> service.hostImages(
                List.of("https://127.0.0.1/shoe.jpg"), "SKU-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("公开地址");
        assertThatThrownBy(() -> service.hostImages(
                List.of("https://[::1]/shoe.jpg"), "SKU-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("公开地址");
        verify(client, never()).uploadExternalPicture(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
