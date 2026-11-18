-- Preserve existing version IDs and rows; reference data is immutable per version.
ALTER TABLE area_sales_q DROP CONSTRAINT area_sales_q_pkey;
ALTER TABLE area_sales_q ADD PRIMARY KEY (version_id, quarter, area_code, service_code);
CREATE INDEX area_sales_q_lookup_idx ON area_sales_q (quarter, area_code, service_code);
CREATE VIEW area_sales_q_active AS SELECT q.* FROM area_sales_q q JOIN data_versions v ON v.id=q.version_id WHERE v.status='ACTIVE';
ALTER TABLE area_stores_q DROP CONSTRAINT area_stores_q_pkey;
ALTER TABLE area_stores_q ADD PRIMARY KEY (version_id, quarter, area_code, service_code);
CREATE INDEX area_stores_q_lookup_idx ON area_stores_q (quarter, area_code, service_code);
CREATE VIEW area_stores_q_active AS SELECT q.* FROM area_stores_q q JOIN data_versions v ON v.id=q.version_id WHERE v.status='ACTIVE';
ALTER TABLE area_floating_pop_q DROP CONSTRAINT area_floating_pop_q_pkey;
ALTER TABLE area_floating_pop_q ADD PRIMARY KEY (version_id, quarter, area_code);
CREATE INDEX area_floating_pop_q_lookup_idx ON area_floating_pop_q (quarter, area_code);
CREATE VIEW area_floating_pop_q_active AS SELECT q.* FROM area_floating_pop_q q JOIN data_versions v ON v.id=q.version_id WHERE v.status='ACTIVE';
ALTER TABLE area_worker_pop_q DROP CONSTRAINT area_worker_pop_q_pkey;
ALTER TABLE area_worker_pop_q ADD PRIMARY KEY (version_id, quarter, area_code);
CREATE INDEX area_worker_pop_q_lookup_idx ON area_worker_pop_q (quarter, area_code);
CREATE VIEW area_worker_pop_q_active AS SELECT q.* FROM area_worker_pop_q q JOIN data_versions v ON v.id=q.version_id WHERE v.status='ACTIVE';
ALTER TABLE game_data_pins DROP CONSTRAINT game_data_pins_version_id_fkey;
ALTER TABLE game_data_pins ADD CONSTRAINT game_data_pins_version_id_fkey FOREIGN KEY (version_id) REFERENCES data_versions(id) ON DELETE RESTRICT;
