-- Пакет «Orda Сервис» (детейлинг, СТО): страницы денег и команды.
--
-- Модель «1 фича = 1 страница»: в пакете было только dashboard.owner,
-- finance.base, service.jobs — у организации на этом пакете в меню не было
-- «Доходов», «Расходов», «Зарплаты». Добавляем страницы, цену не трогаем.
-- Повторный запуск ничего не дублирует.

update public.packages
set feature_codes = (
      select array_agg(distinct f order by f)
      from unnest(feature_codes || array[
        'finance.pnl',
        'page:/income', 'page:/expenses', 'page:/categories',
        'page:/reports', 'page:/weekly-report', 'page:/cashflow', 'page:/profitability',
        'page:/tax', 'page:/goals', 'page:/analytics',
        'page:/salary', 'page:/salary/rules', 'page:/operators', 'page:/staff', 'page:/structure',
        'page:/tasks', 'page:/regulations'
      ]) as f
    ),
    updated_at = now()
where code = 'service';
