-- =============================================================================
-- LevelSweepAgent — local dev MS SQL Server bootstrap
-- Creates the `level_sweep` database and a non-sa application login.
-- Run automatically by the mssql container on first boot via the
-- /docker-entrypoint-initdb.d hook OR manually:
--     sqlcmd -S localhost,1433 -U sa -P "LevelSweep!2026" -C -i dev/sql/init.sql
--
-- NOTE: this is dev only. Production schemas are managed by Flyway under each
-- service's src/main/resources/db/migration. Phase 0 = empty database.
-- =============================================================================

USE master;
GO

-- ---------------------------------------------------------------------------
-- 1. Create the application database
-- ---------------------------------------------------------------------------
IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = N'level_sweep')
BEGIN
    CREATE DATABASE level_sweep
        COLLATE SQL_Latin1_General_CP1_CI_AS;
    PRINT 'Database level_sweep created.';
END
ELSE
BEGIN
    PRINT 'Database level_sweep already exists; skipping CREATE.';
END
GO

-- ---------------------------------------------------------------------------
-- 2. Create dev login + user (least-privilege; sa is for ops only)
-- ---------------------------------------------------------------------------
IF NOT EXISTS (SELECT loginname FROM master.dbo.syslogins WHERE name = N'levelsweep_app')
BEGIN
    CREATE LOGIN levelsweep_app
        WITH PASSWORD = N'LevelSweep!2026',
             CHECK_POLICY = OFF;
    PRINT 'Login levelsweep_app created.';
END
GO

USE level_sweep;
GO

IF NOT EXISTS (SELECT name FROM sys.database_principals WHERE name = N'levelsweep_app')
BEGIN
    CREATE USER levelsweep_app FOR LOGIN levelsweep_app;
    EXEC sp_addrolemember N'db_owner', N'levelsweep_app';
    PRINT 'User levelsweep_app added to db_owner on level_sweep.';
END
GO

-- ---------------------------------------------------------------------------
-- 3. Phase 0 sanity check — Flyway-managed tables come later (Phase 1+).
--    See architecture-spec §13.1 for the full schema (tenants, trades,
--    orders, positions, fills, fsm_transitions, agent_decisions, ...).
-- ---------------------------------------------------------------------------
PRINT 'level_sweep ready. Phase 0 leaves schema empty; Flyway runs in Phase 1.';
GO
