package com.example.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
@RequestMapping
public class FanController {

    private static final Logger log = LoggerFactory.getLogger(FanController.class);

    // 단순 데모/프런트 개발용 인메모리 상태
    private static class State {
        String mode = "AUTOMATIC"; // AUTOMATIC | MANUAL
        int setPwm = 15;            // 지정 PWM
        int actualPwm = 12;         // 실제 PWM
        int cpuTemp = 48;
        int gpuTemp = 47;
        int modelCode = 1;          // 0: Normal, 1: Abnormal
        int cpuThreshold = 60;      // 비정상 판단 임계(자동 모드 기준)
        int gpuThreshold = 60;      // 비정상 판단 임계(자동 모드 기준)
    }

    private final AtomicReference<State> ref = new AtomicReference<>(new State());

    @GetMapping("/fan")
    public String dashboard(Model model) {
        State s = ref.get();
        model.addAttribute("currentMode", s.mode);
        model.addAttribute("currentSetPwm", s.setPwm);
        model.addAttribute("cpuThreshold", s.cpuThreshold);
        model.addAttribute("gpuThreshold", s.gpuThreshold);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
        model.addAttribute("isAdmin", isAdmin);
        return "fan/dashboard";
    }

    @GetMapping("/api/fan/telemetry")
    @ResponseBody
    public Map<String, Object> telemetry() {
        State s = ref.get();
        return Map.of(
                "timestamp", Instant.now().toString(),
                "cpuTemp", s.cpuTemp,
                "gpuTemp", s.gpuTemp,
                "model", Map.of(
                        "code", s.modelCode,
                        "label", s.modelCode == 0 ? "Normal" : "Abnormal"
                ),
                "setPwm", s.setPwm,
                "actualPwm", s.actualPwm,
                "mode", s.mode,
                "cpuThreshold", s.cpuThreshold,
                "gpuThreshold", s.gpuThreshold
        );
    }

    @PostMapping("/api/fan/control")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> control(@RequestBody Map<String, Object> body) {
        State s = ref.get();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null ? String.valueOf(auth.getName()) : "anonymous";
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));

        String dimension = String.valueOf(body.getOrDefault("dimension", "SPEED")).toUpperCase();
        log.info("/api/fan/control user={}, isAdmin={}, dim={}, body={} (mode={})", user, isAdmin, dimension, body, s.mode);

        if ("TEMP".equals(dimension)) {
            if (!isAdmin) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "TEMP threshold requires ADMIN",
                        "mode", s.mode,
                        "cpuThreshold", s.cpuThreshold,
                        "gpuThreshold", s.gpuThreshold
                ));
            }
            Object cObj = body.get("cpuThreshold");
            Object gObj = body.get("gpuThreshold");
            if (cObj instanceof Number cn) s.cpuThreshold = clamp(cn.intValue(), 30, 100);
            if (gObj instanceof Number gn) s.gpuThreshold = clamp(gn.intValue(), 30, 100);
        } else {
            // SPEED
            Object modeObj = body.get("mode");
            if (modeObj instanceof String m) {
                String requested = m.equalsIgnoreCase("manual") ? "MANUAL" : "AUTOMATIC";
                if ("MANUAL".equals(requested) && !isAdmin) {
                    log.warn("Manual request denied for user={}", user);
                    return ResponseEntity.status(403).body(Map.of(
                            "error", "MANUAL mode requires ADMIN",
                            "mode", s.mode,
                            "message", "관리자만 수동 제어가 가능합니다"
                    ));
                }
                s.mode = requested;
                log.info("Mode changed to {} by user={}", s.mode, user);
            }
            if ("MANUAL".equals(s.mode)) {
                Object pwmObj = body.get("pwm");
                if (pwmObj instanceof Number n) {
                    int v = clamp(n.intValue(), 0, 100);
                    s.setPwm = v;
                    s.actualPwm = clamp((s.actualPwm + v) / 2, 0, 100);
                    log.info("PWM set to {} by user={}", s.setPwm, user);
                }
            }
        }
        // 단순 시뮬: 온도/모델 업데이트
        s.cpuTemp = clamp(s.cpuTemp + (int) Math.signum(50 - s.setPwm), 30, 90);
        s.gpuTemp = clamp(s.gpuTemp + (int) Math.signum(50 - s.setPwm), 30, 90);
        s.modelCode = (s.cpuTemp > s.cpuThreshold || s.gpuTemp > s.gpuThreshold) ? 1 : 0;
        return ResponseEntity.ok(telemetry());
    }

    private int clamp(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }

    @GetMapping(value = "/api/fan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        new Thread(() -> {
            try {
                while (true) {
                    Map<String, Object> data = telemetry();
                    emitter.send(SseEmitter.event().name("telemetry").data(data));
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }, "telemetry-sse").start();
        return emitter;
    }

    // 세션 기반 WEB 엔드포인트
    @GetMapping("/web/fan/telemetry")
    @ResponseBody
    public Map<String, Object> webTelemetry() { return telemetry(); }

    @GetMapping(value = "/web/fan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter webStream() { return stream(); }

    @PostMapping("/web/fan/control")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> webControl(@RequestBody Map<String, Object> body) {
        // 동일한 권한 검사 로직을 활용하기 위해 내부로 위임
        return control(body);
    }
}
