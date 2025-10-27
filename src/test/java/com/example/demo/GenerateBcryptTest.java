package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class GenerateBcryptTest {

    @Test
    void generateHashes() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        for (int i = 0; i < 3; i++) {
            System.out.println("HASH pass123 -> " + encoder.encode("pass123"));
        }
    }
}

