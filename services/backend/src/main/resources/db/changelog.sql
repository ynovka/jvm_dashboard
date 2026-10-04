--liquibase formatted sql
--changeset panel:1
CREATE TABLE bootstrap_state (id INT PRIMARY KEY);
INSERT INTO bootstrap_state VALUES (1);
CREATE TABLE users (id CHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, name VARCHAR(80) NOT NULL, password_hash VARCHAR(255) NOT NULL, admin BOOLEAN NOT NULL DEFAULT FALSE, disabled BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE workspaces (id CHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL, cpu DOUBLE NOT NULL, memory_mib BIGINT NOT NULL, disk_mib BIGINT NOT NULL);
CREATE TABLE memberships (workspace_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, role VARCHAR(16) NOT NULL, PRIMARY KEY(workspace_id,user_id), FOREIGN KEY(workspace_id) REFERENCES workspaces(id), FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE invitations (id CHAR(36) PRIMARY KEY, token_hash CHAR(64) NOT NULL UNIQUE, workspace_id CHAR(36), role VARCHAR(16) NOT NULL, email VARCHAR(254), expires BIGINT NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, revoked BOOLEAN NOT NULL DEFAULT FALSE, bootstrap BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE sessions (token_hash CHAR(64) PRIMARY KEY, user_id CHAR(36) NOT NULL, csrf VARCHAR(64) NOT NULL, created BIGINT NOT NULL, touched BIGINT NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE nodes (id VARCHAR(36) PRIMARY KEY, heartbeat BIGINT NOT NULL DEFAULT 0, ready BOOLEAN NOT NULL DEFAULT FALSE, cpu DOUBLE NOT NULL, memory_mib BIGINT NOT NULL, disk_mib BIGINT NOT NULL, snapshot LONGTEXT NOT NULL);
CREATE TABLE runtime_images (jdk INT PRIMARY KEY, image VARCHAR(255) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE applications (id CHAR(36) PRIMARY KEY, workspace_id CHAR(36) NOT NULL, node_id VARCHAR(36) NOT NULL, name VARCHAR(80) NOT NULL, cpu DOUBLE NOT NULL, memory_mib BIGINT NOT NULL, disk_mib BIGINT NOT NULL, spec LONGTEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 1, applied_revision BIGINT NOT NULL DEFAULT 0, generation BIGINT NOT NULL DEFAULT 1, desired VARCHAR(16) NOT NULL DEFAULT 'STOPPED', observed VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING', active_operation CHAR(36), deleted BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(workspace_id) REFERENCES workspaces(id), FOREIGN KEY(node_id) REFERENCES nodes(id));
CREATE TABLE application_permissions (app_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, PRIMARY KEY(app_id,user_id), FOREIGN KEY(app_id) REFERENCES applications(id), FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE application_revisions (app_id CHAR(36) NOT NULL, revision BIGINT NOT NULL, spec LONGTEXT NOT NULL, created BIGINT NOT NULL, PRIMARY KEY(app_id,revision), FOREIGN KEY(app_id) REFERENCES applications(id));
CREATE TABLE port_allocations (node_id VARCHAR(36) NOT NULL, port INT NOT NULL, protocol VARCHAR(3) NOT NULL, app_id CHAR(36) NOT NULL, PRIMARY KEY(node_id,port,protocol), FOREIGN KEY(app_id) REFERENCES applications(id));
CREATE TABLE operations (id CHAR(36) PRIMARY KEY, app_id CHAR(36), node_id VARCHAR(36) NOT NULL, user_id CHAR(36), kind VARCHAR(20) NOT NULL, payload LONGTEXT NOT NULL, generation BIGINT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'QUEUED', lease_until BIGINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0, result LONGTEXT, created BIGINT NOT NULL, idempotency_key VARCHAR(80), UNIQUE(user_id,idempotency_key), INDEX operation_queue(node_id,status,lease_until), FOREIGN KEY(app_id) REFERENCES applications(id));
CREATE TABLE audit_events (id CHAR(36) PRIMARY KEY, user_id CHAR(36), action VARCHAR(80) NOT NULL, object_id VARCHAR(80), created BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL, INDEX audit_time(created));
--changeset panel:2
CREATE TABLE storage_allocations (app_id CHAR(36) PRIMARY KEY, workspace_id CHAR(36) NOT NULL, disk_mib BIGINT NOT NULL, retained BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(app_id) REFERENCES applications(id));
INSERT INTO storage_allocations SELECT id,workspace_id,disk_mib,FALSE FROM applications WHERE deleted=FALSE;
