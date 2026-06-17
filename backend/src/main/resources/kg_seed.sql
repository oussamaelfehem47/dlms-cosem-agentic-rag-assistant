-- ============================================================================
-- Knowledge Graph Seed: DLMS/COSEM Domain Ontology
-- Run AFTER schema.sql (idempotent — uses ON CONFLICT on (type, label) for
-- nodes and (source_id, target_id, edge_type) for edges).
-- ============================================================================

-- ============================================================================
-- NODES
-- ============================================================================

-- OBIS Nodes — Electricity metering values, abstract objects, profiles
INSERT INTO kg_nodes (id, type, label, metadata) VALUES
    (gen_random_uuid(), 'OBIS', '1.0.1.8.0.255',  '{"description":"Active energy import total","ic":3,"unit":"Wh","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.2.8.0.255',  '{"description":"Active energy export total","ic":3,"unit":"Wh","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.1.7.0.255',  '{"description":"Active power import instantaneous","ic":3,"unit":"W","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.2.7.0.255',  '{"description":"Active power export instantaneous","ic":3,"unit":"W","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.3.8.0.255',  '{"description":"Reactive energy import total","ic":3,"unit":"varh","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.4.8.0.255',  '{"description":"Reactive energy export total","ic":3,"unit":"varh","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.9.8.0.255',  '{"description":"Apparent energy import total","ic":3,"unit":"VAh","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.31.7.0.255', '{"description":"Current phase L1 instantaneous","ic":3,"unit":"A","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.32.7.0.255', '{"description":"Voltage phase L1 instantaneous","ic":3,"unit":"V","scaler":-1,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '1.0.13.7.0.255', '{"description":"Power factor instantaneous","ic":3,"unit":"","scaler":-3,"group":"Electricity"}'),
    (gen_random_uuid(), 'OBIS', '0.0.1.0.0.255',  '{"description":"Clock (date-time)","ic":8,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '0.0.40.0.0.255', '{"description":"Association LN current","ic":15,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '0.0.96.1.0.255', '{"description":"Device ID 1 (manufacturer)","ic":1,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '0.0.96.1.1.255', '{"description":"Device ID 2 (serial number)","ic":1,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '1.0.0.8.0.255',  '{"description":"Billing period counter","ic":1,"unit":"","scaler":0,"group":"Billing"}'),
    (gen_random_uuid(), 'OBIS', '1.0.99.1.0.255', '{"description":"Load profile with period 1","ic":7,"unit":"","scaler":0,"group":"Profile"}'),
    (gen_random_uuid(), 'OBIS', '1.0.99.2.0.255', '{"description":"Load profile with period 2","ic":7,"unit":"","scaler":0,"group":"Profile"}'),
    (gen_random_uuid(), 'OBIS', '1.0.94.7.0.255', '{"description":"Event log","ic":7,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '0.0.10.0.100.255', '{"description":"Image transfer","ic":18,"unit":"","scaler":0,"group":"Abstract"}'),
    (gen_random_uuid(), 'OBIS', '0.0.96.9.0.255', '{"description":"Ambient temperature","ic":3,"unit":"°C","scaler":-1,"group":"Environment"}')
ON CONFLICT (type, label) DO NOTHING;

-- IC Nodes — DLMS/COSEM Interface Classes
INSERT INTO kg_nodes (id, type, label, metadata) VALUES
    (gen_random_uuid(), 'IC', 'IC 1',  '{"name":"Data","description":"Single-value data storage"}'),
    (gen_random_uuid(), 'IC', 'IC 3',  '{"name":"Register","description":"Numeric value with unit and scaler"}'),
    (gen_random_uuid(), 'IC', 'IC 4',  '{"name":"Extended Register","description":"Register with capture time"}'),
    (gen_random_uuid(), 'IC', 'IC 7',  '{"name":"Profile Generic","description":"Tabular data with capture objects"}'),
    (gen_random_uuid(), 'IC', 'IC 8',  '{"name":"Clock","description":"Date-time management"}'),
    (gen_random_uuid(), 'IC', 'IC 15', '{"name":"Association LN","description":"Application association management"}'),
    (gen_random_uuid(), 'IC', 'IC 17', '{"name":"SAP Assignment","description":"Logical device to SAP mapping"}'),
    (gen_random_uuid(), 'IC', 'IC 18', '{"name":"Image Transfer","description":"Firmware upgrade support"}'),
    (gen_random_uuid(), 'IC', 'IC 20', '{"name":"Activity Calendar","description":"Tariff switching schedule"}'),
    (gen_random_uuid(), 'IC', 'IC 21', '{"name":"Register Monitor","description":"Threshold monitoring with events"}'),
    (gen_random_uuid(), 'IC', 'IC 22', '{"name":"Single Action Schedule","description":"Timed single action execution"}'),
    (gen_random_uuid(), 'IC', 'IC 23', '{"name":"IEC HDLC Setup","description":"HDLC communication parameters"}'),
    (gen_random_uuid(), 'IC', 'IC 64', '{"name":"Security Setup","description":"Security suite and key management"}')
ON CONFLICT (type, label) DO NOTHING;

-- APDU Nodes — Application Protocol Data Unit types
INSERT INTO kg_nodes (id, type, label, metadata) VALUES
    (gen_random_uuid(), 'APDU', 'GET-Request',       '{"tag":"0xC0","description":"Read attribute value from server object"}'),
    (gen_random_uuid(), 'APDU', 'GET-Response',      '{"tag":"0xC4","description":"Server response with requested attribute value"}'),
    (gen_random_uuid(), 'APDU', 'SET-Request',       '{"tag":"0xC1","description":"Write attribute value to server object"}'),
    (gen_random_uuid(), 'APDU', 'SET-Response',      '{"tag":"0xC5","description":"Server confirmation of SET operation"}'),
    (gen_random_uuid(), 'APDU', 'ACTION-Request',    '{"tag":"0xC3","description":"Execute method on server object"}'),
    (gen_random_uuid(), 'APDU', 'ACTION-Response',   '{"tag":"0xC7","description":"Server confirmation of ACTION execution"}'),
    (gen_random_uuid(), 'APDU', 'AARQ',              '{"tag":"0x60","description":"Association Request — client initiates application association"}'),
    (gen_random_uuid(), 'APDU', 'AARE',              '{"tag":"0x61","description":"Association Response — server accepts or rejects association"}'),
    (gen_random_uuid(), 'APDU', 'GBT',               '{"tag":"0xE6","description":"General Block Transfer — fragmented large data transfer"}'),
    (gen_random_uuid(), 'APDU', 'DATA-Notification', '{"tag":"0x01","description":"Unsolicited push data from server to client"}')
ON CONFLICT (type, label) DO NOTHING;

-- ALARM Nodes — Metering/HES/DCU alarm codes
INSERT INTO kg_nodes (id, type, label, metadata) VALUES
    (gen_random_uuid(), 'ALARM', '0x0001', '{"description":"Power failure","severity":"MEDIUM","component":"METER"}'),
    (gen_random_uuid(), 'ALARM', '0x0002', '{"description":"Clock sync failure","severity":"MEDIUM","component":"HES"}'),
    (gen_random_uuid(), 'ALARM', '0x0008', '{"description":"Communication error","severity":"HIGH","component":"WAN"}'),
    (gen_random_uuid(), 'ALARM', '0x0020', '{"description":"Tamper detected","severity":"HIGH","component":"SECURITY"}'),
    (gen_random_uuid(), 'ALARM', '0x1342', '{"description":"SICONIA DCU comm failure","severity":"HIGH","component":"HES"}'),
    (gen_random_uuid(), 'ALARM', '0x2001', '{"description":"Meter not responding","severity":"HIGH","component":"METER"}'),
    (gen_random_uuid(), 'ALARM', '0x4002', '{"description":"HES timeout","severity":"MEDIUM","component":"HES"}'),
    (gen_random_uuid(), 'ALARM', '0x8004', '{"description":"Authentication failure","severity":"CRITICAL","component":"SECURITY"}')
ON CONFLICT (type, label) DO NOTHING;

-- ============================================================================
-- EDGES
-- ============================================================================

-- OBIS → IC edges (belongs_to)
-- Each OBIS node is linked to its corresponding Interface Class via metadata.ic

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.1.8.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.2.8.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.1.7.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.2.7.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.3.8.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.4.8.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.9.8.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.31.7.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.32.7.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.13.7.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.1.0.0.255'
  AND ic.type='IC' AND ic.label='IC 8'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.40.0.0.255'
  AND ic.type='IC' AND ic.label='IC 15'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.96.1.0.255'
  AND ic.type='IC' AND ic.label='IC 1'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.96.1.1.255'
  AND ic.type='IC' AND ic.label='IC 1'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.0.8.0.255'
  AND ic.type='IC' AND ic.label='IC 1'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.99.1.0.255'
  AND ic.type='IC' AND ic.label='IC 7'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.99.2.0.255'
  AND ic.type='IC' AND ic.label='IC 7'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='1.0.94.7.0.255'
  AND ic.type='IC' AND ic.label='IC 7'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.10.0.100.255'
  AND ic.type='IC' AND ic.label='IC 18'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), obis.id, ic.id, 'belongs_to', '{}'
FROM kg_nodes obis, kg_nodes ic
WHERE obis.type='OBIS' AND obis.label='0.0.96.9.0.255'
  AND ic.type='IC' AND ic.label='IC 3'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;

-- ALARM → IC 64 (Security Setup) edges (affects)
-- Each alarm node is linked to the Security Setup IC with the alarm's metadata
-- carried as edge metadata for context
INSERT INTO kg_edges (id, source_id, target_id, edge_type, metadata)
SELECT gen_random_uuid(), alarm.id,
       (SELECT id FROM kg_nodes WHERE type='IC' AND label='IC 64' LIMIT 1),
       'affects', alarm.metadata
FROM kg_nodes alarm
WHERE alarm.type='ALARM'
ON CONFLICT (source_id, target_id, edge_type) DO NOTHING;
