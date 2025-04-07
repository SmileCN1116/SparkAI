package com.example.demo.Config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class SparkConfig {

    @Value("${spark.hostUrl}")
    private String hostUrl;

    @Value("${spark.domain}")
    private String domain;

    @Value("${spark.appid}")
    private String appid;

    @Value("${spark.apiSecret}")
    private String apiSecret;

    @Value("${spark.apiKey}")
    private String apiKey;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // Getter方法
    public String getHostUrl() { return hostUrl; }
    public String getDomain() { return domain; }
    public String getAppid() { return appid; }
    public String getApiSecret() { return apiSecret; }
    public String getApiKey() { return apiKey; }
}