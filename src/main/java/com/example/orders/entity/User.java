package com.example.orders.entity;

import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An application user: CUSTOMER, SUPPORT or ADMIN.
 *
 * <p>Only ever holds a BCrypt hash - see {@link #passwordHash}. There is no plaintext password
 * field anywhere in the persistence model, so there is nothing for a careless log statement or a
 * serialized entity to leak.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_seq")
    @SequenceGenerator(name = "users_seq", sequenceName = "users_seq", allocationSize = 50)
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    /**
     * Stored lower-cased. {@code uq_users_email_lower} enforces case-insensitive uniqueness in the
     * database, and lookups must go through {@code findByEmailIgnoreCase} to use that index.
     */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * BCrypt hash, never a plaintext password.
     *
     * <p>No {@code @Getter} exception is made for it, but nothing outside the authentication code
     * has a reason to read it - and it must never reach a DTO. Stage 3 owns hashing.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** STRING, never ORDINAL: reordering {@link Role} must not reassign anyone's privileges. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public User(String username, String email, String passwordHash, Role role) {
        this.username = username;
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /** Keeps the stored value aligned with the case-insensitive unique index. */
    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Identity is the primary key, and only once it has been assigned. Two unsaved instances are
     * never equal - they are two separate things until the database says otherwise.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.getId());
    }

    /**
     * Constant on purpose. A hash derived from the id would change the moment Hibernate assigns
     * one, so an entity added to a {@code HashSet} before flush would be unfindable afterwards.
     */
    @Override
    public int hashCode() {
        return User.class.hashCode();
    }

    @Override
    public String toString() {
        // No password hash and no email: entities end up in log lines and exception messages.
        return "User{id=" + id + ", username='" + username + "', role=" + role + '}';
    }
}
