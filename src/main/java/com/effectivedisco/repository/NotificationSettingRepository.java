package com.effectivedisco.repository;

import com.effectivedisco.domain.NotificationSetting;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 알림 수신 설정 저장소.
 *
 * 행이 없는 타입은 기본 수신(enabled=true)으로 간주한다.
 */
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    /** 특정 사용자·알림 타입의 설정 조회 */
    Optional<NotificationSetting> findByUserAndNotificationType(User user, NotificationType notificationType);

    /** 특정 사용자의 전체 알림 설정 조회 */
    List<NotificationSetting> findByUser(User user);

    /**
     * 특정 사용자·알림 타입의 수신 여부를 직접 조회한다.
     * 행이 없으면 Optional.empty()를 반환하므로 호출측에서 기본값(true)을 적용해야 한다.
     */
    @Query("""
            SELECT ns.enabled
            FROM NotificationSetting ns
            WHERE ns.user.username = :username
              AND ns.notificationType = :type
            """)
    Optional<Boolean> findEnabledByUsernameAndType(@Param("username") String username,
                                                    @Param("type") NotificationType type);
}
