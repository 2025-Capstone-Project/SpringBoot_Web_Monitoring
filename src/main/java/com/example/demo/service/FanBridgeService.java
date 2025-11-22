package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FanBridgeService {

    private static final Logger log = LoggerFactory.getLogger(FanBridgeService.class);

    private final String wsUrl;
    private final long retryMillis;
    private final long pingIntervalMillis;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fan-bridge");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> lastTelemetry = new AtomicReference<>(Map.of());
    private final AtomicReference<String> lastMode = new AtomicReference<>("AUTOMATIC");
    private final AtomicReference<Integer> lastCpuTh = new AtomicReference<>(60);
    private final AtomicReference<Integer> lastGpuTh = new AtomicReference<>(60);
    private final AtomicReference<Integer> lastManualPwm = new AtomicReference<>(0);

    private final ObjectMapper mapper = new ObjectMapper();
    private final InfluxService influxService;

    public FanBridgeService(@Value("${fan.bridge.wsUrl}") String wsUrl,
                            @Value("${fan.bridge.connectRetryMillis:3000}") long retryMillis,
                            @Value("${fan.bridge.pingIntervalMillis:1000}") long pingIntervalMillis,
                            @Autowired(required = false) InfluxService influxService) {
        this.wsUrl = wsUrl;
        this.retryMillis = retryMillis;
        this.pingIntervalMillis = pingIntervalMillis;
        this.influxService = influxService;
    }

    @PostConstruct
    public void start() {
        scheduler.schedule(this::ensureConnected, 0, TimeUnit.MILLISECONDS);
        // 주기적 ping 또는 텔레메트리 요청이 필요하다면 사용
        scheduler.scheduleAtFixedRate(() -> {
            try {
                WebSocket ws = socketRef.get();
                if (ws != null) {
                    ws.sendPing(ByteBuffer.wrap(new byte[]{1}));
                }
            } catch (Throwable e) {
                log.debug("ping fail: {}", e.toString());
            }
        }, pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        try { Optional.ofNullable(socketRef.get()).ifPresent(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")); } catch (Exception ignore) {}
        scheduler.shutdownNow();
    }

    private void ensureConnected() {
        try {
            if (socketRef.get() != null) return;
            log.info("[bridge] connecting to {}", wsUrl);
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> cf = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(wsUrl), new Listener());
            cf.whenComplete((ws, err) -> {
                if (err != null) {
                    log.warn("[bridge] connect fail: {}", err.toString());
                    socketRef.set(null);
                    scheduler.schedule(this::ensureConnected, retryMillis, TimeUnit.MILLISECONDS);
                } else {
                    log.info("[bridge] connected");
                    socketRef.set(ws);
                }
            });
        } catch (Throwable t) {
            log.warn("[bridge] connect error: {}", t.toString());
            socketRef.set(null);
            scheduler.schedule(this::ensureConnected, retryMillis, TimeUnit.MILLISECONDS);
        }
    }

    public Map<String, Object> getLastTelemetry() {
        return lastTelemetry.get();
    }

    public Map<String, Object> getUiTelemetry() {
        Map<String, Object> ext = lastTelemetry.get();
        Map<String, Object> influx = Map.of();
        try {
            if (influxService != null) influx = influxService.latestTemps();
        } catch (Exception e) { log.debug("influx read fail: {}", e.toString()); }

        String mode = normalizeMode(lastMode.get());
        int cpuTemp = toInt(ext.getOrDefault("cpu_temp", ext.getOrDefault("cpuTemp", influx.getOrDefault("cpuTemp", 0))));
        int gpuTemp = toInt(ext.getOrDefault("gpu_temp", ext.getOrDefault("gpuTemp", influx.getOrDefault("gpuTemp", 0))));
        int pwm = toInt(ext.getOrDefault("pwm", ext.getOrDefault("setPwm", lastManualPwm.get())));
        int code = toInt(ext.getOrDefault("model_result", influx.getOrDefault("model_result", -1)));
        String label = code < 0 ? "Unknown" : (code == 0 ? "Normal" : "Abnormal");
        int cpuTh = Optional.ofNullable(lastCpuTh.get()).orElse(60);
        int gpuTh = Optional.ofNullable(lastGpuTh.get()).orElse(60);
        return Map.of(
                "timestamp", java.time.Instant.now().toString(),
                "cpuTemp", cpuTemp,
                "gpuTemp", gpuTemp,
                "model", Map.of("code", code < 0 ? 0 : code, "label", label),
                "setPwm", mode.equals("MANUAL") ? lastManualPwm.get() : pwm,
                "actualPwm", pwm,
                "mode", mode,
                "cpuThreshold", cpuTh,
                "gpuThreshold", gpuTh
        );
    }

    private String normalizeMode(String m){ if (m==null) return "AUTOMATIC"; m=m.toUpperCase(); return switch(m){ case "MANUAL","RANGE" -> "MANUAL"; default -> "AUTOMATIC"; }; }
    private int toInt(Object o){ if(o instanceof Number n) return n.intValue(); try{ return Integer.parseInt(String.valueOf(o)); }catch(Exception e){ return 0; } }

    public CompletableFuture<Void> sendControl(Map<String, Object> payload) {
        // 상태 추적 업데이트
        Object m = payload.get("mode");
        if (m instanceof String sm) { lastMode.set(normalizeMode(sm)); }
        if (payload.containsKey("cpu_threshold")) lastCpuTh.set(toInt(payload.get("cpu_threshold")));
        if (payload.containsKey("gpu_threshold")) lastGpuTh.set(toInt(payload.get("gpu_threshold")));
        if (payload.containsKey("manual_pwm")) lastManualPwm.set(toInt(payload.get("manual_pwm")));

        WebSocket ws = socketRef.get();
        if (ws == null) {
            ensureConnected();
            return CompletableFuture.failedFuture(new IllegalStateException("bridge not connected"));
        }
        try {
            String json = mapper.writeValueAsString(payload);
            log.info("[bridge] send: {}", json);
            return ws.sendText(json, true).thenAccept(seq -> {});
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Apply local control state optimistically without sending to the external bridge.
     * This updates lastMode/lastManualPwm/thresholds so getUiTelemetry() reflects the
     * requested state immediately (useful for optimistic UI updates and page refreshes).
     */
    public void applyLocalControl(Map<String, Object> payload) {
        Object m = payload.get("mode");
        if (m instanceof String sm) { lastMode.set(normalizeMode(sm)); }
        if (payload.containsKey("cpu_threshold")) lastCpuTh.set(toInt(payload.get("cpu_threshold")));
        if (payload.containsKey("gpu_threshold")) lastGpuTh.set(toInt(payload.get("gpu_threshold")));
        if (payload.containsKey("manual_pwm")) lastManualPwm.set(toInt(payload.get("manual_pwm")));
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    Map<String, Object> parsed = mapper.readValue(msg, new TypeReference<Map<String, Object>>(){});
                    if (parsed != null) { lastTelemetry.set(parsed); }
                } catch (Exception e) {
                    log.warn("[bridge] parse fail: {} | payload={}", e.toString(), msg);
                }
            }
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socketRef.compareAndSet(webSocket, null);
            scheduler.schedule(FanBridgeService.this::ensureConnected, retryMillis, TimeUnit.MILLISECONDS);
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[bridge] ws error: {}", Objects.toString(error));
            socketRef.compareAndSet(webSocket, null);
            scheduler.schedule(FanBridgeService.this::ensureConnected, retryMillis, TimeUnit.MILLISECONDS);
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) { webSocket.request(1); return null; }
        @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) { webSocket.request(1); return null; }
        @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) { webSocket.request(1); return null; }
    }
}
