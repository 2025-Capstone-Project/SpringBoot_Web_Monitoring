package com.example.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Controller
@RequestMapping
public class FanController {

    // 단순 데모/프런트 개발용 인메모리 상태
    private static class State {
        String mode = "AUTOMATIC"; // AUTOMATIC | MANUAL
        int setPwm = 15;            // 사용자가 설정한 PWM (자동 모드에선 제어 로직이 설정했다고 가정)
        int actualPwm = 12;         // 실제 팬 PWM (하드웨어 피드백)
        int cpuTemp = 48;
        int gpuTemp = 47;
        int modelCode = 1;          // 0: Normal, 1: Abnormal
    }

    private final AtomicReference<State> ref = new AtomicReference<>(new State());

    @GetMapping("/fan")
    public String dashboard() {
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
                "mode", s.mode
        );
    }

    @PostMapping("/api/fan/control")
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN') or (#body != null and #body['mode'] != 'MANUAL')")
    public ResponseEntity<Map<String, Object>> control(@RequestBody Map<String, Object> body) {
        State s = ref.get();
        Object modeObj = body.get("mode");
        if (modeObj instanceof String m) {
            String requested = m.equalsIgnoreCase("manual") ? "MANUAL" : "AUTOMATIC";
            // MANUAL은 관리자만 허용
            if ("MANUAL".equals(requested)) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ADMIN".equals(a.getAuthority()));
                if (!isAdmin) {
                    return ResponseEntity.status(403).body(Map.of(
                            "error", "MANUAL mode requires ADMIN",
                            "mode", s.mode,
                            "message", "관리자만 수동 제어가 가능합니다"
                    ));
                }
            }
            s.mode = requested;
        }
        if ("MANUAL".equals(s.mode)) {
            Object pwmObj = body.get("pwm");
            if (pwmObj instanceof Number n) {
                int v = Math.max(0, Math.min(100, n.intValue()));
                s.setPwm = v;
                s.actualPwm = Math.max(0, Math.min(100, (s.actualPwm + v) / 2));
            }
        }
        s.cpuTemp = Math.max(30, Math.min(90, s.cpuTemp + (int) Math.signum(50 - s.setPwm)));
        s.gpuTemp = Math.max(30, Math.min(90, s.gpuTemp + (int) Math.signum(50 - s.setPwm)));
        s.modelCode = (s.cpuTemp > 60 || s.gpuTemp > 60) ? 1 : 0;
        return ResponseEntity.ok(telemetry());
    }

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
    public ResponseEntity<Map<String, Object>> webControl(@RequestBody Map<String, Object> body) {
        // 동일한 권한 검사 로직을 활용하기 위해 내부로 위임
        return control(body);
    }
}
