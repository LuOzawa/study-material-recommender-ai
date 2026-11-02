-- PostgreSQL initialization wrapper.
-- Usage: createdb recommend_db && psql -d recommend_db -f sql/init.sql
\set ON_ERROR_STOP on
\ir schema.sql
\ir seed.sql
