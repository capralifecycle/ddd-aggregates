CREATE TABLE example (
  id uuid NOT NULL PRIMARY KEY,
  created_at timestamptz NOT NULL,
  modified_at timestamptz NOT NULL,
  version bigint NOT NULL,
  data jsonb NOT NULL
);

CREATE TABLE additional_columns_example (
  id uuid NOT NULL PRIMARY KEY,
  created_at timestamptz NOT NULL,
  modified_at timestamptz NOT NULL,
  version bigint NOT NULL,
  data jsonb NOT NULL,
  extra1 text,
  extra2 text
);

CREATE TABLE event_outbox (
  id bigserial PRIMARY KEY,
  data jsonb NOT NULL
);
