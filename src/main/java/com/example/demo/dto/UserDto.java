package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 사용자 DTO 클래스
 * - 클라이언트와 서버 간의 데이터 전송에 사용됩니다.
 * - 입력 유효성 검사를 위한 어노테이션이 포함되어 있습니다.
 */
public class UserDto {

    // 회원가입 요청 DTO
    public static class RegisterRequest {
        @NotBlank(message = "사용자 이름은 필수입니다")
        @Size(min = 4, max = 50, message = "사용자 이름은 4~50자 사이여야 합니다")
        private String username;

        @NotBlank(message = "비밀번호는 필수입니다")
        @Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다")
        private String password;

        @NotBlank(message = "이름은 필수입니다")
        private String name;

        @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "유효한 이메일 형식이 아닙니다")
        private String email;

        // Getter와 Setter
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    // 로그인 요청 DTO
    public static class LoginRequest {
        @NotBlank(message = "사용자 이름은 필수입니다")
        private String username;

        @NotBlank(message = "비밀번호는 필수입니다")
        private String password;

        // Getter와 Setter
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    // 사용자 응답 DTO (민감한 정보 제외)
    public static class Response {
        private Long id;
        private String username;
        private String name;
        private String email;

        public Response() {
        }

        public Response(Long id, String username, String name, String email) {
            this.id = id;
            this.username = username;
            this.name = name;
            this.email = email;
        }

        // Getter와 Setter
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    // JWT 인증 응답 DTO
    public static class AuthResponse {
        private Long id;
        private String username;
        private String name;
        private String email;
        private String token;
        private String role;

        public AuthResponse() {}

        public AuthResponse(Long id, String username, String name, String email, String role, String token) {
            this.id = id;
            this.username = username;
            this.name = name;
            this.email = email;
            this.role = role;
            this.token = token;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }
}
