package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SettingControllerDeleteAllSpecialModelsTest {

    private final MustCrawlMapper mustCrawlMapper = mock(MustCrawlMapper.class);
    private final CustomModelMapper customModelMapper = mock(CustomModelMapper.class);
    private final SettingController controller = new SettingController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "mustCrawlMapper", mustCrawlMapper);
        ReflectionTestUtils.setField(controller, "customModelMapper", customModelMapper);
        TransactionSynchronizationManager.initSynchronization();
        CustomModelDO model = new CustomModelDO();
        model.setModelNo("TEST-SPECIAL");
        model.setEuSize("42");
        ShoesContext.addFlawsModel(model);
        ShoesContext.addNotCompareModel(model);
        ShoesContext.addThreeFiveModel(model);
        ShoesContext.addNoPrice(model);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        ShoesContext.clearFlawsModelSet();
        ShoesContext.clearNotCompareModelSet();
        ShoesContext.clearThreeFiveModelSet();
        ShoesContext.clearNoPriceModelSet();
    }

    @Test
    void clearsOnlySpecialModelCategoriesAndUpdatesCachesAfterCommit() throws Exception {
        when(mustCrawlMapper.deleteByPlatform("kc")).thenReturn(2);
        when(customModelMapper.clearByType(4)).thenReturn(3);
        when(customModelMapper.clearByType(2)).thenReturn(4);

        Result<Integer> result = controller.deleteAllSpecialModels();

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(9);
        verify(mustCrawlMapper).deleteByPlatform("kc");
        verify(customModelMapper).clearByType(4);
        verify(customModelMapper).clearByType(2);
        verifyNoMoreInteractions(mustCrawlMapper, customModelMapper);
        assertThat(SettingController.class.getMethod("deleteAllSpecialModels")
                .getAnnotation(Transactional.class)).isNotNull();
        assertSpecialCachesPresent();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(ShoesContext.getFlawsModelSet()).isEmpty();
        assertThat(ShoesContext.getNotCompareModelSet()).isEmpty();
        assertThat(ShoesContext.getThreeFiveModelSet()).containsExactly("TEST-SPECIAL:42");
        assertThat(ShoesContext.getNoPriceModelSet()).containsExactly("TEST-SPECIAL");
    }

    @Test
    void databaseFailureDoesNotClearRuntimeCaches() {
        when(customModelMapper.clearByType(2)).thenThrow(new IllegalStateException("delete failed"));

        assertThatThrownBy(controller::deleteAllSpecialModels)
                .isInstanceOf(IllegalStateException.class);

        assertSpecialCachesPresent();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void rollbackDoesNotClearRuntimeCaches() {
        controller.deleteAllSpecialModels();

        TransactionSynchronizationManager.getSynchronizations().forEach(
                callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertSpecialCachesPresent();
    }

    @Test
    void emptyDatabaseIsAnIdempotentSuccess() {
        Result<Integer> result = controller.deleteAllSpecialModels();

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isZero();
    }

    private void assertSpecialCachesPresent() {
        assertThat(ShoesContext.isFlawsModel("TEST-SPECIAL", "42")).isTrue();
        assertThat(ShoesContext.isNotCompareModel("TEST-SPECIAL", "42")).isTrue();
    }
}
