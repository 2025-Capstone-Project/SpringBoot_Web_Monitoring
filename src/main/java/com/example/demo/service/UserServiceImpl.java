package com.example.demo.service;

import com.example.demo.dto.UserDto;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 사용자 서비스 구현 클래스
 * - 사용자 관련 비즈니스 로직을 구현합니다.
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 생성자 주입
    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 회원가입 처리
     * - 사용자 이름과 이메일 중복 검사
     * - 비밀번호 암호화 (실제 구현에서는 BCrypt 등 사용)
     * - 사용자 정보 저장
     */
    @Override
    @Transactional
    public UserDto.Response register(UserDto.RegisterRequest registerRequest) {
        // 사용자 이름 중복 검사
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new IllegalArgumentException("이미 사용 중인 사용자 이름입니다.");
        }

        // 이메일 중복 검사
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        // 실전 예제!!!
        if ("admin".equalsIgnoreCase(registerRequest.getUsername())) {
            throw new IllegalArgumentException("admin은 사용할 수 없는 사용자 이름입니다.");
        }

        // 비밀번호 암호화 (실제 구현에서는 BCrypt 등 사용)
        // 여기서는 간단히 처리하지만, 실제로는 보안을 위해 암호화 필요
        String encodedPassword = passwordEncoder.encode(registerRequest.getPassword());

        // 사용자 객체 생성
        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(encodedPassword);
        user.setName(registerRequest.getName());
        user.setEmail(registerRequest.getEmail());
        user.setRole("USER"); // 기본 역할 설정

        // 사용자 저장
        User savedUser = userRepository.save(user);

        // 응답 DTO 반환
        return new UserDto.Response(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getName(),
                savedUser.getEmail()
        );
    }

    /**
     * 로그인 처리
     * - 사용자 이름으로 사용자 조회
     * - 비밀번호 일치 여부 확인
     */
    @Override
    public Optional<User> login(UserDto.LoginRequest loginRequest) {
        // 사용자 이름으로 사용자 조회
        return userRepository.findByUsername(loginRequest.getUsername())
                .filter(user -> passwordEncoder.matches(loginRequest.getPassword(), user.getPassword()));
    }

    /**
     * 사용자 ID로 사용자 정보 조회
     */
    @Override
    public UserDto.Response getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        return new UserDto.Response(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getEmail()
        );
    }
}
