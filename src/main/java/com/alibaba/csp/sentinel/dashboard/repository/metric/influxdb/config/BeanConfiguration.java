package com.alibaba.csp.sentinel.dashboard.repository.metric.influxdb.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class BeanConfiguration {

    private final static Logger logger = LoggerFactory.getLogger(BeanConfiguration.class);
    private final InfluxDBConfig config;

    public BeanConfiguration(InfluxDBConfig config) {
        this.config = config;
    }

    public OkHttpClient.Builder builder() {
        ConnectionPool connectionPool = new ConnectionPool(config.getMaxIdle(), config.getKeepAliveDuration(), TimeUnit.SECONDS);
        return new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeout(), TimeUnit.SECONDS)
                .connectionPool(connectionPool);
    }

    @Bean
    public InfluxDB influxDB() {
        InfluxDB influxDB = InfluxDBFactory.connect(config.getUrl(), config.getUser(), config.getPassword(), builder());
        try {
            influxDB.setDatabase(config.getDatabase())
                    .setRetentionPolicy(config.getRetentionPolicy())
                    .enableBatch(100, 2000, TimeUnit.MILLISECONDS)
                    .setLogLevel(InfluxDB.LogLevel.BASIC);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return influxDB;
    }
}
