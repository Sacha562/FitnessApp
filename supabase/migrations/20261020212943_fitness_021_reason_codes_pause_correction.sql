-- fitness_021_reason_codes_pause_correction (D34, DATA_MODEL 9.1.3)
-- Pause reasons (UX 14.7) and the reasons of Correct a value get their own codes; until now they were stored as
-- the nearest existing code. Two new chip contexts, five new codes, three existing codes join the new contexts.
-- Reference data of the fitness schema only; idempotent.

alter table fitness.reason_codes drop constraint reason_codes_ui_contexts_check;
alter table fitness.reason_codes add constraint reason_codes_ui_contexts_check
  check (ui_contexts <@ array['easier', 'harder', 'skip', 'session_end', 'substitute', 'pause', 'correction']::text[]);

insert into fitness.reason_codes (code, label_en, label_nl, ui_contexts, is_safety, implies_flag, active, sort)
values
  ('too_easy', 'Too easy', 'Te makkelijk', array['easier', 'correction']::text[], false, null, true, 10),
  ('too_hard', 'Too hard', 'Te zwaar', array['harder', 'correction']::text[], false, null, true, 20),
  ('other', 'Other', 'Anders', array['easier', 'harder', 'skip', 'session_end', 'pause', 'correction']::text[], false, null, true, 130),
  ('holiday', 'Holiday', 'Vakantie', array['pause']::text[], false, null, true, 140),
  ('illness', 'Illness', 'Ziek', array['pause']::text[], false, null, true, 150),
  ('injury', 'Injury', 'Blessure', array['pause']::text[], false, null, true, 160),
  ('loaded_different', 'Loaded a different weight', 'Ander gewicht geladen', array['correction']::text[], false, null, true, 170),
  ('value_wrong', 'The value was wrong', 'Waarde klopte niet', array['correction']::text[], false, null, true, 180)
on conflict (code) do update
  set label_en = excluded.label_en,
      label_nl = excluded.label_nl,
      ui_contexts = excluded.ui_contexts,
      is_safety = excluded.is_safety,
      implies_flag = excluded.implies_flag,
      active = excluded.active,
      sort = excluded.sort
  where (fitness.reason_codes.label_en, fitness.reason_codes.label_nl, fitness.reason_codes.ui_contexts, fitness.reason_codes.is_safety, fitness.reason_codes.implies_flag, fitness.reason_codes.active, fitness.reason_codes.sort)
        is distinct from
        (excluded.label_en, excluded.label_nl, excluded.ui_contexts, excluded.is_safety, excluded.implies_flag, excluded.active, excluded.sort);
