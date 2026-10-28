-- Ключи открытого API (/api/v1). Ключ выдаёт владелец на одну точку и с
-- перечнем прав (scopes). Сам ключ не храним — только sha256: показали один
-- раз при создании, потеряли — выпускаем новый.
create table if not exists public.api_keys (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  name text not null,
  key_prefix text not null,
  key_hash text not null unique,
  scopes text[] not null default '{}',
  created_by uuid null,
  created_at timestamptz not null default now(),
  last_used_at timestamptz null,
  revoked_at timestamptz null
);

create index if not exists api_keys_organization_idx on public.api_keys (organization_id, created_at desc);

-- Читается и пишется только сервером (service role). Политик нет: ни anon,
-- ни authenticated к таблице не допускаются.
alter table public.api_keys enable row level security;
