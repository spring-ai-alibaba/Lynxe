DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'longtext') THEN CREATE DOMAIN longtext AS text; END IF; END $$;
