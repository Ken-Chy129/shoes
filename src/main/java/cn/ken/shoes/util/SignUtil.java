package cn.ken.shoes.util;

import lombok.extern.slf4j.Slf4j;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Description: 签名器
 * @Param:
 * @return:
 * @Author: fengping
 * @Date: 2020/5/26
 */
@Slf4j
public class SignUtil {

    public static String mapToQueryString(Map<String, Object> params) {
        // 过滤非空值，并将json数组转换为逗号分隔的字符串
        Map<String, String> filteredParams = params.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().toString().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (entry.getValue() instanceof List) {
                                List<?> list = (List<?>) entry.getValue();
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
                .collect(Collectors.toList());

        // 使用URL键值对格式拼接成字符串stringA
        StringBuilder queryStringBuilder = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : sortedEntries) {
                if (queryStringBuilder.length() > 0) {
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("b", "B");
        params.put("a", "A");
        params.put("c", "");
        params.put("d", Arrays.asList("D1", "D2", "D3"));
        params.put("e", null);
        params.put("f", "F");

        // 调用方法并打印结果
        System.out.println(mapToQueryString(params));
    }


    /**
     * 拼接参数md5加密，生成签名
     */
    public static String stringToMD5UpperCase(String input) {
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



}