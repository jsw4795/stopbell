CREATE TABLE bus_metadata_sync_states (
    provider VARCHAR(20) NOT NULL PRIMARY KEY,
    last_complete_sync_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_bus_metadata_sync_states_provider CHECK (provider IN ('TAGO', 'SEOUL_BUS'))
);
