package com.effectivedisco.service;

import com.effectivedisco.domain.NotificationSetting;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * 사용자별 알림 수신 설정 관리 서비스.
 *
 * 각 {@link NotificationType}에 대해 수신 on/off를 관리한다.
 * DB에 행이 없으면 해당 타입은 기본 수신(enabled=true)으로 간주한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final NotificationSettingRepository settingRepository;
    private final UserLookupService             userLookupService;

    /**
     * 해당 사용자의 알림 수신 설정 전체를 반환한다.
     * DB에 행이 없는 타입은 기본 true(수신)으로 포함된다.
     *
     * @return NotificationType → enabled 매핑
     */
    @Transactional(readOnly = true)
    public Map<NotificationType, Boolean> getSettings(String username) {
        User user = userLookupService.findByUsername(username);
        // 기본값: 모든 타입 수신
        Map<NotificationType, Boolean> result = new EnumMap<>(NotificationType.class);
        for (NotificationType type : NotificationType.values()) {
            result.put(type, true);
        }
        // DB에 저장된 설정으로 덮어쓰기
        for (NotificationSetting setting : settingRepository.findByUser(user)) {
            result.put(setting.getNotificationType(), setting.isEnabled());
        }
        return result;
    }

    /**
     * 특정 알림 타입의 수신 설정을 변경한다.
     * 행이 없으면 새로 생성하고, 있으면 갱신한다.
     */
    @Transactional
    public void updateSetting(String username, NotificationType type, boolean enabled) {
        User user = userLookupService.findByUsername(username);
        NotificationSetting setting = settingRepository.findByUserAndNotificationType(user, type)
                .orElse(null);
        if (setting == null) {
            settingRepository.save(new NotificationSetting(user, type, enabled));
        } else {
            setting.updateEnabled(enabled);
        }
    }

    /**
     * 해당 사용자가 특정 알림 타입을 수신하도록 설정했는지 확인한다.
     * 행이 없으면 기본 수신(true)으로 간주한다.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(String username, NotificationType type) {
        return settingRepository.findEnabledByUsernameAndType(username, type)
                .orElse(true);
    }

}
