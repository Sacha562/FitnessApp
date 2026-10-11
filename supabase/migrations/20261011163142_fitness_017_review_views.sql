-- fitness_017_review_views (M1) — DATA_MODEL.md 14 (R-V1..R-V9, 14.3, 14.4-14.23), 13.2, 17.4 item V, 19.9
--
-- The read-only fitness_review views that Claude Code queries as postgres through the Supabase MCP during
-- algorithm reviews (M5). Created in the order of the 14.3 catalogue, because later views select from
-- v_set_facts, v_set_estimates, v_prescription_error, v_challenge_timeline and v_db_size.
--
--   R-V1  every view is security_invoker = true (a view never widens access; advisor lint 0010).
--   R-V2  no grants. USAGE on fitness_review was revoked from PUBLIC by fitness_001 and was never granted
--         to anon, authenticated or service_role; the schema is never exposed. Only postgres can query.
--   R-V3  user_id is the first column of every view except v_db_size and v_db_size_summary.
--   R-V4  engine numbers are read from the logged snapshot keys of 10.2 and engine-written columns; no
--         e1RM, set-credit, readiness or ISO-week formula is recomputed here.
--   R-V5  live rows only (deleted_at is null; rule events only of the enrollment's current fold).
--   R-V6  newest snapshot per subject by (created_at desc, id desc).
--   R-V7  no personal free text, except calendar title/location (v_calendar_events) and
--         metric_observations.validity_note (v_challenge_tests).
--   R-V9  a meaning change is drop view + create view in a new migration, never create or replace.
--
-- Deviation from the 14.20 SQL: v_data_quality lists user_id first (then check_code, severity, entity,
-- entity_id, detail) so that it satisfies R-V3; the predicates are unchanged. Every other view is the spec
-- SQL verbatim (all referenced columns exist in migrations 004-015). Data-free: no inserts.

-- ---------------------------------------------------------------------------------------------------
-- 14.4 v_set_facts: one row per live set with its session, block and exercise context
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_set_facts with (security_invoker = true) as
select
  s.user_id,
  s.id                    as set_id,
  s.session_id,
  s.session_exercise_id,
  se.block_id,
  sb.block_kind,
  s.exercise_id,
  e.slug                  as exercise_slug,
  e.pattern, e.mechanics, e.contraction, e.curve_class, e.rest_class, e.safety_class,
  ws.kind                 as session_kind,
  ws.status               as session_status,
  ws.challenge_slot,
  se.challenge_item_key,
  ws.is_deload,
  ws.mesocycle_week,
  ws.local_date,
  ws.iso_week,
  ws.engine_version,
  ws.params_hash,
  se.origin               as exercise_origin,
  se.status               as exercise_status,
  se.progression_mode,
  se.user_override,
  s.set_index, s.side, s.set_kind, s.round_index, s.attempt_index,
  (s.set_kind in ('working', 'top', 'backoff', 'amrap')) as is_work_set,
  s.load_mode, s.equipment_id, s.equipment_calibration_rev,
  s.input_value, s.input_unit, s.implement_count, s.load_per_implement_kg, s.implement_base_kg,
  s.added_kg, s.assist_pin_native, s.assist_unit, s.assist_kg, s.assist_ordinal,
  s.bodyweight_kg, s.f_bw, s.effective_load_kg,
  s.reps, s.duration_s, s.hold_reserve_s, s.hold_reserve_is_lower_bound,
  s.ecc_s_target, s.ecc_s_actual,
  s.rir_reported, s.rir_is_lower_bound, s.rir_source, s.calib_extra_reps,
  s.flags, s.deviation_reason_code, s.pain_flag,
  s.planned_reps, s.planned_rep_min, s.planned_rep_max, s.planned_rir,
  s.planned_input_value, s.planned_input_unit, s.planned_effective_load_kg,
  s.planned_duration_s, s.planned_source,
  s.rest_timer_planned_s, s.rest_adjust_s, s.rest_timer_overridden, s.rest_before_s, s.rest_source, s.rest_end_reason,
  s.started_at, s.completed_at
from fitness.set_logs s
join fitness.session_exercises se on se.id = s.session_exercise_id and se.user_id = s.user_id
join fitness.session_blocks sb    on sb.id = se.block_id and sb.user_id = s.user_id
join fitness.workout_sessions ws  on ws.id = s.session_id and ws.user_id = s.user_id
join fitness.exercises e          on e.id = s.exercise_id
where s.deleted_at is null
  and se.deleted_at is null
  and sb.deleted_at is null
  and ws.deleted_at is null;

-- ---------------------------------------------------------------------------------------------------
-- 14.5 v_set_estimates: per-set SSE-1 estimates from the newest estimation.sse snapshot listing the set
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_set_estimates with (security_invoker = true) as
with est as (
  select distinct on (r.user_id, (x.value->>'set_id')::uuid)
    r.user_id,
    (x.value->>'set_id')::uuid as set_id,
    r.id             as sse_recommendation_id,
    r.module_version as sse_version,
    r.engine_version,
    r.params_hash    as sse_params_hash,
    r.created_at     as estimated_at,
    x.value          as est
  from fitness.recommendations r
  cross join lateral jsonb_array_elements(r.outputs->'sets') as x(value)
  where r.kind = 'estimation.sse'
  order by r.user_id, (x.value->>'set_id')::uuid, r.created_at desc, r.id desc
)
select
  f.user_id, f.set_id, f.session_id, f.exercise_id, f.exercise_slug, f.side, f.set_kind,
  f.local_date, f.iso_week, f.load_mode, f.effective_load_kg, f.bodyweight_kg,
  f.reps, f.rir_reported, f.rir_is_lower_bound, f.rir_source,
  est.sse_recommendation_id, est.sse_version, est.engine_version, est.sse_params_hash, est.estimated_at,
  est.est->>'estimator_class'                          as estimator_class,
  (est.est->>'q_true')::numeric                        as q_true,
  (est.est->>'q_true')::numeric - f.rir_reported       as q_minus_reported,
  (est.est->>'n_eff')::numeric                         as n_eff,
  (est.est->>'n_fresh')::numeric                       as n_fresh,
  (est.est->>'fatigue_f')::numeric                     as fatigue_f,
  (est.est->>'z')::numeric                             as z,
  (est.est->>'s')::numeric                             as s,
  (est.est->>'e1rm_kg')::numeric                       as e1rm_kg,
  (est.est->>'e1rm_added_kg')::numeric                 as e1rm_added_kg,
  (est.est->>'k_used')::numeric                        as k_used,
  (est.est->>'residual')::numeric                      as residual
from est
join fitness_review.v_set_facts f on f.set_id = est.set_id and f.user_id = est.user_id;

-- ---------------------------------------------------------------------------------------------------
-- 14.6 v_e1rm_history: per user, exercise, side and local date with at least one SSE-1 estimate
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_e1rm_history with (security_invoker = true) as
with per_day as (
  select user_id, exercise_id, exercise_slug, side, local_date, iso_week,
         count(*)                                                        as n_sets,
         max(e1rm_kg)                                                    as best_set_e1rm_kg,
         max(e1rm_added_kg)                                              as best_set_e1rm_added_kg,
         (array_agg(k_used      order by estimated_at desc, set_id desc))[1] as k_used_last,
         (array_agg(sse_version order by estimated_at desc, set_id desc))[1] as sse_version
  from fitness_review.v_set_estimates
  group by user_id, exercise_id, exercise_slug, side, local_date, iso_week
),
state as (
  select distinct on (r.user_id, ws.local_date, (st.value->>'exercise_id')::uuid, st.value->>'side')
    r.user_id,
    ws.local_date,
    (st.value->>'exercise_id')::uuid      as exercise_id,
    st.value->>'side'                     as side,
    (st.value->>'m_kg')::numeric          as state_m_kg,
    (st.value->>'ci80_lo_kg')::numeric    as state_ci80_lo_kg,
    (st.value->>'ci80_hi_kg')::numeric    as state_ci80_hi_kg,
    (st.value->>'g_per_week')::numeric    as state_g_per_week
  from fitness.recommendations r
  join fitness.workout_sessions ws on ws.id = r.session_id and ws.user_id = r.user_id and ws.deleted_at is null
  cross join lateral jsonb_array_elements(r.outputs->'states') as st(value)
  where r.kind = 'estimation.sse'
  order by r.user_id, ws.local_date, (st.value->>'exercise_id')::uuid, st.value->>'side', r.created_at desc, r.id desc
)
select d.*, s.state_m_kg, s.state_ci80_lo_kg, s.state_ci80_hi_kg, s.state_g_per_week
from per_day d
left join state s
  on s.user_id = d.user_id and s.exercise_id = d.exercise_id and s.side = d.side and s.local_date = d.local_date;

-- ---------------------------------------------------------------------------------------------------
-- 14.7 v_prescription_error: non-warm-up sets next to the newest rx.prescription of their session exercise
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_prescription_error with (security_invoker = true) as
select
  f.user_id, f.set_id, f.session_id, f.session_exercise_id, f.exercise_id, f.exercise_slug,
  f.local_date, f.iso_week, f.set_index, f.side, f.set_kind, f.is_work_set,
  f.progression_mode, f.challenge_item_key, f.user_override,
  rx.id                                       as rx_recommendation_id,
  rx.module_id                                as rx_module_id,
  rx.module_version                           as rx_module_version,
  rx.params_hash                              as rx_params_hash,
  rx.outputs->>'mode'                         as rx_mode,
  rx.outputs->'caps_applied'                  as rx_caps_applied,
  (rs.value->>'target_reps')::numeric         as rx_target_reps,
  (rs.value->>'rir')::numeric                 as rx_rir,
  (rs.value->>'effective_load_kg')::numeric   as rx_effective_load_kg,
  f.planned_source,
  f.planned_reps, f.planned_rep_min, f.planned_rep_max, f.planned_rir, f.planned_effective_load_kg,
  f.reps, f.rir_reported, f.rir_is_lower_bound, f.effective_load_kg,
  f.reps - f.planned_reps                                    as rep_err,
  f.rir_reported - f.planned_rir                             as rir_err,
  f.effective_load_kg / nullif(f.planned_effective_load_kg, 0) as load_ratio,
  f.deviation_reason_code, f.rest_adjust_s
from fitness_review.v_set_facts f
left join lateral (
  select r.*
  from fitness.recommendations r
  where r.user_id = f.user_id
    and r.kind = 'rx.prescription'
    and r.scope_key = 'session_exercise:' || f.session_exercise_id::text
  order by r.created_at desc, r.id desc
  limit 1
) rx on true
left join lateral (
  select x.value
  from jsonb_array_elements(rx.outputs->'sets') as x(value)
  where (x.value->>'set_index')::int = f.set_index and x.value->>'side' = f.side
  limit 1
) rs on true
where f.set_kind <> 'warmup';

-- ---------------------------------------------------------------------------------------------------
-- 14.8 v_rir_calibration: one row per RIRCAL-1 update (C17)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_rir_calibration with (security_invoker = true) as
select
  r.user_id,
  r.id               as recommendation_id,
  r.module_version   as rircal_version,
  r.params_hash,
  r.created_at,
  r.set_log_id,
  f.exercise_slug, f.set_kind, f.challenge_item_key, f.safety_class, f.local_date,
  f.reps, f.rir_reported, f.calib_extra_reps, f.effective_load_kg,
  (r.outputs->>'b_before')::numeric    as b_before,
  (r.outputs->>'b_after')::numeric     as b_after,
  (r.outputs->>'s_q_before')::numeric  as s_q_before,
  (r.outputs->>'s_q_after')::numeric   as s_q_after,
  (r.outputs->>'e')::numeric           as e
from fitness.recommendations r
left join fitness_review.v_set_facts f on f.set_id = r.set_log_id and f.user_id = r.user_id
where r.kind = 'estimation.rircal';

-- ---------------------------------------------------------------------------------------------------
-- 14.9 v_override_stats: override rates per ISO week, scope, reason and overridden module version
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_override_stats with (security_invoker = true) as
with ex as (
  select se.user_id, ws.iso_week, se.user_override, se.override_reason_code, se.origin,
         se.substitution_reason_code,
         coalesce(r.module_id, 'none')    as module_id,
         coalesce(r.module_version, '-')  as module_version
  from fitness.session_exercises se
  join fitness.workout_sessions ws
    on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
  left join fitness.recommendations r
    on r.id = se.recommendation_id and r.user_id = se.user_id
  where se.deleted_at is null and se.status <> 'removed'
),
st as (
  select user_id, iso_week, deviation_reason_code, planned_source, rest_adjust_s,
         coalesce(rx_module_id, 'none')   as module_id,
         coalesce(rx_module_version, '-') as module_version
  from fitness_review.v_prescription_error
  where is_work_set
),
x as (
  select user_id, iso_week, 'exercise_override' as scope, override_reason_code as reason_code,
         module_id, module_version, user_override as hit from ex
  union all
  select user_id, iso_week, 'substitution', substitution_reason_code,
         module_id, module_version, origin = 'substitute' from ex
  union all
  select user_id, iso_week, 'set_deviation', deviation_reason_code,
         module_id, module_version, deviation_reason_code is not null from st
  union all
  select user_id, iso_week, 'rest_adjusted', null,
         module_id, module_version, coalesce(rest_adjust_s, 0) <> 0 from st
  union all
  select user_id, iso_week, 'autoreg_applied', null,
         module_id, module_version, coalesce(planned_source = 'autoreg', false) from st
),
basis as (
  select user_id, iso_week, scope, module_id, module_version, count(*) as basis_n
  from x group by 1, 2, 3, 4, 5
),
hits as (
  select user_id, iso_week, scope, reason_code, module_id, module_version, count(*) as n
  from x where hit group by 1, 2, 3, 4, 5, 6
)
select h.user_id, h.iso_week, h.scope, h.reason_code, h.module_id, h.module_version,
       h.n, b.basis_n, round(h.n::numeric / b.basis_n, 4) as rate
from hits h
join basis b using (user_id, iso_week, scope, module_id, module_version);

-- ---------------------------------------------------------------------------------------------------
-- 14.10 v_skip_stats: session-exercise status counts per week, exercise, reason and current preference
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_skip_stats with (security_invoker = true) as
select
  se.user_id, ws.iso_week, se.exercise_id, e.slug as exercise_slug, se.origin, se.status,
  se.status_reason_code,
  coalesce(p.preference, 'neutral') as preference_now,
  count(*)            as n,
  sum(se.defer_count) as defers
from fitness.session_exercises se
join fitness.workout_sessions ws
  on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
join fitness.exercises e on e.id = se.exercise_id
left join fitness.user_exercise_prefs p
  on p.user_id = se.user_id and p.exercise_id = se.exercise_id and p.deleted_at is null
where se.deleted_at is null
group by 1, 2, 3, 4, 5, 6, 7, 8;

-- ---------------------------------------------------------------------------------------------------
-- 14.11 v_weekly_muscle_volume: newest accounting.week (done) next to newest targets.weekly (target)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_weekly_muscle_volume with (security_invoker = true) as
with acc as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.id as accounting_id, r.module_version as setcredit_version,
         r.outputs->'groups' as groups
  from fitness.recommendations r
  where r.kind = 'accounting.week' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
tgt as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.id as targets_id, r.module_version as targets_version,
         r.outputs->'groups' as groups
  from fitness.recommendations r
  where r.kind = 'targets.weekly' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
wk as (
  select coalesce(a.user_id, t.user_id) as user_id,
         coalesce(a.iso_week, t.iso_week) as iso_week,
         a.accounting_id, a.setcredit_version, coalesce(a.groups, '{}'::jsonb) as acc_groups,
         t.targets_id, t.targets_version, coalesce(t.groups, '{}'::jsonb) as tgt_groups
  from acc a
  full join tgt t on t.user_id = a.user_id and t.iso_week = a.iso_week
)
select
  wk.user_id, wk.iso_week, g.group_id, mg.family,
  (wk.acc_groups->g.group_id->>'vhyp_done')::numeric    as vhyp_done,
  (wk.acc_groups->g.group_id->>'vstr_done')::numeric    as vstr_done,
  (wk.acc_groups->g.group_id->>'fatigue_done')::numeric as fatigue_done,
  (wk.acc_groups->g.group_id->>'committed')::numeric    as committed,
  wk.tgt_groups->g.group_id->>'goal'                    as goal,
  (wk.tgt_groups->g.group_id->>'target')::numeric       as target,
  (wk.tgt_groups->g.group_id->>'mrv')::numeric          as mrv,
  (wk.tgt_groups->g.group_id->>'cap_s')::numeric        as cap_s,
  wk.tgt_groups->g.group_id->'covered_by_challenge'     as covered_by_challenge,
  wk.tgt_groups->g.group_id->'reentry'                  as reentry,
  wk.tgt_groups->g.group_id->'deload'                   as deload,
  wk.setcredit_version, wk.targets_version, wk.accounting_id, wk.targets_id
from wk
cross join lateral (
  select k as group_id from jsonb_object_keys(wk.acc_groups) as k
  union
  select k from jsonb_object_keys(wk.tgt_groups) as k
) g
left join fitness.muscle_groups mg on mg.id = g.group_id;

-- ---------------------------------------------------------------------------------------------------
-- 14.12 v_rest_compliance (rest taken versus timer, C18) and v_rest_alarm_latency (alarm delivery, C28)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_rest_compliance with (security_invoker = true) as
select
  f.user_id, f.set_id, f.session_id, f.exercise_id, f.exercise_slug, f.rest_class, f.set_kind,
  f.local_date, f.iso_week, f.challenge_item_key,
  f.rest_timer_planned_s, f.rest_adjust_s, f.rest_timer_overridden,
  f.rest_timer_planned_s + coalesce(f.rest_adjust_s, 0)                                 as rest_timer_s,
  f.rest_before_s, f.rest_source, f.rest_end_reason,
  f.rest_before_s / nullif(f.rest_timer_planned_s + coalesce(f.rest_adjust_s, 0), 0)     as rest_ratio,
  f.reps, f.planned_reps, f.rir_reported, f.planned_rir
from fitness_review.v_set_facts f
where f.rest_timer_planned_s is not null;

create view fitness_review.v_rest_alarm_latency with (security_invoker = true) as
select
  e.user_id,
  to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
  e.payload->>'app_state'                     as app_state,
  (e.payload->>'screen_interactive')::boolean as screen_interactive,
  e.payload->>'alarm_mode'                    as alarm_mode,
  e.payload->>'platform_config_hash'          as platform_config_hash,
  count(*)                                                                         as n,
  percentile_cont(0.50) within group (order by (e.payload->>'latency_ms')::numeric) as p50_ms,
  percentile_cont(0.95) within group (order by (e.payload->>'latency_ms')::numeric) as p95_ms,
  max((e.payload->>'latency_ms')::numeric)                                          as max_ms
from fitness.session_events e
where e.event_type = 'rest_alarm_fired' and e.payload ? 'latency_ms'
group by 1, 2, 3, 4, 5, 6;

-- ---------------------------------------------------------------------------------------------------
-- 14.13 v_adherence_week: recommended versus planned versus done per ISO week
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_adherence_week with (security_invoker = true) as
with plan as (
  select distinct on (r.user_id, r.iso_week)
         r.user_id, r.iso_week, r.module_version as weekplan_version,
         (r.outputs->>'recommended_n')::int as recommended_n
  from fitness.recommendations r
  where r.kind = 'weekplan.plan' and r.iso_week is not null
  order by r.user_id, r.iso_week, r.created_at desc, r.id desc
),
cal as (
  select c.user_id, c.iso_week,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.provider_status <> 'cancelled' and c.missing_since is null)        as planned_events,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.linked_session_id is not null)                                      as linked_events,
         count(*) filter (where k.kind in ('challenge_session', 'planned_gym')
                            and c.provider_status <> 'cancelled' and c.missing_since is null
                            and c.ends_at < now() and c.linked_session_id is null)                    as missed_events,
         count(*) filter (where k.kind = 'run')                                                       as runs,
         sum(c.run_minutes) filter (where k.kind = 'run')                                             as run_minutes
  from fitness.calendar_events c
  cross join lateral (select coalesce(c.override_kind, c.classified_kind) as kind) k
  where c.deleted_at is null
  group by 1, 2
),
ses as (
  select s.user_id, s.iso_week,
         count(*) filter (where s.status = 'finished' and s.kind in ('gym', 'test'))                  as gym_done,
         count(*) filter (where s.status = 'finished' and s.challenge_slot is not null)               as challenge_done,
         count(*) filter (where s.status = 'finished' and s.kind = 'home')                            as home_done,
         count(*) filter (where s.status in ('abandoned', 'skipped'))                                 as not_done,
         count(*) filter (where s.status = 'finished' and s.kind in ('gym', 'test')
                            and not exists (select 1 from fitness.calendar_events c
                                            where c.user_id = s.user_id and c.linked_session_id = s.id
                                              and c.deleted_at is null))                              as unplanned_done
  from fitness.workout_sessions s
  where s.deleted_at is null
  group by 1, 2
)
select coalesce(p.user_id, c.user_id, s.user_id)    as user_id,
       coalesce(p.iso_week, c.iso_week, s.iso_week) as iso_week,
       p.weekplan_version, p.recommended_n,
       c.planned_events, c.linked_events, c.missed_events, c.runs, c.run_minutes,
       s.gym_done, s.challenge_done, s.home_done, s.not_done, s.unplanned_done
