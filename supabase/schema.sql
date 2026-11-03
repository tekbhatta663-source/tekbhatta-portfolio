-- TEKBHATTA portfolio schema
-- Run this once in the Supabase SQL editor, then create an admin user under
-- Authentication > Users (email + password). The admin dashboard signs in with it.

create extension if not exists "pgcrypto";

-- ---------- Tables ----------
create table if not exists settings (
  id integer primary key default 1,
  display_name text,
  headline text,
  tagline text,
  location text,
  email text,
  linkedin_url text,
  github_url text,
  bio text,
  updated_at timestamptz default now()
);

create table if not exists projects (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  summary text,
  description text,
  tech text[] default '{}',
  status text default 'Planned',
  repo_url text,
  demo_url text,
  image_url text,
  sort_order integer default 0,
  created_at timestamptz default now()
);

create table if not exists skills (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  category text default 'AI',
  level text default 'Learning',
  note text,
  sort_order integer default 0,
  created_at timestamptz default now()
);

create table if not exists education (
  id uuid primary key default gen_random_uuid(),
  institution text not null,
  programme text,
  location text,
  start_year integer,
  end_year integer,
  note text,
  created_at timestamptz default now()
);

create table if not exists certifications (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  issuer text,
  year integer,
  file_url text,
  created_at timestamptz default now()
);

create table if not exists contact_messages (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  email text not null,
  subject text,
  message text not null,
  handled boolean default false,
  created_at timestamptz default now()
);

create table if not exists chatbot_kb (
  id uuid primary key default gen_random_uuid(),
  question text not null,
  keywords text[] default '{}',
  answer text not null,
  created_at timestamptz default now()
);

-- ---------- Row Level Security ----------
alter table settings enable row level security;
alter table projects enable row level security;
alter table skills enable row level security;
alter table education enable row level security;
alter table certifications enable row level security;
alter table contact_messages enable row level security;
alter table chatbot_kb enable row level security;

-- Public may read published content.
do $$
declare t text;
begin
  foreach t in array array['settings','projects','skills','education','certifications','chatbot_kb']
  loop
    execute format('drop policy if exists "public read %1$s" on %1$s', t);
    execute format('create policy "public read %1$s" on %1$s for select using (true)', t);
    execute format('drop policy if exists "admin write %1$s" on %1$s', t);
    execute format('create policy "admin write %1$s" on %1$s for all to authenticated using (true) with check (true)', t);
  end loop;
end $$;

-- Anyone may submit a message, only the signed-in admin may read or change them.
drop policy if exists "public insert contact" on contact_messages;
create policy "public insert contact" on contact_messages for insert with check (true);
drop policy if exists "admin read contact" on contact_messages;
create policy "admin read contact" on contact_messages for select to authenticated using (true);
drop policy if exists "admin update contact" on contact_messages;
create policy "admin update contact" on contact_messages for update to authenticated using (true);
drop policy if exists "admin delete contact" on contact_messages;
create policy "admin delete contact" on contact_messages for delete to authenticated using (true);

-- ---------- Storage ----------
insert into storage.buckets (id, name, public, file_size_limit)
values ('media', 'media', true, 52428800)
on conflict (id) do update set public = true, file_size_limit = 52428800;

drop policy if exists "media public read" on storage.objects;
create policy "media public read" on storage.objects for select using (bucket_id = 'media');
drop policy if exists "media admin write" on storage.objects;
create policy "media admin write" on storage.objects for insert to authenticated with check (bucket_id = 'media');
drop policy if exists "media admin update" on storage.objects;
create policy "media admin update" on storage.objects for update to authenticated using (bucket_id = 'media');
drop policy if exists "media admin delete" on storage.objects;
create policy "media admin delete" on storage.objects for delete to authenticated using (bucket_id = 'media');

-- ---------- Seed ----------
insert into settings (id) values (1) on conflict (id) do nothing;
