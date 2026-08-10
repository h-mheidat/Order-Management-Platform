package com.example.orders.mapper;

import com.example.orders.dto.UserResponse;
import com.example.orders.entity.User;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO conversion for users.
 *
 * <p>Hand-written rather than generated. At this size a mapping library buys nothing and costs an
 * annotation processor, and the explicit constructor call is what makes it obvious that
 * {@code passwordHash} is not being copied.
 */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
    }
}