from plan p
full join cal c on c.user_id = p.user_id and c.iso_week = p.iso_week
full join ses s on s.user_id = coalesce(p.user_id, c.user_id) and s.iso_week = coalesce(p.iso_week, c.iso_week);

-- ---------------------------------------------------------------------------------------------------
-- 14.14 v_recommendation_acceptance: acceptance of Today options, suggestions, warnings, prompts, rules
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_recommendation_acceptance with (security_invoker = true) as
with ev as (
  select e.user_id,
         to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
         e.event_type, e.module_id, e.module_version, e.payload,
         case when e.event_type in ('today_card_shown', 'today_action', 'why_opened') then 'today'
              when e.event_type like 'suggestion\_%' then 'autoreg'
              when e.event_type like 'warning\_%'    then 'warning'
              when e.event_type like 'prompt\_%'     then 'prompt'
              when e.event_type in ('challenge_rule_applied', 'challenge_rule_undone') then 'challenge_rule'
         end as family
  from fitness.session_events e
  where e.category in ('today', 'autoreg', 'warning', 'prompt', 'challenge')
)
select
  user_id, iso_week, family,
  case family when 'today' then '-'
              else coalesce(payload->>'rule_id', payload->>'trigger', payload->>'prompt', '-') end as subject,
  case family when 'warning' then payload->>'severity' end                                         as severity,
  module_id, module_version,
  count(*) filter (where event_type in ('today_card_shown', 'suggestion_shown', 'warning_shown', 'prompt_shown',
                                        'challenge_rule_applied'))                                as shown,
  count(*) filter (where event_type in ('suggestion_applied', 'warning_use_suggested', 'prompt_answered')
                      or (event_type = 'today_action' and payload->>'rank' = '1'))               as accepted,
  count(*) filter (where event_type = 'today_action' and coalesce(payload->>'rank', '1') <> '1') as chose_alternative,
  count(*) filter (where event_type in ('suggestion_kept', 'warning_dismissed', 'prompt_skipped', 'challenge_rule_undone')
                      or (event_type = 'warning_use_mine_toggled' and payload->>'on' = 'true')) as declined,
  count(*) filter (where event_type in ('warning_why_opened', 'why_opened'))                      as why_opened
