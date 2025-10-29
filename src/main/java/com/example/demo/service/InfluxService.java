package com.example.demo.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InfluxService implements DisposableBean {
    private final InfluxDBClient client;
    private final String org;
    private final String bucket;

    public InfluxService(
            @Value("${influx.url:http://localhost:8086}") String url,
            @Value("${INFLUX_TOKEN}") String token,
            @Value("${influx.org:HANBAT}") String org,
            @Value("${influx.bucket:TEMPER}") String bucket
    ) {
        this.client = InfluxDBClientFactory.create(url, token != null ? token.toCharArray() : null, org, bucket);
        this.org = org;
        this.bucket = bucket;
    }

    /**
     * 최근 5분 내 thermal 측정의 마지막 값들을 키-값으로 반환합니다.
     * 예: {cpu_temperature: 55.3, gpu_temperature: 50.1, model_result: 1, setPwm: 35, actualPwm: 32}
     */
    public Map<String,Object> latestTemps() {
        String flux = "from(bucket: '"+bucket+"') \n" +
                "|> range(start: -5m) \n" +
                "|> filter(fn: (r) => r._measurement == 'thermal') \n" +
                "|> last()";
        QueryApi q = client.getQueryApi();
        List<FluxTable> tables = q.query(flux, org);
        Map<String,Object> res = new HashMap<>();
        for (FluxTable t : tables) {
            for (FluxRecord r : t.getRecords()) {
                String field = String.valueOf(r.getField());
                res.put(field, r.getValue());
            }
        }
        return res;
    }

    @Override
    public void destroy() {
        if (client != null) client.close();
    }
}

