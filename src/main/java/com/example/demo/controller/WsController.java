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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collection;
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
    public void onControl(@Payload Map<String, Object> body, java.security.Principal principal, org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {
        Authentication auth = null;
        if (principal instanceof Authentication a) auth = a;
        if (auth == null) auth = SecurityContextHolder.getContext().getAuthentication();
        String who = principal == null ? "anonymous" : principal.getName();
        boolean isAdmin = auth != null && auth.getAuthorities() != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
        // fallback: WebSocket 세션의 sessionAttributes에 저장된 'role'을 검사
        try {
            if (headerAccessor != null) {
                var attrs = headerAccessor.getSessionAttributes();
                log.info("[ws] sessionAttributes keys={}", attrs==null?null:attrs.keySet());
                if (!isAdmin && attrs != null) {
                    Object r = attrs.get("role");
                    if (r != null && "ADMIN".equals(String.valueOf(r))) {
                        isAdmin = true;
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        log.info("[ws] onControl from {} (auth==null:{} ) admin={} payload={}", who, auth==null, isAdmin, body);
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
        // 서버 내부 상태를 낙관적으로 먼저 업데이트하여 새로고침 시에도 변경이 유지되도록 함
        try {
            bridge.applyLocalControl(payload);
        } catch (Exception e) {
            log.debug("[ws] applyLocalControl failed: {}", e.toString());
        }
         // 낙관적 업데이트: 브리지로 전송하기 전에 클라이언트가 즉시 변경을 볼 수 있도록 현재 스냅샷을 복제하여 수정한 값을 푸시한다.
         try {
             Map<String, Object> snap = bridge.getUiTelemetry();
             Map<String, Object> optimistic = new HashMap<>(snap);
             String pmode = String.valueOf(payload.getOrDefault("mode", "auto")).toUpperCase();
             if ("MANUAL".equalsIgnoreCase(pmode) || "MANUAL".equalsIgnoreCase((String)payload.get("mode"))) {
                 optimistic.put("mode", "MANUAL");
                 if (payload.containsKey("manual_pwm")) optimistic.put("setPwm", toIntSafe(payload.get("manual_pwm")));
             } else if ("range".equalsIgnoreCase(String.valueOf(payload.get("mode")))) {
                 optimistic.put("mode", "MANUAL");
                 if (payload.containsKey("cpu_threshold")) optimistic.put("cpuThreshold", toIntSafe(payload.get("cpu_threshold")));
                 if (payload.containsKey("gpu_threshold")) optimistic.put("gpuThreshold", toIntSafe(payload.get("gpu_threshold")));
             } else {
                 optimistic.put("mode", "AUTOMATIC");
             }
             broker.convertAndSend("/topic/telemetry", optimistic);
         } catch (Exception e) {
             log.debug("[ws] optimistic push failed: {}", e.toString());
         }
        bridge.sendControl(payload).whenComplete((ok, err) -> {
            if (err != null) {
                log.warn("[ws] bridge.sendControl failed: {}", err.toString());
                // 브리지 연결 실패시에도 현재 서버가 알고 있는 스냅샷을 클라이언트에게 푸시하여
                // 사용자가 Apply 후 즉시 변경 상태를 볼 수 있도록 한다.
                try {
                    Map<String, Object> snap = bridge.getUiTelemetry();
                    Map<String, Object> out = new HashMap<>(snap);
                    out.put("error", "bridge not connected");
                    broker.convertAndSend("/topic/telemetry", out);
                } catch (Exception e2) {
                    broker.convertAndSend("/topic/telemetry", Map.of("error","bridge not connected"));
                }
            } else {
                Map<String, Object> t = bridge.getUiTelemetry();
                if (!t.isEmpty()) broker.convertAndSend("/topic/telemetry", t);
            }
        });
    }

    // REST fallback: 브라우저가 STOMP 연결에 인증 문제를 겪을 때 POST 방식으로도 제어를 허용합니다.
    @org.springframework.web.bind.annotation.PostMapping("/api/control")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> controlViaHttp(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
                                              java.security.Principal principal,
                                              jakarta.servlet.http.HttpSession session) {
        Authentication auth = null;
        if (principal instanceof Authentication a) auth = a;
        if (auth == null) auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities() != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
        // fallback: 세션 속성에서 role 검사
        try {
            if (!isAdmin && session != null) {
                Object r = session.getAttribute("role");
                if (r != null && "ADMIN".equals(String.valueOf(r))) isAdmin = true;
            }
        } catch (Exception ignored) {}

        String dim = String.valueOf(body.getOrDefault("dimension","SPEED")).toUpperCase();
        Map<String, Object> payload = new HashMap<>();
        if ("TEMP".equals(dim)) {
            if (!isAdmin) return Map.of("ok", false, "error", "TEMP threshold requires ADMIN");
            payload.put("cpu_threshold", body.getOrDefault("cpuThreshold", 60));
            payload.put("gpu_threshold", body.getOrDefault("gpuThreshold", 60));
            payload.put("mode", "range");
        } else {
            String reqMode = String.valueOf(body.getOrDefault("mode","AUTOMATIC"));
            if ("MANUAL".equalsIgnoreCase(reqMode) && !isAdmin) return Map.of("ok", false, "error", "MANUAL mode requires ADMIN");
            if ("MANUAL".equalsIgnoreCase(reqMode)) { payload.put("mode", "manual"); payload.put("manual_pwm", body.getOrDefault("pwm", 0)); }
            else { payload.put("mode", "auto"); }
        }

        log.info("[http-control] from {} admin={} payload={}", principal==null?"anonymous":principal.getName(), isAdmin, payload);
        try { bridge.applyLocalControl(payload); } catch (Exception e) { log.debug("applyLocalControl failed: {}", e.toString()); }
        // optimistic push
        try {
            Map<String, Object> snap = bridge.getUiTelemetry();
            Map<String, Object> optimistic = new HashMap<>(snap);
            String pmode = String.valueOf(payload.getOrDefault("mode", "auto")).toUpperCase();
            if ("MANUAL".equalsIgnoreCase(pmode) || "MANUAL".equalsIgnoreCase((String)payload.get("mode"))) {
                optimistic.put("mode", "MANUAL");
                if (payload.containsKey("manual_pwm")) optimistic.put("setPwm", toIntSafe(payload.get("manual_pwm")));
            } else if ("range".equalsIgnoreCase(String.valueOf(payload.get("mode")))) {
                optimistic.put("mode", "MANUAL");
                if (payload.containsKey("cpu_threshold")) optimistic.put("cpuThreshold", toIntSafe(payload.get("cpu_threshold")));
                if (payload.containsKey("gpu_threshold")) optimistic.put("gpuThreshold", toIntSafe(payload.get("gpu_threshold")));
            } else {
                optimistic.put("mode", "AUTOMATIC");
            }
            broker.convertAndSend("/topic/telemetry", optimistic);
        } catch (Exception e) { log.debug("optimistic push failed: {}", e.toString()); }

        bridge.sendControl(payload).whenComplete((ok, err) -> {
            if (err != null) {
                log.warn("[http-control] bridge.sendControl failed: {}", err.toString());
                try { Map<String, Object> snap = bridge.getUiTelemetry(); Map<String, Object> out = new HashMap<>(snap); out.put("error","bridge not connected"); broker.convertAndSend("/topic/telemetry", out);}catch(Exception e){ broker.convertAndSend("/topic/telemetry", Map.of("error","bridge not connected")); }
            } else {
                Map<String, Object> t = bridge.getUiTelemetry(); if (!t.isEmpty()) broker.convertAndSend("/topic/telemetry", t);
            }
        });

        return Map.of("ok", true);
    }

    @Scheduled(fixedDelay = 1000)
    public void pushTelemetry() {
        Map<String, Object> t = bridge.getUiTelemetry();
        if (!t.isEmpty()) broker.convertAndSend("/topic/telemetry", t);
    }

    @GetMapping("/fan")
    public String fanDashboard(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        boolean isAdmin = false;
        String username = null;
        if (isAuthenticated) {
            username = auth.getName();
            Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
            if (authorities != null) {
                isAdmin = authorities.stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
            }
        }

        // 뷰에서 사용될 기본값들
        model.addAttribute("isAuthenticated", isAuthenticated);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("username", username == null ? "" : username);

        // 실제 브리지에서 UI용 스냅샷을 가져와 초기값으로 제공
        try {
            Map<String, Object> snap = bridge.getUiTelemetry();
            String mode = snap.getOrDefault("mode", "AUTOMATIC").toString().toUpperCase();
            Integer setPwm = (snap.containsKey("setPwm") && snap.get("setPwm") instanceof Number) ? ((Number)snap.get("setPwm")).intValue() : 50;
            Integer cpuTh = (snap.containsKey("cpuThreshold") && snap.get("cpuThreshold") instanceof Number) ? ((Number)snap.get("cpuThreshold")).intValue() : 80;
            Integer gpuTh = (snap.containsKey("gpuThreshold") && snap.get("gpuThreshold") instanceof Number) ? ((Number)snap.get("gpuThreshold")).intValue() : 80;

            model.addAttribute("currentMode", mode);
            model.addAttribute("currentSetPwm", setPwm);
            model.addAttribute("cpuThreshold", cpuTh);
            model.addAttribute("gpuThreshold", gpuTh);
        } catch (Exception e) {
            model.addAttribute("currentMode", "AUTOMATIC");
            model.addAttribute("currentSetPwm", 50);
            model.addAttribute("cpuThreshold", 80);
            model.addAttribute("gpuThreshold", 80);
        }

        return "fan/dashboard";
    }

    // 유틸: Object -> int 안전 변환 (컨트롤러 내부용)
    private int toIntSafe(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number)o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