from ev
where family is not null
group by 1, 2, 3, 4, 5, 6, 7;

-- ---------------------------------------------------------------------------------------------------
-- 14.15 v_session_summary (session outcome) and v_exercise_time (TIME-1 estimate versus actual)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_session_summary with (security_invoker = true) as
select
  ws.user_id, ws.id as session_id, ws.local_date, ws.iso_week, ws.kind, ws.status, ws.template_source,
  ws.challenge_slot, ws.challenge_template_id, ws.enrollment_mode, ws.is_deload, ws.mesocycle_week,
  ws.families, ws.time_budget_min, ws.time_budget_source,
  ws.started_at, ws.finished_at,
  round(extract(epoch from (ws.finished_at - ws.started_at)) / 60.0, 1) as actual_min,
  pl.est_min_total, pl.budget_min_total,
  ws.completion_ratio, ws.bodyweight_kg, ws.bodyweight_source, ws.bodyweight_stale,
  ws.engine_version, ws.params_hash, ws.catalog_version, ws.end_reason_code,
  st.n_sets, st.n_work_sets, st.n_pain_flags,
  w.srpe, w.readiness_level, w.elbow_score
from fitness.workout_sessions ws
left join lateral (
  select sum((r.outputs->>'est_min')::numeric)    as est_min_total,
         sum((r.outputs->>'budget_min')::numeric) as budget_min_total
  from fitness.session_blocks b
  join fitness.recommendations r on r.id = b.recommendation_id and r.user_id = b.user_id
  where b.session_id = ws.id and b.user_id = ws.user_id and b.deleted_at is null
) pl on true
left join lateral (
  select count(*)                                                                    as n_sets,
         count(*) filter (where s.set_kind in ('working', 'top', 'backoff', 'amrap')) as n_work_sets,
         count(*) filter (where s.pain_flag)                                          as n_pain_flags
  from fitness.set_logs s
  where s.session_id = ws.id and s.user_id = ws.user_id and s.deleted_at is null
) st on true
left join lateral (
  select max(wl.srpe)            filter (where wl.kind = 'srpe')        as srpe,
         max(wl.readiness_level) filter (where wl.kind = 'readiness')   as readiness_level,
         max(wl.elbow_score)     filter (where wl.kind = 'elbow_check') as elbow_score
  from fitness.wellness_logs wl
  where wl.session_id = ws.id and wl.user_id = ws.user_id and wl.deleted_at is null
) w on true
where ws.deleted_at is null;

