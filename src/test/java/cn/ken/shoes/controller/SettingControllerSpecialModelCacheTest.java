package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.excel.SpecialModelExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingControllerSpecialModelCacheTest {

    private static final String MODEL_NO = "DH6927-017";

    @AfterEach
    void cleanUp() {
        ShoesContext.clearFlawsModelSet();
    }

    @Test
    void addingForbiddenModelImmediatelyUpdatesRuntimeCache() throws Exception {
        CustomModelMapper customModelMapper = mock(CustomModelMapper.class);
        when(customModelMapper.insertIgnore(any())).thenReturn(1);
        SettingController controller = new SettingController();
        setField(controller, "customModelMapper", customModelMapper);
        JSONObject body = new JSONObject();
        body.put("category", "forbidden");
        body.put("modelNos", MODEL_NO);

        Result<Integer> result = controller.addSpecialModel(body);

        assertThat(result.getSuccess()).isTrue();
        assertThat(ShoesContext.isFlawsModel(MODEL_NO, "44")).isTrue();
    }

    @Test
    void deletingForbiddenModelImmediatelyUpdatesRuntimeCache() throws Exception {
        CustomModelDO forbiddenModel = forbiddenModel(MODEL_NO, null);
        ShoesContext.addFlawsModel(forbiddenModel);
        CustomModelMapper customModelMapper = mock(CustomModelMapper.class);
        when(customModelMapper.deleteByTypeAndModelNo(4, MODEL_NO, null)).thenReturn(1);
        SettingController controller = new SettingController();
        setField(controller, "customModelMapper", customModelMapper);

        Result<Boolean> result = controller.deleteSpecialModel("forbidden", MODEL_NO, null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(ShoesContext.isFlawsModel(MODEL_NO, "44")).isFalse();
    }

    @Test
    void importingForbiddenModelImmediatelyUpdatesRuntimeCache() throws Exception {
        CustomModelMapper customModelMapper = mock(CustomModelMapper.class);
        when(customModelMapper.insertIgnore(any())).thenReturn(1);
        SettingController controller = new SettingController();
        setField(controller, "customModelMapper", customModelMapper);
        SpecialModelExcel row = new SpecialModelExcel();
        row.setCategory("禁爬");
        row.setModelNo(MODEL_NO);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, SpecialModelExcel.class).sheet().doWrite(List.of(row));
        MockMultipartFile file = new MockMultipartFile(
                "file", "special-model.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());

        Result<Integer> result = controller.importSpecialModelExcel(file);

        assertThat(result.getSuccess()).isTrue();
        assertThat(ShoesContext.isFlawsModel(MODEL_NO, "44")).isTrue();
    }

    private static CustomModelDO forbiddenModel(String modelNo, String euSize) {
        CustomModelDO model = new CustomModelDO();
        model.setType(4);
        model.setModelNo(modelNo);
        model.setEuSize(euSize);
        return model;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = SettingController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
