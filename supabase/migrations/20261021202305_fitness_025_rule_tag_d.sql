-- fitness_025_rule_tag_d (M2b.4, schema): the rule log accepts the definition tag D — DATA_MODEL.md 9.7.2, 13.2
--
-- Definition rules tagged D are fixed by docs/DECISIONS.md (for example data.test.e1rm, D7). The first test day
-- (2026-10-21) wrote such an audit row and the old check (A, P, A+P, E) rejected it on sync. No data changes.
alter table fitness.challenge_rule_events
  drop constraint challenge_rule_events_rule_tag_check;
alter table fitness.challenge_rule_events
  add constraint challenge_rule_events_rule_tag_check
  check (rule_tag in ('A', 'P', 'A+P', 'D', 'E'));
