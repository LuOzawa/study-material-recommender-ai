package com.recommend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    // @JsonIgnore：兼底防护。接口层已手工 setPassword(null)，但一旦将来新增
    // 返回 User 实体的接口而忘了处理，口令哈希/明文就会直接泄漏到响应体。
    @JsonIgnore
    @Column(nullable = false, length = 255)
    private String password;

    @Column(unique = true, length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.USER;

    @Column(name = "target_school", length = 100)
    private String targetSchool;

    @Column(length = 100)
    private String major;

    @Enumerated(EnumType.STRING)
    @Column(name = "study_phase", length = 20)
    private StudyPhase studyPhase = StudyPhase.PREPARATION;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Role { USER, ADMIN }

    public enum StudyPhase { PREPARATION, INITIAL, INTENSIVE, REVIEW }
}
