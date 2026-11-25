package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.TaskItemDO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 解析真实的 TaskItemMapper.xml，保证新增语句能被 MyBatis 加载（XML 写错会导致后端启动失败）。 */
class TaskItemMapperXmlTest {

    @Test
    void repricingBatchInsertRendersOneValuesGroupPerItem() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        String resource = "mapper/TaskItemMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        TaskItemDO a = new TaskItemDO();
        a.setTaskId(1L);
        a.setProfit35(new BigDecimal("1.23"));
        TaskItemDO b = new TaskItemDO();
        b.setTaskId(1L);

        BoundSql sql = configuration.getMappedStatement(TaskItemMapper.class.getName() + ".insertRepricingBatch")
                .getBoundSql(Map.of("items", List.of(a, b)));

        assertThat(sql.getSql().replaceAll("\\s+", " "))
                .startsWith("INSERT INTO task_item (task_id, round,")
                .contains("profit_35, operate_result, operate_time) VALUES");
        assertThat(sql.getParameterMappings()).hasSize(32);
    }
}
