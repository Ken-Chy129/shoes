package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class StockXAccount {

    private String id;

    private String name;

    private String apiKey;

    private String authorization;

    private boolean enabled;

    private long standardInterval = 1800;

    private long custodialInterval = 1800;
}