create view fitness_review.v_exercise_time with (security_invoker = true) as
select
  se.user_id, se.session_id, se.id as session_exercise_id, se.exercise_id, e.slug as exercise_slug,
  e.mechanics, ws.local_date, b.block_kind,
  r.kind                                           as plan_kind,
  r.module_version                                 as plan_version,
  (pe.value->>'sets')::int                         as plan_sets,
  (pe.value->>'est_s')::numeric                    as est_s,
  extract(epoch from (se.completed_at - se.started_at)) as actual_s,
  (select count(*) from fitness.set_logs s
    where s.session_exercise_id = se.id and s.user_id = se.user_id and s.deleted_at is null) as n_sets
from fitness.session_exercises se
join fitness.session_blocks b    on b.id = se.block_id and b.user_id = se.user_id and b.deleted_at is null
join fitness.workout_sessions ws on ws.id = se.session_id and ws.user_id = se.user_id and ws.deleted_at is null
join fitness.exercises e         on e.id = se.exercise_id
left join fitness.recommendations r on r.id = b.recommendation_id and r.user_id = b.user_id
left join lateral (
  select x.value from jsonb_array_elements(r.outputs->'exercises') as x(value)
  where (x.value->>'exercise_id')::uuid = se.exercise_id
  limit 1
) pe on true
where se.deleted_at is null and se.status = 'done';

