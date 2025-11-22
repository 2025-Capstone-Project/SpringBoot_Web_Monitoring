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
    private final boolean enabled;

    public InfluxService(
            @Value("${influx.url:http://localhost:8086}") String url,
            @Value("${influx.token:${INFLUX_TOKEN:}}") String token,
            @Value("${influx.org:HANBAT}") String org,
            @Value("${influx.bucket:TEMPER}") String bucket
    ) {
        this.org = org;
        this.bucket = bucket;
        boolean ok = token != null && !token.isBlank() && url != null && !url.isBlank();
        InfluxDBClient c = null;
        if (ok) {
            try {
                c = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
            } catch (Throwable t) {
                // Influx 접근 실패 시 안전하게 비활성화
                c = null;
            }
        }
        this.client = c;
        this.enabled = c != null;
    }

    /**
     * 최근 측정의 마지막 값들을 키-값으로 반환합니다.
     * Influx가 비활성화되어 있으면 빈 맵을 반환합니다.
     */
    public Map<String,Object> latestTemps() {
        if (!enabled || client == null) return Map.of();
        String flux =
            "from(bucket: \"" + bucket + "\")\n" +
            "  |> range(start: -7d)\n" +
            "  |> filter(fn: (r) => r._measurement == \"cpu_temperature\" or\n" +
            "                       r._measurement == \"gpu_temperature\" or\n" +
            "                       r._measurement == \"model_result\")\n" +
            "  |> filter(fn: (r) => r._field == \"value\")\n" +
            "  |> group(columns: [\"_measurement\"])\n" +
            "  |> sort(columns: [\"_time\"], desc: true)\n" +
            "  |> limit(n: 1)";
        try {
            QueryApi q = client.getQueryApi();
            List<FluxTable> tables = q.query(flux, org);
            Map<String,Object> res = new HashMap<>();
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    String measurement = r.getMeasurement();
                    Object value = r.getValue();
                    if (measurement == null) continue;
                    switch (measurement) {
                        case "cpu_temperature" -> res.put("cpuTemp", value);
                        case "gpu_temperature" -> res.put("gpuTemp", value);
                        case "model_result" -> res.put("model_result", value);
                        default -> res.put(measurement, value);
                    }
                }
            }
            return res;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    @Override
    public void destroy() {
        if (client != null) client.close();
    }
}
