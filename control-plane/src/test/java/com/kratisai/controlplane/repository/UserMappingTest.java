package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class UserMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    void testUserEntityMapping() {
        // Create and save a user
        User user = new User("test@example.com", "hashed-password", "Test User");
        userRepository.save(user);
        entityManager.flush();

        // Verify save
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isNotNull();

        // Verify find
        User found = userRepository.findByEmail("test@example.com").orElseThrow();
        assertThat(found.getEmail()).isEqualTo("test@example.com");
        assertThat(found.getDisplayName()).isEqualTo("Test User");
        assertThat(found.getPasswordHash()).isEqualTo("hashed-password");

        // Verify existsByEmail
        assertThat(userRepository.existsByEmail("test@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nonexistent@example.com")).isFalse();

        // Verify delete
        userRepository.delete(found);
        entityManager.flush();
        assertThat(userRepository.findByEmail("test@example.com")).isEmpty();
    }
}