-- ---------------------------------------------------------------------------------------------------
-- 14.16 v_challenge_timeline (ECA audit of the current fold) and v_challenge_var_changes
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_challenge_timeline with (security_invoker = true) as
select
  ce.user_id, ce.enrollment_id, en.definition_slug, en.definition_version,
  ce.occurred_at, ce.local_date, ce.id as challenge_event_id,
  'input'::text      as row_kind,
  ce.event_type      as type,
  ce.dedupe_key, ce.supersedes_event_id, ce.source_session_id,
  null::text as rule_id, null::text as rule_tag, null::text as rule_decision, null::text as stage,
  null::smallint as prio, null::text as outcome, null::boolean as cond_value, null::text as eval_error,
  null::jsonb as applied, null::text as suppressed_by, null::smallint as cascade_depth,
  null::text as derived_event_type, null::int as state_rev_after,
  ce.created_at as logged_at
from fitness.challenge_events ce
join fitness.challenge_enrollments en on en.id = ce.enrollment_id and en.user_id = ce.user_id and en.deleted_at is null
union all
select
  re.user_id, re.enrollment_id, re.definition_slug, re.definition_version,
  ce.occurred_at, ce.local_date, re.challenge_event_id,
  'rule', coalesce(re.derived_event_type, 'rule'),
  null, null, null,
  re.rule_id, re.rule_tag, re.rule_decision, re.stage,
  re.prio, re.outcome, re.cond_value, re.eval_error,
  re.applied, re.suppressed_by, re.cascade_depth,
  re.derived_event_type, re.state_rev_after,
  re.created_at
from fitness.challenge_rule_events re
join fitness.challenge_enrollments en
  on en.id = re.enrollment_id and en.user_id = re.user_id and en.deleted_at is null
 and re.fold_generation = en.fold_generation
join fitness.challenge_events ce on ce.id = re.challenge_event_id and ce.user_id = re.user_id;

create view fitness_review.v_challenge_var_changes with (security_invoker = true) as
select
  t.user_id, t.enrollment_id, t.occurred_at, t.local_date, t.rule_id, t.rule_tag, t.stage,
  v.key                as var,
  v.value->'old'       as old_value,
  v.value->'new'       as new_value,
  t.state_rev_after
from fitness_review.v_challenge_timeline t
cross join lateral jsonb_each(t.applied) as v(key, value)
where t.row_kind = 'rule' and t.applied is not null and t.applied <> '{}'::jsonb;

-- ---------------------------------------------------------------------------------------------------
-- 14.17 v_challenge_tests: test history with validity and the engine's own derivation parameters
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_challenge_tests with (security_invoker = true) as
select
  mo.user_id, mo.id as observation_id, mo.enrollment_id, mo.test_session_id, mo.session_id, mo.set_log_id,
  mo.metric_code, md.better, mo.side, mo.value_num, mo.value_bool, mo.unit,
  mo.observed_at, mo.local_date, mo.source,
  mo.valid_for_gate, mo.validity_note, mo.protocol_code,
  mo.derivation, mo.derived_from_ids,
  mo.calc_params->>'method'                    as calc_method,
  (mo.calc_params->>'k')::numeric              as calc_k,
  (mo.calc_params->>'m3')::numeric             as calc_m3,
  (mo.calc_params->>'multiplier')::numeric     as calc_multiplier,
  (mo.calc_params->>'bw_kg')::numeric          as calc_bw_kg,
  (mo.calc_params->'ci80'->>'lo')::numeric     as ci80_lo,
  (mo.calc_params->'ci80'->>'hi')::numeric     as ci80_hi,
  mo.calc_params->'alternatives'               as alternatives,
  mo.bodyweight_kg, mo.equipment_id, mo.equipment_calibration_rev, mo.attempt_count
from fitness.metric_observations mo
join fitness.metric_defs md on md.code = mo.metric_code
where mo.deleted_at is null
  and (mo.enrollment_id is not null or mo.test_session_id is not null or md.introduced_by like 'challenge:%');

