-- Кассы без единой точки: остались от удалённых точек и организаций
-- (Castle, SHAIQAS, Orda, пробная F16 Test). Точки у них нет, а токен работал.
-- Дальше удаление точки убирает такие кассы само (lib/server/company-delete.ts),
-- а requirePointDevice не пускает кассу без точек.
delete from public.point_projects p
where not exists (select 1 from public.point_project_companies pc where pc.project_id = p.id);
