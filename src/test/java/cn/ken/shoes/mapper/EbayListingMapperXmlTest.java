package cn.ken.shoes.mapper;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 项目没有数据库测试环境，这里直接校验关键 SQL 的语义，防止回归。 */
class EbayListingMapperXmlTest {

    @Test
    void priceSnapshotUpdateKeepsQuantityWhenNoneIsGiven() throws Exception {
        // 定时改价有价时只改价格、quantity 传 null；不能把库存快照写成 NULL。
        String sql = statement("updatePriceAndQuantity");
        assertThat(sql).contains("quantity = COALESCE(#{quantity}, quantity)");
        assertThat(sql).contains("price = COALESCE(#{price}, price)");
    }

    @Test
    void groupEndOnlyTouchesActiveMappingsOfTheSameRegion() throws Exception {
        String sql = statement("markGroupEnded");
        assertThat(sql).contains("style_id = #{styleId}")
                .contains("COALESCE(region, 'hk') = #{region}")
                .contains("status = 'active'");
    }

    private String statement(String id) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (InputStream in = getClass().getResourceAsStream("/mapper/EbayListingMapper.xml")) {
            NodeList nodes = factory.newDocumentBuilder().parse(in).getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if (id.equals(element.getAttribute("id"))) {
                    return element.getTextContent().replaceAll("\\s+", " ");
                }
            }
        }
        throw new AssertionError("statement not found: " + id);
    }
}
