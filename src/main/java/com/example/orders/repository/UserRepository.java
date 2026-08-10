package com.example.orders.repository;

import java.util.Optional;

import com.example.orders.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks a user up by email, case-insensitively.
     *
     * <p>Spring Data renders this as {@code where lower(email) = lower(?)}, which is exactly the
     * expression behind {@code uq_users_email_lower} - so the lookup uses that index instead of
     * scanning. A plain {@code findByEmail} would be case-sensitive and would not match a user who
     * typed their address with a capital letter.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);
}