-- ---------------------------------------------------------------------------------------------------
-- 14.18 v_wellness_safety: every live wellness answer (the free-text note is excluded, R-V7)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_wellness_safety with (security_invoker = true) as
select
  wl.user_id, wl.id as wellness_log_id, wl.kind, wl.phase, wl.observed_at, wl.local_date,
  wl.session_id, wl.session_exercise_id, wl.set_log_id, e.slug as exercise_slug,
  wl.readiness_level, wl.elbow_score,
  wl.pain_body_region, wl.pain_side, wl.pain_severity, wl.pain_score, wl.symptom_codes,
  wl.soreness_muscle_group_id, wl.soreness_code, wl.srpe, wl.sleep_hours
from fitness.wellness_logs wl
left join fitness.session_exercises se
  on se.id = wl.session_exercise_id and se.user_id = wl.user_id
left join fitness.exercises e on e.id = se.exercise_id
where wl.deleted_at is null;

-- ---------------------------------------------------------------------------------------------------
-- 14.19 v_calendar_events (stored classified events) and v_calendar_classification (telemetry)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_calendar_events with (security_invoker = true) as
select
  c.user_id, c.id as calendar_event_id, c.instance_key, c.series_key, c.local_date, c.iso_week,
  c.starts_at, c.ends_at, round(extract(epoch from (c.ends_at - c.starts_at)) / 60.0) as minutes,
  c.classified_kind, c.override_kind, coalesce(c.override_kind, c.classified_kind) as effective_kind,
  c.challenge_slot, c.override_slot, coalesce(c.override_slot, c.challenge_slot) as effective_slot,
  c.title, c.location,
  c.match_method, c.score, c.features, c.classifier_version,
  c.provider_status, c.app_written, (c.marker is not null) as has_marker, c.adopted_at,
  c.write_state, c.attention, c.linked_session_id, c.link_method, c.missing_since
from fitness.calendar_events c
where c.deleted_at is null;

create view fitness_review.v_calendar_classification with (security_invoker = true) as
select
  e.user_id,
  to_char((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min), 'IYYY-"W"IW') as iso_week,
  e.payload->>'classifier_version' as classifier_version,
  e.payload->>'kind'               as kind,
  e.payload->>'method'             as method,
  count(*)                                   as n,
  count(distinct e.payload->>'instance_key') as n_instances,
  avg((e.payload->>'score')::numeric)        as mean_score,
  min((e.payload->>'score')::numeric)        as min_score
from fitness.session_events e
where e.event_type = 'calendar_classified'
group by 1, 2, 3, 4, 5;

-- ---------------------------------------------------------------------------------------------------
-- 14.20 v_data_quality: one row per finding (DQ01-DQ17). Deviation: user_id is the first column (R-V3).
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_data_quality with (security_invoker = true) as
-- DQ01: unregistered module versions
select r.user_id, 'DQ01'::text as check_code, 'warn'::text as severity,
       'module_version'::text as entity, r.module_id || '@' || r.module_version as entity_id,
       jsonb_build_object('count', count(*), 'first_seen', min(r.created_at), 'last_seen', max(r.created_at)) as detail
from fitness.recommendations r
where not exists (select 1 from fitness.algorithm_versions v
                  where v.module_id = r.module_id and v.version = r.module_version)
group by r.user_id, r.module_id, r.module_version
union all
select m.user_id, 'DQ01', 'warn', 'module_version', m.model || '@' || m.module_version,
       jsonb_build_object('count', count(*), 'source', 'exercise_model_state')
from fitness.exercise_model_state m
where m.deleted_at is null
  and not exists (select 1 from fitness.algorithm_versions v
                  where v.module_id = m.model and v.version = m.module_version)
group by m.user_id, m.model, m.module_version
union all
-- DQ02: unregistered telemetry types
select e.user_id, 'DQ02', 'info', 'event_type', e.event_type, jsonb_build_object('count', count(*))
from fitness.session_events e
where not exists (select 1 from fitness.session_event_types t where t.event_type = e.event_type)
group by e.user_id, e.event_type
union all
-- DQ03: metric unit mismatch
select mo.user_id, 'DQ03', 'error', 'metric_observation', mo.id::text,
       jsonb_build_object('metric', mo.metric_code, 'unit', mo.unit, 'expected', md.unit)
from fitness.metric_observations mo
join fitness.metric_defs md on md.code = mo.metric_code
where mo.deleted_at is null and mo.unit <> md.unit
union all
-- DQ04: unknown station keys (global exercises have user_id null)
select ex.user_id, 'DQ04', 'warn', 'exercise', ex.slug, jsonb_build_object('station', st.key)
from fitness.exercises ex
cross join lateral unnest(ex.requires_station) as st(key)
where ex.deleted_at is null
  and not exists (select 1 from fitness.equipment_types t where t.key = st.key)
union all
-- DQ05: dynamic work sets without effort, per week
select f.user_id, 'DQ05', 'info', 'iso_week', f.iso_week, jsonb_build_object('count', count(*))
from fitness_review.v_set_facts f
where f.is_work_set and f.contraction = 'dynamic' and f.rir_source = 'missing'
group by f.user_id, f.iso_week
union all
-- DQ06: missing engine-written effective load
select s.user_id, 'DQ06', 'error', 'set', s.id::text, jsonb_build_object('load_mode', s.load_mode)
from fitness.set_logs s
where s.deleted_at is null and s.effective_load_kg is null
  and s.load_mode <> 'assisted_ordinal' and s.reps is not null
union all
-- DQ07: finished session without sets
select ws.user_id, 'DQ07', 'info', 'session', ws.id::text, jsonb_build_object('local_date', ws.local_date)
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.status = 'finished' and ws.template_source <> 'import'
  and not exists (select 1 from fitness.set_logs s
                  where s.session_id = ws.id and s.user_id = ws.user_id and s.deleted_at is null)
union all
-- DQ08: stale in-progress session
select ws.user_id, 'DQ08', 'warn', 'session', ws.id::text, jsonb_build_object('started_at', ws.started_at)
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.status = 'in_progress' and ws.started_at < now() - interval '12 hours'
union all
-- DQ09: fold count versus stored events
select en.user_id, 'DQ09', 'info', 'enrollment', en.id::text,
       jsonb_build_object('state_rev', en.state_rev, 'events', n.cnt)
