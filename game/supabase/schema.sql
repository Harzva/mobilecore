-- TuiMa Push Supabase schema v0.1
-- Purpose: store benchmark game submissions, custom boards, and leaderboard views.

create extension if not exists pgcrypto;

create table if not exists public.submissions (
  id uuid primary key default gen_random_uuid(),
  schema_version text not null default 'tuima-push-submission-v0.1',
  anonymous_id text not null,
  player_name text not null check (char_length(player_name) between 1 and 32),

  device_class text not null,
  os text not null,
  ram_class text,
  chip_class text not null,

  mobilecore_version text not null,
  runtime_backend text not null,
  model_format text not null,

  board_id text not null,
  board_version integer not null default 1,
  board_type text not null check (board_type in ('standard', 'custom')),

  total_score integer not null check (total_score >= 0),
  avg_decode_tok_s numeric not null check (avg_decode_tok_s >= 0),
  first_token_ms integer not null check (first_token_ms >= 0),
  memory_peak_mb integer not null check (memory_peak_mb >= 0),
  temperature_peak_celsius numeric,
  best_model text not null,
  cleared_models jsonb not null default '[]'::jsonb,
  stages_completed integer not null default 0,
  stage_total integer not null default 5,
  moves_used integer,

  status text not null default 'accepted' check (status in ('accepted', 'pending_review', 'rejected')),
  created_at timestamptz not null default now()
);

create index if not exists idx_submissions_leaderboard
on public.submissions (board_id, total_score desc, avg_decode_tok_s desc, created_at asc)
where status = 'accepted';

create index if not exists idx_submissions_device_class
on public.submissions (device_class, total_score desc)
where status = 'accepted';

create table if not exists public.custom_boards (
  id uuid primary key default gen_random_uuid(),
  board_id text not null,
  name text not null,
  version integer not null default 1,
  schema_version text not null default 'tuima-push-board-v0.1',
  board_json jsonb not null,
  created_by text,
  created_at timestamptz not null default now(),
  unique (board_id, version)
);

create or replace view public.leaderboard_global as
select
  id,
  player_name,
  device_class,
  os,
  chip_class,
  board_id,
  board_version,
  total_score,
  avg_decode_tok_s,
  first_token_ms,
  memory_peak_mb,
  best_model,
  cleared_models,
  stages_completed,
  stage_total,
  created_at,
  dense_rank() over (partition by board_id order by total_score desc, avg_decode_tok_s desc, created_at asc) as rank
from public.submissions
where status = 'accepted';

create or replace view public.leaderboard_by_device_class as
select
  id,
  player_name,
  device_class,
  os,
  chip_class,
  board_id,
  total_score,
  avg_decode_tok_s,
  best_model,
  cleared_models,
  created_at,
  dense_rank() over (partition by board_id, device_class order by total_score desc, avg_decode_tok_s desc, created_at asc) as rank
from public.submissions
where status = 'accepted';

alter table public.submissions enable row level security;
alter table public.custom_boards enable row level security;

-- Public read access for leaderboard.
create policy if not exists "submissions_public_read"
on public.submissions
for select
using (status = 'accepted');

-- Public anonymous insert. Keep strict validation in client and database constraints.
create policy if not exists "submissions_public_insert"
on public.submissions
for insert
with check (true);

-- Do not allow public update/delete by default.

create policy if not exists "custom_boards_public_read"
on public.custom_boards
for select
using (true);

create policy if not exists "custom_boards_public_insert"
on public.custom_boards
for insert
with check (true);
