\set ON_ERROR_STOP on
\getenv production_password FINSIGHT_DB_PRODUCTION_PASSWORD
\getenv staging_password FINSIGHT_DB_STAGING_PASSWORD

CREATE ROLE finsight LOGIN PASSWORD :'production_password';
CREATE ROLE finsight_staging LOGIN PASSWORD :'staging_password';
CREATE DATABASE finsight OWNER finsight;
CREATE DATABASE finsight_staging OWNER finsight_staging;
REVOKE CONNECT ON DATABASE finsight FROM PUBLIC;
REVOKE CONNECT ON DATABASE finsight_staging FROM PUBLIC;
