// =============================================================================
// LevelSweepAgent — local dev MongoDB bootstrap
// Creates the `level_sweep` database and the key collections referenced
// in architecture-spec §13.2. Phase 0 leaves them empty; indexes and TTL
// rules are added by services in Phase 1+.
//
// Mounted into the mongo container at /docker-entrypoint-initdb.d/init.js
// and executed automatically the first time the container starts.
// =============================================================================

print('[init.js] LevelSweepAgent dev Mongo bootstrap starting...');

const dbName = 'level_sweep';
const target = db.getSiblingDB(dbName);

// Application user (least-privilege on level_sweep DB only).
try {
    target.createUser({
        user: 'levelsweep_app',
        pwd: 'LevelSweep!2026',
        roles: [{ role: 'readWrite', db: dbName }]
    });
    print('[init.js] User levelsweep_app created.');
} catch (e) {
    // 51003 = user already exists; ignore on re-init.
    if (e.code !== 51003) {
        throw e;
    }
    print('[init.js] User levelsweep_app already exists; skipping.');
}

// Collections per architecture-spec §13.2 (audit + read models + agent memory).
const collections = [
    'bars_raw',
    'signal_evaluations',
    'audit_log_transitions',
    'audit_log_ai_calls',
    'news_calendar',
    'projection_inputs',
    'notifications_log',
    'journal_narratives',
    'journal_daily_reports',
    'agent_memory_short_term',
    'agent_memory_long_term'
];

collections.forEach(function (name) {
    if (target.getCollectionNames().indexOf(name) === -1) {
        target.createCollection(name);
        print('[init.js] Created collection: ' + name);
    } else {
        print('[init.js] Collection already exists: ' + name);
    }
});

print('[init.js] LevelSweepAgent dev Mongo bootstrap complete.');
