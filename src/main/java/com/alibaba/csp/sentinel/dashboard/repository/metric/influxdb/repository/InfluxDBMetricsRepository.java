package com.alibaba.csp.sentinel.dashboard.repository.metric.influxdb.repository;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.MetricEntity;
import com.alibaba.csp.sentinel.dashboard.repository.metric.MetricsRepository;
import com.alibaba.csp.sentinel.dashboard.repository.metric.influxdb.model.InfluxDBMetricEntity;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.google.common.collect.Lists;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBException;
import org.influxdb.annotation.Measurement;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.influxdb.impl.InfluxDBResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Primary
@Repository
public class InfluxDBMetricsRepository implements MetricsRepository<MetricEntity>, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(InfluxDBMetricsRepository.class);
    private final InfluxDB influxDB;
    private final String measurement;
    private final InfluxDBResultMapper resultMapper = new InfluxDBResultMapper();

    public InfluxDBMetricsRepository(InfluxDB influxDB) {
        this.influxDB = influxDB;
        this.measurement = AnnotationUtils.findAnnotation(InfluxDBMetricEntity.class, Measurement.class).name();
    }

    @Override
    public void save(MetricEntity metric) {
        if (Objects.isNull(metric) || StringUtil.isBlank(metric.getApp())) {
            return;
        }
        Optional.of(metric)
                .map(this::toInfluxDBMetricEntity)
                .ifPresent(it -> {
                    Point.Builder builder = Point.measurement(measurement);
                    builder.addFieldsFromPOJO(it);
                    influxDB.write(builder.build());
                    influxDB.flush();
                });
    }

    @Override
    public void saveAll(Iterable<MetricEntity> metrics) {
        if (Objects.isNull(metrics)) {
            return;
        }
        metrics.forEach(this::save);
    }

    @Override
    public List<MetricEntity> queryByAppAndResourceBetween(String app, String resource, long startTime, long endTime) {
        if (StringUtil.isBlank(app) || StringUtil.isBlank(resource)) {
            return Lists.newArrayList();
        }
        String sql = String.format("select * from %s where app='%s' and resource='%s' and gmtCreate >= %s and gmtCreate <= %s",
                this.measurement, app, resource, startTime, endTime);
        return findAllMetricEntities(sql);
    }

    private List<MetricEntity> findAllMetricEntities(String sql) {
        QueryResult result = influxDB.query(new Query(sql));
        return this.resultMapper.toPOJO(result, InfluxDBMetricEntity.class, this.measurement).stream()
                .map(this::toMetricEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listResourcesOfApp(String app) {
        long period = System.currentTimeMillis() - 1000 * 60 * 15;
        String sql = String.format("select * from %s where app = '%s' and gmtCreate > %s", this.measurement, app, period);
        List<MetricEntity> metricEntities = findAllMetricEntities(sql);
        if (CollectionUtils.isEmpty(metricEntities)) {
            return Lists.newArrayList();
        }
        Map<String, MetricEntity> resourceCount = new HashMap<>(32);
        for (MetricEntity metricEntity : metricEntities) {
            String resource = metricEntity.getResource();
            if (resourceCount.containsKey(resource)) {
                MetricEntity oldEntity = resourceCount.get(resource);
                oldEntity.addPassQps(metricEntity.getPassQps());
                oldEntity.addRtAndSuccessQps(metricEntity.getRt(), metricEntity.getSuccessQps());
                oldEntity.addBlockQps(metricEntity.getBlockQps());
                oldEntity.addExceptionQps(metricEntity.getExceptionQps());
                oldEntity.addCount(1);
            } else {
                resourceCount.put(resource, MetricEntity.copyOf(metricEntity));
            }
        }
        return resourceCount.entrySet()
                .stream()
                .sorted((o1, o2) -> {
                    MetricEntity e1 = o1.getValue();
                    MetricEntity e2 = o2.getValue();
                    int t = e2.getBlockQps().compareTo(e1.getBlockQps());
                    if (t != 0) {
                        return t;
                    }
                    return e2.getPassQps().compareTo(e1.getPassQps());
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        QueryResult rs = this.influxDB.query(new Query("show databases"));
        log.debug("influxDB database check => {}", rs);
        if (rs.hasError()) {
            throw new InfluxDBException(rs.getError());
        }
    }

    private InfluxDBMetricEntity toInfluxDBMetricEntity(MetricEntity metricEntity) {
        InfluxDBMetricEntity influxDBMetricEntity = new InfluxDBMetricEntity();
        BeanUtils.copyProperties(metricEntity, influxDBMetricEntity);
        return influxDBMetricEntity;
    }

    private MetricEntity toMetricEntity(InfluxDBMetricEntity influxDBMetricEntity) {
        MetricEntity metricEntity = new MetricEntity();
        BeanUtils.copyProperties(influxDBMetricEntity, metricEntity);
        return metricEntity;
    }
}