from fitness.challenge_enrollments en
cross join lateral (select count(*) as cnt from fitness.challenge_events ce
                    where ce.enrollment_id = en.id and ce.user_id = en.user_id) n
where en.deleted_at is null and en.state_rev <> n.cnt
union all
-- DQ10: missing params snapshot for a session
select ws.user_id, 'DQ10', 'warn', 'session', ws.id::text, jsonb_build_object('params_hash', ws.params_hash)
from fitness.workout_sessions ws
where ws.deleted_at is null
  and not exists (select 1 from fitness.params_snapshots p
                  where p.user_id = ws.user_id and p.params_hash = ws.params_hash)
union all
-- DQ11: inputs pruned before the retention age
select r.user_id, 'DQ11', 'error', 'recommendation', r.id::text,
       jsonb_build_object('created_at', r.created_at, 'pruned_at', r.inputs_pruned_at)
from fitness.recommendations r
where r.inputs is null and r.created_at > now() - interval '730 days'
union all
-- DQ12: SSE-1 snapshot referencing an unknown set
select r.user_id, 'DQ12', 'warn', 'recommendation', r.id::text, jsonb_build_object('set_id', x.value->>'set_id')
from fitness.recommendations r
cross join lateral jsonb_array_elements(r.outputs->'sets') as x(value)
where r.kind = 'estimation.sse'
  and not exists (select 1 from fitness.set_logs s
                  where s.id = (x.value->>'set_id')::uuid and s.user_id = r.user_id)
union all
-- DQ13: possible free text in telemetry payloads
select e.user_id, 'DQ13', 'error', 'session_event', e.id::text, jsonb_build_object('event_type', e.event_type)
from fitness.session_events e
where jsonb_path_exists(e.payload, '$.** ? (@.type() == "string" && @ like_regex "[[:space:]]|.{65}")')
union all
-- DQ14: set outside its session window
select s.user_id, 'DQ14', 'warn', 'set', s.id::text,
       jsonb_build_object('completed_at', s.completed_at, 'started_at', ws.started_at, 'finished_at', ws.finished_at)
from fitness.set_logs s
join fitness.workout_sessions ws on ws.id = s.session_id and ws.user_id = s.user_id
where s.deleted_at is null and ws.deleted_at is null
  and (s.completed_at < ws.started_at - interval '1 hour' or s.completed_at > ws.finished_at + interval '1 hour')
union all
-- DQ15: per-user override for an unknown key
select u.user_id, 'DQ15', 'error', 'user_algo_param', u.param_key, jsonb_build_object('valid_from', u.valid_from)
from fitness.user_algo_params u
where u.deleted_at is null
  and not exists (select 1 from fitness.algo_params a where a.status = 'active' and a.params ? u.param_key)
union all
-- DQ16: stale bodyweight at session start, per week
select ws.user_id, 'DQ16', 'info', 'iso_week', ws.iso_week, jsonb_build_object('sessions', count(*))
from fitness.workout_sessions ws
where ws.deleted_at is null and ws.bodyweight_stale
group by ws.user_id, ws.iso_week
union all
-- DQ17: classifier features that are not codes (only the length is reported, never the text)
select c.user_id, 'DQ17', 'warn', 'calendar_event', c.id::text, jsonb_build_object('feature_length', length(f.feature))
from fitness.calendar_events c
cross join lateral unnest(c.features) as f(feature)
where c.deleted_at is null and f.feature !~ '^[a-z0-9_.:]{1,60}$';

-- ---------------------------------------------------------------------------------------------------
-- 14.21 v_sync_health (sync telemetry) and v_upload_latency (last local edit to server write)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_sync_health with (security_invoker = true) as
select
  e.user_id,
  ((e.occurred_at at time zone 'UTC') + make_interval(mins => e.tz_offset_min))::date as local_date,
  e.event_type,
  coalesce(e.payload->>'pg_code', e.payload->>'pgrst_code', e.payload->>'http_status',
           e.payload->>'trigger', '-')                                             as code,
  count(*)                                                                         as n,
  sum((e.payload->>'pushed')::int)                                                 as pushed,
  sum((e.payload->>'pulled')::int)                                                 as pulled,
  max((e.payload->>'outbox_after')::int)                                           as max_outbox_after,
  percentile_cont(0.95) within group (order by (e.payload->>'duration_ms')::numeric) as p95_duration_ms
from fitness.session_events e
where e.category = 'sync'
group by 1, 2, 3, 4;

create view fitness_review.v_upload_latency with (security_invoker = true) as
select
  s.user_id,
  (s.completed_at at time zone 'Europe/Amsterdam')::date                                         as local_date_ams,
  count(*)                                                                                        as n_sets,
  percentile_cont(0.50) within group (order by extract(epoch from (s.server_updated_at - s.client_updated_at))) as p50_s,
  percentile_cont(0.95) within group (order by extract(epoch from (s.server_updated_at - s.client_updated_at))) as p95_s,
  min(extract(epoch from (s.server_updated_at - s.client_updated_at)))                            as min_s
from fitness.set_logs s
where s.deleted_at is null
group by 1, 2;

-- ---------------------------------------------------------------------------------------------------
-- 14.22 v_db_size and v_db_size_summary: the size guard of 15.2 (no user_id; catalog functions only)
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_db_size with (security_invoker = true) as
select
  n.nspname                                           as schema_name,
  c.relname                                           as table_name,
  pg_total_relation_size(c.oid)                       as total_bytes,
  pg_relation_size(c.oid)                             as heap_bytes,
  pg_indexes_size(c.oid)                              as index_bytes,
  coalesce(pg_total_relation_size(nullif(c.reltoastrelid, 0)), 0) as toast_bytes,
  nullif(c.reltuples, -1)::bigint                     as est_rows
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname in ('fitness', 'fitness_private') and c.relkind in ('r', 'p');

create view fitness_review.v_db_size_summary with (security_invoker = true) as
with s as (
  select pg_database_size(current_database())                       as db_bytes,
         (select coalesce(sum(total_bytes), 0) from fitness_review.v_db_size) as fitness_bytes
)
select
  round(db_bytes / 1048576.0, 1)                       as db_mb,
  round(fitness_bytes / 1048576.0, 1)                  as fitness_mb,
  round((db_bytes - fitness_bytes) / 1048576.0, 1)     as other_mb,
  round(100.0 * db_bytes / (500 * 1048576.0), 1)       as pct_of_free_limit,
  case when db_bytes      >= 425 * 1048576.0 then 'critical'
       when db_bytes      >= 350 * 1048576.0 then 'warn'
       when fitness_bytes >= 150 * 1048576.0 then 'warn'
       else 'ok' end                                   as status,
  now()                                                as measured_at
