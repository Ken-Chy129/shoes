package cn.ken.shoes.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Service
public class ConfigService {

    private static final String CONFIG_DIR = "config";
    private static final String RESOURCES_PATH = "src/main/resources";

    public Properties loadConfig(String configFileName) {
        Properties properties = new Properties();
        
        try {
            ClassPathResource resource = new ClassPathResource(CONFIG_DIR + "/" + configFileName);
            if (resource.exists()) {
                try (InputStream input = resource.getInputStream()) {
                    properties.load(input);
                }
            } else {
                System.out.println("Config file not found in classpath: " + configFileName + ", trying file system");
                Path configPath = getConfigPath(configFileName);
                if (Files.exists(configPath)) {
                    try (InputStream input = Files.newInputStream(configPath)) {
                        properties.load(input);
                    }
                } else {
                    System.out.println("Config file not found: " + configFileName + ", using default values");
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load config file: " + configFileName + ", error: " + e.getMessage());
        }
        
        return properties;
    }

    public void saveConfig(String configFileName, Properties properties) {
        Path configPath = getConfigPath(configFileName);
        
        try {
            Files.createDirectories(configPath.getParent());
            
            try (OutputStream output = Files.newOutputStream(configPath)) {
                properties.store(output, "Auto-generated configuration file");
            }
        } catch (IOException e) {
            System.err.println("Failed to save config file: " + configFileName + ", error: " + e.getMessage());
        }
    }

    private Path getConfigPath(String configFileName) {
        return Paths.get(RESOURCES_PATH, CONFIG_DIR, configFileName);
    }

    public String getProperty(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public Integer getIntProperty(Properties properties, String key, Integer defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer value for key: " + key + ", using default value");
            }
        }
        return defaultValue;
    }

    public Double getDoubleProperty(Properties properties, String key, Double defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid double value for key: " + key + ", using default value");
            }
        }
        return defaultValue;
    }

    public Boolean getBooleanProperty(Properties properties, String key, Boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}