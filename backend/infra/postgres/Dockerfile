# PostgreSQL 16 + pg_partman, for automated partition maintenance on the
# partitioned tables from Deliverable 6 (loan_transaction, payment_transaction,
# disbursement_instruction, gl_posting, audit_log, premium_invoice).
FROM postgres:16

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-partman postgresql-16-cron \
    && rm -rf /var/lib/apt/lists/*

# pg_cron must be loaded via shared_preload_libraries -- baked into the image's default
# config here so local dev matches how a managed Postgres (e.g. RDS/Cloud SQL) would
# need it configured via parameter group, catching config drift early rather than only
# in a real environment.
RUN echo "shared_preload_libraries = 'pg_cron'" >> /usr/share/postgresql/postgresql.conf.sample \
    && echo "cron.database_name = 'lifeplatform'" >> /usr/share/postgresql/postgresql.conf.sample
