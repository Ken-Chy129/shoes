package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayPictureApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayPictureServiceTest {

    @Test
    void uploadsThirdPartyImagesAndKeepsExistingEbayHostedImages() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "SKU-1-1"))
                .thenReturn(Optional.of(
                        "https://i.ebayimg.com/images/g/new/s-l1600.jpg"));
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
    void skipsImagesThatEbayReportsBelowItsMinimumResolution() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture(
                "https://cdn.example.com/small.jpg", "SKU-1-1"))
                .thenReturn(Optional.empty());
        when(client.uploadExternalPicture(
                "https://cdn.example.com/large.jpg", "SKU-1-2"))
                .thenReturn(Optional.of(
                        "https://i.ebayimg.com/images/g/large/s-l1600.jpg"));
        EbayPictureService service = new EbayPictureService(client);

        List<String> result = service.hostImages(List.of(
                "https://cdn.example.com/small.jpg",
                "https://cdn.example.com/large.jpg"), "SKU-1");

        assertThat(result).containsExactly(
                "https://i.ebayimg.com/images/g/large/s-l1600.jpg");
    }

    @Test
    void rejectsUnsafeOrNonHttpsImageUrlsBeforeCallingEbay() {
        rejectsUnsafeUrls();
    }

    @Test
    void reusesHostedUrlsForImagesAlreadyUploadedToSaveTheDailyQuota() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "SKU-1-1"))
                .thenReturn(Optional.of(
                        "https://i.ebayimg.com/images/g/new/s-l1600.jpg"));
        when(client.uploadExternalPicture(
                "https://cdn.example.com/small.jpg", "SKU-1-2"))
                .thenReturn(Optional.empty());
        when(client.uploadExternalPicture(
                "https://cdn.example.com/other.jpg", "SKU-2-3"))
                .thenReturn(Optional.of(
                        "https://i.ebayimg.com/images/g/other/s-l1600.jpg"));
        EbayPictureService service = new EbayPictureService(client);

        service.hostImages(List.of(
                "https://cdn.example.com/shoe.jpg",
                "https://cdn.example.com/small.jpg"), "SKU-1");
        List<String> second = service.hostImages(List.of(
                "https://cdn.example.com/shoe.jpg",
                "https://cdn.example.com/small.jpg",
                "https://cdn.example.com/other.jpg"), "SKU-2");

        assertThat(second).containsExactly(
                "https://i.ebayimg.com/images/g/new/s-l1600.jpg",
                "https://i.ebayimg.com/images/g/other/s-l1600.jpg");
        verify(client, times(3)).uploadExternalPicture(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private void rejectsUnsafeUrls() {
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

    @Test
    void skipsSingleFailedImageAndConvertsStockXWebpToJpg() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture(
                "https://images.stockx.com/360/a/img07.jpg?fm=jpg&w=559", "SKU-1-1"))
                .thenThrow(new cn.ken.shoes.client.EbayApiException(
                        "eBay图片托管失败：21916544 ExternalPictureURL not found"));
        when(client.uploadExternalPicture(
                "https://images.stockx.com/images/a.jpg?fm=jpg&q=90", "SKU-1-2"))
                .thenReturn(Optional.of("https://i.ebayimg.com/images/g/a/s-l1600.jpg"));
        EbayPictureService service = new EbayPictureService(client);

        List<String> result = service.hostImages(List.of(
                "https://images.stockx.com/360/a/img07.jpg?fm=webp&w=559",
                "https://images.stockx.com/images/a.jpg?fm=webp&q=90"), "SKU-1");

        assertThat(result).containsExactly("https://i.ebayimg.com/images/g/a/s-l1600.jpg");
    }

    @Test
    void stillFailsWhenEveryImageFailsAndStopsOnQuotaExhaustion() {
        EbayPictureApiClient client = mock(EbayPictureApiClient.class);
        when(client.uploadExternalPicture("https://cdn.example.com/missing.jpg", "SKU-1-1"))
                .thenThrow(new cn.ken.shoes.client.EbayApiException("eBay图片托管失败：not found"));
        EbayPictureService service = new EbayPictureService(client);
        assertThatThrownBy(() -> service.hostImages(
                List.of("https://cdn.example.com/missing.jpg"), "SKU-1"))
                .hasMessageContaining("not found");

        EbayPictureApiClient quotaClient = mock(EbayPictureApiClient.class);
        when(quotaClient.uploadExternalPicture("https://cdn.example.com/a.jpg", "SKU-2-1"))
                .thenThrow(new cn.ken.shoes.client.EbayQuotaExceededException("额度已用完"));
        EbayPictureService quotaService = new EbayPictureService(quotaClient);
        assertThatThrownBy(() -> quotaService.hostImages(List.of(
                "https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg"), "SKU-2"))
                .isInstanceOf(cn.ken.shoes.client.EbayQuotaExceededException.class);
        verify(quotaClient, never()).uploadExternalPicture("https://cdn.example.com/b.jpg", "SKU-2-2");
    }
}
