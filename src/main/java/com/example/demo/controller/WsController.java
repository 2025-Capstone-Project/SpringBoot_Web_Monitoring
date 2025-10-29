package com.example.demo.controller;

import com.example.demo.service.FanBridgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

@Controller
@EnableScheduling
public class WsController {

    private static final Logger log = LoggerFactory.getLogger(WsController.class);

    private final SimpMessagingTemplate broker;
    private final FanBridgeService bridge;

    public WsController(SimpMessagingTemplate broker, FanBridgeService bridge) {
        this.broker = broker;
        this.bridge = bridge;
    }

    @SubscribeMapping("/topic/telemetry")
    public Map<String, Object> onSubscribeTelemetry() {
        Map<String, Object> t = bridge.getUiTelemetry();
        return t.isEmpty() ? Map.of("status","no-data") : t;
    }

    @MessageMapping("/control")
    public void onControl(@Payload Map<String, Object> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
        String dim = String.valueOf(body.getOrDefault("dimension","SPEED")).toUpperCase();
        Map<String, Object> payload = new HashMap<>();
        if ("TEMP".equals(dim)) {
            if (!isAdmin) { broker.convertAndSend("/topic/telemetry", Map.of("error","TEMP threshold requires ADMIN")); return; }
            payload.put("cpu_threshold", body.getOrDefault("cpuThreshold", 60));
            payload.put("gpu_threshold", body.getOrDefault("gpuThreshold", 60));
            payload.put("mode", "range");
        } else {
            String reqMode = String.valueOf(body.getOrDefault("mode","AUTOMATIC"));
            if ("MANUAL".equalsIgnoreCase(reqMode) && !isAdmin) { broker.convertAndSend("/topic/telemetry", Map.of("error","MANUAL mode requires ADMIN")); return; }
            if ("MANUAL".equalsIgnoreCase(reqMode)) { payload.put("mode", "manual"); payload.put("manual_pwm", body.getOrDefault("pwm", 0)); }
            else { payload.put("mode", "auto"); }
        }
        log.info("[ws] forward to python: {}", payload);
        bridge.sendControl(payload).whenComplete((ok, err) -> {
            if (err != null) broker.convertAndSend("/topic/telemetry", Map.of("error","bridge not connected"));
            else {
                Map<String, Object> t = bridge.getUiTelemetry();
                if (!t.isEmpty()) broker.convertAndSend("/topic/telemetry", t);
            }
        });
    }

    @Scheduled(fixedDelay = 1000)
    public void pushTelemetry() {
        Map<String, Object> t = bridge.getUiTelemetry();
        if (!t.isEmpty()) broker.convertAndSend("/topic/telemetry", t);
    }
}
