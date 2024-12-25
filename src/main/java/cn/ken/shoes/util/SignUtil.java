package cn.ken.shoes.util;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 签名工具
 */
@Slf4j
public class SignUtil {

    private static String mapToQueryString(Map<String, Object> params) {
        // 过滤非空值，并将json数组转换为逗号分隔的字符串
        Map<String, String> filteredParams = params.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().toString().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (entry.getValue() instanceof List<?> list) {
                                return list.stream()
                                        .map(Object::toString)
                                        .collect(Collectors.joining(","));
                            } else {
                                return entry.getValue().toString();
                            }
                        },
                        (existing, replacement) -> existing, // 避免key冲突
                        LinkedHashMap::new // 保持插入顺序
                ));

        // 按照参数名ASCII码从小到大排序（字典序）
        List<Map.Entry<String, String>> sortedEntries = filteredParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        // 使用URL键值对格式拼接成字符串stringA
        StringBuilder queryStringBuilder = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : sortedEntries) {
                if (!queryStringBuilder.isEmpty()) {
                    queryStringBuilder.append("&");
                }
                queryStringBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return queryStringBuilder.toString();
    }

    public static void main(String[] args) {
        // 示例数据
        Map<String, Object> params = new HashMap<>();
        params.put("app_key", "4d1715e032c44b709ef4954ef13e0950");
        params.put("appoint_no", "A14343543654");
        Map<String, Object> son1 = new LinkedHashMap<>();
        son1.put("spu_id", 81293);
        son1.put("sku_id", 487752589);
        son1.put("bar_code", "487752589");
        son1.put("article_number", "wucaishi");
        son1.put("appoint_num", 10);
        son1.put("brand_id", 10444);
        son1.put("category_id", 46);
        Map<String, Object> son2 = new LinkedHashMap<>();
        son2.put("spu_id", 81293);
        son2.put("sku_id", 487752589);
        son2.put("bar_code", "487752589");
        son2.put("article_number", "wucaishi");
        son2.put("appoint_num", 10);
        son2.put("brand_id", 10444);
        son2.put("category_id", 46);
        params.put("sku_list", Arrays.asList(JSON.toJSONString(son1), JSON.toJSONString(son2)));

        params.put("timestamp", 1603354338917L);
        // 调用方法并打印结果
        System.out.println(mapToQueryString(params));

        String s = "app_key=4d1715e032c44b709ef4954ef13e0950&appoint_no=A14343543654&sku_list=%7B%22spu_id%22%3A81293%2C%22sku_id%22%3A487752589%2C%22bar_code%22%3A%22487752589%22%2C%22article_number%22%3A%22wucaishi%22%2C%22appoint_num%22%3A10%2C%22brand_id%22%3A10444%2C%22category_id%22%3A46%7D%2C%7B%22spu_id%22%3A81293%2C%22sku_id%22%3A487752589%2C%22bar_code%22%3A%22487752589%22%2C%22article_number%22%3A%22wucaishi%22%2C%22appoint_num%22%3A10%2C%22brand_id%22%3A10444%2C%22category_id%22%3A46%7D&timestamp=1603353500369fb91e9e96f054166b567eec1b170ae2b";
        System.out.println(stringToMD5UpperCase(s));
    }


    /**
     * 拼接参数md5加密，生成签名
     */
    private static String stringToMD5UpperCase(String input) {
        try {
            // 获取MD5摘要算法的MessageDigest对象
            MessageDigest md = MessageDigest.getInstance("MD5");

            // 执行消息摘要计算
            byte[] digest = md.digest(input.getBytes());

            // 将字节数组转换成16进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            // 返回全部转为大写的32位MD5字符串
            return hexString.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sign(String appKey, String appSecret, Long timestamp, Map<String, Object> params) {
        params.put("app_key", appKey);
        params.put("timestamp", timestamp);
        String baseQueryString = mapToQueryString(params);
        return stringToMD5UpperCase(baseQueryString + appSecret);
    }

}