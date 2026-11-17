package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayMemberMessageService;
import cn.ken.shoes.service.EbayPlatformNotificationParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayPlatformNotificationControllerTest {

    private final EbayPlatformNotificationParser parser = mock(EbayPlatformNotificationParser.class);
    private final EbayMemberMessageService service = mock(EbayMemberMessageService.class);
    private final EbayPlatformNotificationController controller =
            new EbayPlatformNotificationController(parser, service);

    @Test
    void acknowledgesVerifiedNotificationEvenIfProcessingFails() {
        var notification = new EbayPlatformNotificationParser.Notification("AskSellerQuestion", "seller", null);
        when(parser.parseAndVerify(any())).thenReturn(notification);
        doThrow(new RuntimeException("db down")).when(service).onNotification(notification);

        assertThat(controller.receive(new byte[]{1}).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void rejectsBadSignatureWithoutProcessing() {
        when(parser.parseAndVerify(any())).thenThrow(new SecurityException("bad"));

        assertThat(controller.receive(new byte[]{1}).getStatusCode().value()).isEqualTo(412);
        verify(service, never()).onNotification(any());
    }

    @Test
    void rejectsEmptyAndOversizedBodies() {
        assertThat(controller.receive(new byte[0]).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.receive(new byte[EbayPlatformNotificationController.MAX_BODY_BYTES + 1])
                .getStatusCode().value()).isEqualTo(413);
    }
}
