package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL)
    private List<Post> posts = new ArrayList<>();

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL)
    private List<Comment> comments = new ArrayList<>();

    /**
     * 권한 역할. "ROLE_USER" 또는 "ROLE_ADMIN".
     * Spring Security의 hasRole("ADMIN") 검사와 일치시키기 위해 ROLE_ 접두사를 포함한다.
     */
    @Column(nullable = false, columnDefinition = "varchar(255) default 'ROLE_USER'")
    private String role = "ROLE_USER";

    /** 자기 소개 (선택 입력, 최대 300자) */
    @Column(columnDefinition = "TEXT")
    private String bio;

    @Builder
    public User(String username, String email, String password) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.role = "ROLE_USER";
        this.createdAt = LocalDateTime.now();
    }

    /** 관리자 권한 부여/회수 */
    public void promoteToAdmin()  { this.role = "ROLE_ADMIN"; }
    public void demoteToUser()    { this.role = "ROLE_USER";  }
    public boolean isAdmin()      { return "ROLE_ADMIN".equals(this.role); }

    /** 프로필 편집 */
    public void updateBio(String bio)             { this.bio = bio; }
    public void updateEmail(String email)         { this.email = email; }
    public void updatePassword(String encoded)    { this.password = encoded; }
}
