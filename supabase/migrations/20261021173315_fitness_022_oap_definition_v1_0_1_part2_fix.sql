-- fitness_022_oap_definition_v1_0_1_part2_fix (M2b, data): repairs the staged text of part 2.
--
-- Part 2 was applied with its last 555 characters mistyped (the staged chunk ended in 68 wrong characters;
-- the first 29445 matched the generated file). This replaces the tail with the generated text and checks
-- the prefix before and the whole chunk after against the md5 of the generated part 2. Staging only; nothing
-- is published until part 5, which checks the assembled definition's jcs_sha256 and server checksum.

do $$
declare
  good text := 's.fingerLevel"},"h90":{"exercise":"oa_hold_angle","angles":["top","90","120"],"sets":2,"hold_s":[5,8],"perSide":true,"sideOrder":["L","R"],"creditRir":2,"rest_s":90,"progression":"none"},"cal":{"exercise":"oa_negative","sets":2,"reps":1,"perSide":true,"sideOrder":["L","R"],"ecc_s_target":5,"records":"oa_neg5_unassisted","validForGate":false,"skipIf":"s.elbowTrace","creditRir":2,"rest_s":120}}},"A3":{"extends":"A2","slot":"A","tag":"P","proposal":"P18","prepend":["attempts"],"override":{"wpu":{"sets":2}}},"B3":{"extends":"B1","slot":"B","tag":"P","pr';
  cur text;
begin
  select txt into cur from fitness_private.challenge_definition_staging where part = 2;
  if md5(left(cur, 29445)) <> 'd0e5639abb3afef91f586f1a920e5c9e' then
    raise exception '022 fix: staged part 2 prefix differs (md5 %)', md5(left(cur, 29445));
  end if;
  update fitness_private.challenge_definition_staging set txt = left(cur, 29445) || good where part = 2;
  select txt into cur from fitness_private.challenge_definition_staging where part = 2;
  if length(cur) <> 30000 or md5(cur) <> '3199b65b08b5087886ce7930f36283b9' then
    raise exception '022 fix: part 2 still differs (length %, md5 %)', length(cur), md5(cur);
  end if;
end;
$$;
