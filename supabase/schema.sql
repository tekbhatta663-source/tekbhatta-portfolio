-- TekBhatta portfolio CMS schema (optional module).
-- Run in the Supabase SQL editor. Public users can read published content only.
-- Writes are performed server-side with the service role key.

create table if not exists projects (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  description text not null,
  tags text[] not null default '{}',
  status text not null default 'in progress',
  live_url text,
  repo_url text,
  image_url text,
  sort_order int not null default 0,
  published boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists skills (
  id uuid primary key default gen_random_uuid(),
  category text not null,
  level text not null default 'learning',
  bar int not null default 25 check (bar between 0 and 100),
  items text[] not null default '{}',
  sort_order int not null default 0,
  published boolean not null default true
);

create table if not exists education (
  id uuid primary key default gen_random_uuid(),
  school text not null,
  program text not null,
  detail text,
  place text,
  period text,
  sort_order int not null default 0,
  published boolean not null default true
);

create table if not exists certifications (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  issuer text not null,
  year text,
  file_url text,
  published boolean not null default true
);

create table if not exists media (
  id uuid primary key default gen_random_uuid(),
  storage_path text not null,
  file_name text not null,
  mime_type text not null,
  size_bytes bigint not null,
  kind text not null default 'image' check (kind in ('image','video','pdf','document')),
  created_at timestamptz not null default now()
);

create table if not exists chatbot_kb (
  id uuid primary key default gen_random_uuid(),
  keys text[] not null default '{}',
  answer text not null,
  published boolean not null default true,
  updated_at timestamptz not null default now()
);

create table if not exists contact_messages (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  email text not null,
  subject text not null,
  message text not null,
  handled boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists site_settings (
  id int primary key default 1 check (id = 1),
  theme text not null default 'dark' check (theme in ('dark','light','neon')),
  bio text,
  headline text,
  email text,
  github_url text,
  linkedin_url text,
  updated_at timestamptz not null default now()
);

alter table projects enable row level security;
alter table skills enable row level security;
alter table education enable row level security;
alter table certifications enable row level security;
alter table media enable row level security;
alter table chatbot_kb enable row level security;
alter table contact_messages enable row level security;
alter table site_settings enable row level security;

-- Public read access, published rows only.
create policy "public read projects" on projects for select using (published = true);
create policy "public read skills" on skills for select using (published = true);
create policy "public read education" on education for select using (published = true);
create policy "public read certifications" on certifications for select using (published = true);
create policy "public read kb" on chatbot_kb for select using (published = true);
create policy "public read media" on media for select using (true);
create policy "public read settings" on site_settings for select using (true);

-- No public insert/update/delete. contact_messages is written by the server route
-- using the service role key, which bypasses RLS by design.

create index if not exists projects_sort_idx on projects (sort_order);
create index if not exists skills_sort_idx on skills (sort_order);
create index if not exists messages_created_idx on contact_messages (created_at desc);
