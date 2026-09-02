package com.stopbell.alarm.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlarmTest {

    @Test
    void activateChangesAnInactiveAlarmToActive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);

        alarm.activate();

        assertThat(alarm.isActive()).isTrue();
    }

    @Test
    void activateKeepsAnActiveAlarmActive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);
        alarm.activate();

        alarm.activate();

        assertThat(alarm.isActive()).isTrue();
    }

    @Test
    void deactivateChangesAnActiveAlarmToInactive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);
        alarm.activate();

        alarm.deactivate();

        assertThat(alarm.isActive()).isFalse();
    }

    @Test
    void deactivateKeepsAnInactiveAlarmInactive() {
        Alarm alarm = new Alarm(null, TransitType.BUS);

        alarm.deactivate();

        assertThat(alarm.isActive()).isFalse();
    }
}
