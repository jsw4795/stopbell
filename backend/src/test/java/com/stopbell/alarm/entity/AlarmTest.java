package com.stopbell.alarm.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlarmTest {

    @Test
    @DisplayName("비활성 알람을 활성화하면 활성 상태가 된다")
    void activate_when_alarm_is_inactive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);

        alarm.activate();

        assertThat(alarm.isActive()).isTrue();
    }

    @Test
    @DisplayName("이미 활성화된 알람을 다시 활성화해도 활성 상태를 유지한다")
    void activate_when_alarm_is_already_active() {
        Alarm alarm = new Alarm(null, TransitType.BUS);
        alarm.activate();

        alarm.activate();

        assertThat(alarm.isActive()).isTrue();
    }

    @Test
    @DisplayName("활성 알람을 비활성화하면 비활성 상태가 된다")
    void deactivate_when_alarm_is_active() {
        Alarm alarm = new Alarm(null, TransitType.BUS);
        alarm.activate();

        alarm.deactivate();

        assertThat(alarm.isActive()).isFalse();
    }

    @Test
    @DisplayName("이미 비활성화된 알람을 다시 비활성화해도 비활성 상태를 유지한다")
    void deactivate_when_alarm_is_already_inactive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);

        alarm.deactivate();

        assertThat(alarm.isActive()).isFalse();
    }
}