from s;

-- ---------------------------------------------------------------------------------------------------
-- 14.23 v_replay_index: snapshots selectable for :sim replay and counterfactuals
-- ---------------------------------------------------------------------------------------------------
create view fitness_review.v_replay_index with (security_invoker = true) as
select
  r.user_id, r.id as recommendation_id, r.kind, r.module_id, r.module_version, r.engine_version,
  r.params_hash, (p.id is not null) as params_available,
  r.catalog_version, r.challenge_def_sha256, r.inputs_hash,
  (r.inputs is not null) as inputs_available, r.inputs_bytes, r.inputs_pruned_at,
  r.scope_key, r.scope_date, r.iso_week, r.session_id, r.session_exercise_id, r.enrollment_id,
  r.supersedes_id, r.created_at,
  exists (select 1 from fitness.algorithm_versions v
          where v.module_id = r.module_id and v.version = r.module_version) as version_registered
from fitness.recommendations r
left join fitness.params_snapshots p on p.user_id = r.user_id and p.params_hash = r.params_hash;

-- ---------------------------------------------------------------------------------------------------
-- Comments
-- ---------------------------------------------------------------------------------------------------
comment on view fitness_review.v_set_facts is 'Live sets with session, block and exercise context; facts and engine-written columns only (14.4).';
comment on view fitness_review.v_set_estimates is 'Per-set SSE-1 estimates from the newest estimation.sse snapshot (14.5, C3).';
comment on view fitness_review.v_e1rm_history is 'Best logged per-set e1RM, last k_used and logged state M/CI80 per user, exercise, side, date (14.6).';
comment on view fitness_review.v_prescription_error is 'Planned versus actual per non-warm-up set next to its newest rx.prescription (14.7).';
comment on view fitness_review.v_rir_calibration is 'One row per RIRCAL-1 update: bias b trajectory (14.8, C17).';
comment on view fitness_review.v_override_stats is 'Override, substitution, deviation, rest-adjust and autoreg rates per week and module version (14.9).';
comment on view fitness_review.v_skip_stats is 'Session-exercise status counts per week, exercise, reason and current preference (14.10).';
comment on view fitness_review.v_weekly_muscle_volume is 'Logged done versus target per ISO week and muscle group; no set credit recomputed (14.11, C14).';
comment on view fitness_review.v_rest_compliance is 'Rest taken versus the rest timer per set (14.12, C18).';
comment on view fitness_review.v_rest_alarm_latency is 'Rest alarm delivery latency per week, app state and alarm mode (14.12, C28).';
comment on view fitness_review.v_adherence_week is 'Recommended versus planned versus done sessions per ISO week (14.13).';
comment on view fitness_review.v_recommendation_acceptance is 'Acceptance of Today options, suggestions, warnings, prompts and challenge rules (14.14).';
comment on view fitness_review.v_session_summary is 'Session outcome with block plan estimate, set counts and wellness answers (14.15).';
comment on view fitness_review.v_exercise_time is 'TIME-1 estimate versus actual duration per done session exercise (14.15, C19).';
comment on view fitness_review.v_challenge_timeline is 'Input and rule events of the current fold of each enrollment (14.16, C21).';
comment on view fitness_review.v_challenge_var_changes is 'One row per challenge variable changed by an applied rule (14.16).';
comment on view fitness_review.v_challenge_tests is 'Challenge and test metric observations with validity and logged derivation params (14.17, D7).';
comment on view fitness_review.v_wellness_safety is 'Live wellness answers without free text (14.18).';
comment on view fitness_review.v_calendar_events is 'Stored classified calendar events (14.19, C20; title/location allowed by R-V7).';
comment on view fitness_review.v_calendar_classification is 'calendar_classified telemetry per week, kind and method (14.19).';
comment on view fitness_review.v_data_quality is 'Data-quality findings DQ01-DQ17, one row per finding (14.20).';
comment on view fitness_review.v_sync_health is 'Sync telemetry per local date, event type and code (14.21).';
comment on view fitness_review.v_upload_latency is 'Delay from the last local edit to the server write per Amsterdam date (14.21, 11.12).';
comment on view fitness_review.v_db_size is 'Size of every fitness and fitness_private table (14.22, 15.2).';
comment on view fitness_review.v_db_size_summary is 'Database size guard status against the free-plan limits (14.22, 15.2).';
comment on view fitness_review.v_replay_index is 'Snapshots selectable for :sim replay with params, inputs and version availability (14.23).';

-- R-V2: no grants. fitness_review has no default privileges, so the views are created without any grant;
-- this idempotent revoke only makes that explicit.
revoke all on all tables in schema fitness_review from public, anon, authenticated, service_role;

-- Manual rollback (only with explicit user approval), reverse creation order:
--   drop view fitness_review.v_replay_index;
--   drop view fitness_review.v_db_size_summary;
--   drop view fitness_review.v_db_size;
--   drop view fitness_review.v_upload_latency;
--   drop view fitness_review.v_sync_health;
--   drop view fitness_review.v_data_quality;
--   drop view fitness_review.v_calendar_classification;
--   drop view fitness_review.v_calendar_events;
--   drop view fitness_review.v_wellness_safety;
--   drop view fitness_review.v_challenge_tests;
--   drop view fitness_review.v_challenge_var_changes;
--   drop view fitness_review.v_challenge_timeline;
--   drop view fitness_review.v_exercise_time;
--   drop view fitness_review.v_session_summary;
--   drop view fitness_review.v_recommendation_acceptance;
--   drop view fitness_review.v_adherence_week;
--   drop view fitness_review.v_rest_alarm_latency;
--   drop view fitness_review.v_rest_compliance;
--   drop view fitness_review.v_weekly_muscle_volume;
--   drop view fitness_review.v_skip_stats;
--   drop view fitness_review.v_override_stats;
--   drop view fitness_review.v_rir_calibration;
--   drop view fitness_review.v_prescription_error;
--   drop view fitness_review.v_e1rm_history;
--   drop view fitness_review.v_set_estimates;
--   drop view fitness_review.v_set_facts;
