#!/usr/bin/env zx

// Хелпер для команд с dotenv (дамп/рестор)
const withEnv = (cmd) => {
  $`dotenv -e .env -- sh -c ${cmd}`;
  console.log('Done!')
};

const command = argv._[0];

switch (command) {
  case "dump":
    withEnv(
      `docker exec -t $DB_HOST pg_dumpall -U $POSTGRES_USER -c -f /backup/full_backup.sql`,
    );
    break;

  case "restore":
    withEnv(
      `docker exec -t $DB_HOST psql -U $POSTGRES_USER -f /backup/full_backup.sql`,
    );
    break;

  default:
    console.log("Unknown command");
    process.exit(1);
}
