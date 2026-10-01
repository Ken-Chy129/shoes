package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayPictureApiClient;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class EbayPictureService {

    private static final int MAX_IMAGES = 12;
    /** 分辨率不足的图片缓存为空串，避免重复上传。 */
    private static final String UNDERSIZED = "";
    private final EbayPictureApiClient apiClient;
    /**
     * 原图地址 -> eBay托管地址。UploadSiteHostedPictures 每天只有 5000 次额度，
     * 同一张图在重试或多个任务里反复上传会很快把额度耗尽。未挂到 listing 的
     * EPS 图片 30 天后会被清理，这里只缓存 7 天以留足余量。
     */
    private final Cache<String, String> hostedCache = CacheBuilder.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(7, TimeUnit.DAYS)
            .build();

    public EbayPictureService(EbayPictureApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public List<String> hostImages(List<String> imageUrls, String pictureNamePrefix) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个图片链接(image URL)");
        }
        if (imageUrls.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("图片链接最多支持12个");
        }
        List<String> uniqueUrls = new ArrayList<>(new LinkedHashSet<>(imageUrls));
        List<String> hostedUrls = new ArrayList<>(uniqueUrls.size());
        for (int i = 0; i < uniqueUrls.size(); i++) {
            String sourceUrl = validateImageUrl(uniqueUrls.get(i));
            if (isEbayHosted(sourceUrl)) {
                hostedUrls.add(sourceUrl);
                continue;
            }
            String cached = hostedCache.getIfPresent(sourceUrl);
            if (cached == null) {
                Optional<String> hosted = apiClient.uploadExternalPicture(
                        sourceUrl, pictureName(pictureNamePrefix, i + 1));
                if (hosted.isPresent()
                        && !isEbayHosted(validateImageUrl(hosted.get()))) {
                    throw new EbayApiException("eBay图片托管返回了无效地址");
                }
                cached = hosted.orElse(UNDERSIZED);
                hostedCache.put(sourceUrl, cached);
            }
            if (UNDERSIZED.equals(cached)) {
                continue;
            }
            hostedUrls.add(cached);
        }
        if (hostedUrls.isEmpty()) {
            throw new IllegalArgumentException(
                    "图片分辨率不足，至少一张图片的最长边需达到500像素");
        }
        return List.copyOf(hostedUrls);
    }

    /** 判断这组地址是否全部已是 eBay 托管图片（可直接复用，无需再上传）。 */
    public boolean allEbayHosted(List<String> imageUrls) {
        return imageUrls != null && !imageUrls.isEmpty()
                && imageUrls.stream().allMatch(url -> url != null && isEbayHosted(url));
    }

    private String validateImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank() || imageUrl.length() > 2_000) {
            throw new IllegalArgumentException("图片链接(image URL)无效");
        }
        try {
            URI uri = new URI(imageUrl.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("图片链接(image URL)必须使用HTTPS");
            }
            if (host == null || uri.getUserInfo() != null || isDisallowedHost(host)) {
                throw new IllegalArgumentException("图片链接(image URL)必须是公开地址");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("图片链接(image URL)无效", e);
        }
    }

    private boolean isEbayHosted(String imageUrl) {
        try {
            URI uri = new URI(imageUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "i.ebayimg.com".equalsIgnoreCase(uri.getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private boolean isDisallowedHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT)
                .replace("[", "").replace("]", "");
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.contains(":")) {
            return true;
        }
        if (!host.matches("[0-9.]+")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return true;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 0 || first == 10 || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private String pictureName(String rawPrefix, int index) {
        String prefix = rawPrefix == null ? "EBAY" : rawPrefix
                .replaceAll("[^A-Za-z0-9_-]", "-");
        prefix = prefix.length() <= 120 ? prefix : prefix.substring(0, 120);
        return prefix + "-" + index;
    }
}
