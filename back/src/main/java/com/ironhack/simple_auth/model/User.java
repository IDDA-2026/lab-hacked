package com.ironhack.simple_auth.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A registered member of the platform.
 *
 * This is the table the attacker walked away with. It holds private emails and
 * password hashes that no public endpoint is ever supposed to return.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    // Yes, these would be properly hashed in a real app. The point of the lab is
    // that they should NEVER leave the database, no matter how they are stored.
    @JsonIgnore
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    public User(String fullName, String email, String avatarUrl, String passwordHash, String role) {
        this.fullName = fullName;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.passwordHash = passwordHash;
        this.role = role;
    }
}
