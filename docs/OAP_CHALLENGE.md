# One-arm pull-up challenge: the program as the app will run it

Version of this document: 1.1 (2026-10-07; aligned with DECISIONS D13-D16, the corrected D8 wording,
the revised F11, F21-F27 and the cross-document resolutions C37-C65). Definition:
`docs/challenges/oap.challenge.v1.json` (`oap@1.10.0`, schema `fitapp.challenge/1`; 1.0.1 is a text-only patch of
1.0.0 with shorter user-facing texts (D35), 1.1.0 adds P21's protocols as structured `stepsIfApproved` with five params
(D36), 1.2.0 lets the first valid test fill empty loads, negatives and pins (engineering rules `data.test.first*`, param
`firstAnchorRir`) and initialises the gate e1RM from the entered 3RM (D37), 1.3.0 lets the test fall on any day and puts it
first in its week while there are no loads (D38), 1.4.0 is the cutover version with the Pallof press, P28 and P29 (D40),
1.5.0 drops the rowing machine from the warm-up (D44), 1.6.0 widens the elbow trace to the back of the elbow and the
forearm and makes the Pallof press per side (D45), 1.7.0 keeps the prehab sets in deload weeks (D47), 1.8.0 skips the daily hang on every gym day (D51), 1.9.0 gives the torso-rotation notice one message per exercise (D52), 1.10.0 keeps the re-entry pin offset out of pinA and pinC (D63); the frozen
1.0.0 to 1.9.0 are `oap.challenge.v1.0.0.json` to `oap.challenge.v1.9.0.json`). The proposal
rationales quoted in section 6 are the 1.0.0 wording; the app shows the shorter 1.0.1 texts.
Engine: `docs/CHALLENGE_ENGINE.md`. Decisions: `docs/DECISIONS.md` (cited by id, for example "D7").

This document is for you to read and approve. It describes your one-arm pull-up program ("Van
twaalf naar één") exactly as the app will execute it once you switch over (D4), shows where every
rule comes from, and lists every proposal the app makes on top of your program. Each proposal has a
switch. Setup turns on every proposal the definition recommends (D35, today all 27); you can switch
any of them off in setup ("Customize") or later on the Proposals screen (D8).

---

## 0. How to read this document

| Tag | Meaning |
|---|---|
| **A** | Your program as written: the artifact (updated 2026-10-07) or the Todoist task descriptions (read 2026-10-07; DECISIONS section 4). Executed exactly as written. Where an A rule has a P replacement, the A rule runs unless you approve the replacement (D8, C48). The app's generic load-jump caps do not stop your rules; a bigger step shows a warning instead (D13, section 4.9). |
| **P** | A proposal from the app design. Runs only after you approve it; an undecided proposal is inactive. Your decision is stored per proposal, so approving one switches every rule it lists (C48). Each has a one-line rationale, what happens if you decline, and a checkbox. |
| **D** | Fixed by your decisions or the canonical resolutions in DECISIONS.md (for example D7: the strength gate uses your personal rep curve). |
| **E** | An engineering interpretation needed to run an A rule deterministically (for example "which arm decides when one shared pin serves both arms"). It does not add training content; each E item is listed so you can object. |

Evidence grades: strong, moderate, weak, expert-opinion (coach heuristics, forum consensus),
engineering-fact (arithmetic, equipment facts). Almost everything in this program is expert opinion;
the app labels it that way.

---

## 1. The program in plain language

**The goal.** One strict one-arm pull-up in your chosen grip (supinated): start from a dead hang
with the arm fully extended (no kip, no pre-bend), on a bar or fixed handle (no free-hanging ring),
the free hand touches nothing, chin over the bar, controlled return to full extension. These are the
rules you agreed with your opponent on 2026-08-31.

**The idea.** Maximal strength is the engine (heavy weighted pull-ups under five reps), and one-arm
skill is trained from week one in parallel (assisted one-arm reps, holds, negatives, one-arm
hanging). The elbow decides the match: prevention work is in every session, and three warning signs
stop all one-arm work for two weeks.

**Three phases, gated by criteria, not by the calendar.**

| Phase | Name | Expected weeks | Leave when (all on the same valid test day, both arms) |
|---|---|---|---|
| 1 | Heavy pulling and learning to hang | 1-10 | weighted 1RM >= 65 % of BW - 2 kg (about +43.96 kg at 70.7 kg); one-arm dead hang >= 20 s fresh on the neutral handle; 5 s one-arm negative without help; one-arm top hold >= 8 s |
| 2 | Removing assistance | 11-25 | weighted 1RM >= 65 % of BW (+45.96 kg); top hold >= 15 s; controlled 5 s negative; one rep with only one finger of help |
| 3 | Attempts | 26+ | one valid rep (either arm if you approve P16, otherwise both) |

**The week.** Three gym sessions with at least one day between them, a rest day before the heaviest
session, two short home plank sessions, a daily two-arm hang (skipped on gym days; the artifact skips it on session-A days only, D51). Sessions:

| Session | Content today (block 1) |
|---|---|
| C, volume and technique | warm-up; assisted one-arm pull-up 3 x 5 per arm at pin 50 lb (RIR 2); weighted pull-ups 4 x 5 at +20 kg (the anchor); one-arm scapular pulls 4 x 3 per arm (neutral handle, left first, right matches left); one-arm dead hang 4 x 10 s per arm (neutral handle, not to failure); prehab |
| A, one arm heavy | warm-up; assisted one-arm pull-up 5 x 3 per arm at pin 40 lb (first set is the calibration set); calibration negative 1 per arm, 5 s, no help; weighted pull-ups 4 x 4 at +22.5 kg; top hold 3 rounds x 4 short attempts per arm; 90-degree hold 3 x 6-8 s per arm at pin 35 lb; straight-arm hold 3 x 6 s per arm (neutral handle, last); prehab |
| B, max strength | warm-up; weighted pull-ups 6 x 2 at +27.5 kg (RIR 2); weighted negatives 3 x 2 at +47.5 kg, 5 s down; heavy pronated row 4 x 8; dips 3 x 8-10 (neutral handles, RIR 2); one-arm plank 3 x 20 s per side; prehab |
| Test day | replaces B on the Sunday of the deload week (order in section 8) |

**Blocks.** Five weeks: four build weeks, then a deload week (all sets halved, loads kept, daily hang
time halved) that ends with the test day instead of session B. After a valid test the loads are
recalculated and a new block starts on Monday.

**When things go wrong.** A missed week is not made up: you continue where you were with the same
loads, and the next test waits for a normal session without complaints. Two test days without
progress mean sleep and food first, not harder training; if it still does not move, session B
switches to 1-2-3 ladders and an extra deload week is added. Elbow stop signals mean two weeks
without unilateral work, light bilateral training, doubled prevention work, and the daily hang is
the first thing dropped.

**What the app does for you.** It shows the right session with loads and pins, logs your sets with
one tap on the "reps left in the tank" chip (D9), applies your in-session rules to the remaining sets
straight away with an Undo (D14), applies the progression rules after each session, recalculates
after each test day, writes your challenge sessions and test days into your calendar for
this week and next (D11), reminds you of the home habits, and explains every change ("why").

---

## 2. Where each part comes from (source mapping)

| Program element | Artifact (sections 01-10) | Todoist task descriptions | Not in either: proposal |
|---|---|---|---|
| Challenge rules, grip, neutral handle for full-extension one-arm hanging | 02 I, II | | |
| Phases and gate criteria, timeline 6-12 months | 01, 03 | | Completion with either arm (P16); phase 2/3 session details (P18) |
| Week layout, blocks of 4 + 1, deload = halve sets keep loads halve hang | 04 | | Block-relative test dates (P07); deload form while a test is pending (P19) |
| Working loads: anchor 20 kg 4 x 5, A = anchor + 1 rep, B = anchor + 3 reps, 2.17 kg per rep, negatives 105 % of 1RM, pins 40/50/35 lb | 05 | C: "4 x 5 @ 20 kg is the measured anchor" | Test-day recalculation formula for the anchor (P10); pins from the least-assistance test (P11) |
| B progression (+2.5 kg after 6 x 2 at RIR >= 2), grip-slip guard, negatives 8 s / 5 s rule | 05, 06 | B | In-block progression for A and C (P05) |
| A pin -5 lb per week; C pin -5 lb after two easy sessions | 05 | A, C | Gated A pin (P01); definition of "easy" (P03) |
| A first-set calibration (+5 / -5 lb), controlled calibration negative -> -10 lb, warning at 35 lb | | A | |
| C fewer than 4 reps -> +5 lb | | C | |
| 90-degree hold: fails +5 lb, -5 lb every week | 06 | A | |
| Straight-arm hold 6 s -> 8 s when all six attempts are clean | | A | |
| Plank 20 s -> 30 s -> plate when all six sets on all three days are clean | 06 (20 s "with difficulty") | Core, B | Plate size (P22) |
| Daily two-arm hang details, weekly fresh measurement, skipped on A day, halved in deload, dropped first on elbow symptoms | 06, 08 | Hangen | Day of the weekly measurement (P26) |
| Test-day order: 3RM, max reps, one-arm hang L/R, top hold L/R, least assistance L/R; test replaces B; every 5 weeks | 07 ("order in your Todoist task") | Testdag | The 5 s negative is part of every test day, in the order the definition gives (C65, because gate 1 needs it): right after least assistance (P20 confirms this place); max reps last (P08); attempt protocols (P21) |
| Elbow protocol: 3 stop signals, 14 days, two blocks back afterwards | 08, 10 | | Exit only after 3 pain-free days (P14); what "light bilateral" means (P27) |
| Stall rule | 05, 10 | | Definition of "progress" (P04); ladder count (P17); back to 6 x 2 after progress (P24) |
| Missed week, test after a clean session | 01, 10 | | Definition of a missed week (P02); of a clean session (P25); pause handling (P15) |
| Max 3 heavy unilateral sessions per week | 08 | | |
| Sleep 8 h, protein 1.6-2.0 g/kg | 09 | | |
| In-session adjustments (applied at once, with Undo, D14) | | | B grind (P12); A machine after a failed set (P13) |
| Protected window after B | | | P23 (C12) |
| Strength gate metric from the personal rep curve | (x1.10 rule in 05) | | D7 (your decision) |
| Your rules may take bigger steps than the app's generic load-jump caps (warning only) | | | D13 (your decision; section 4.9) |
| In-session rules change the remaining sets at once, with Undo | | | D14 (your decision) |

---

## 3. What the personal rep curve (D7) means for your numbers

You decided (D7) that the strength gate uses your personal rep curve, not the x1.10 rule. Here is the
honest consequence, with today's numbers (bodyweight 70.7 kg, 3RM +32.5 kg on 2026-09-19).

**The model.** The app's rep-capacity model (RCM-1, C3) says the load you can lift for `r` reps,
`T(r)`, relates to your one-rep max `T1` as `T1 = T(r) x (1 + (r - 1) / k)`, where `k` describes how
flat your curve is (higher k = flatter, fewer kilos per extra rep). The x1.10 rule is the same model
with k = 20 at three reps, which is a steep curve. Your own data say your curve is flatter (the
artifact notes this itself).

| Method | Total 1RM (BW + added) | Added kg | Gap to phase-1 gate (+43.96) | Gap to the threshold (+45.96) |
|---|---|---|---|---|
| x1.10 rule (shown for transparency) | 113.52 kg | **+42.82** | 1.14 kg | 3.14 kg |
| Personal curve, k = 30 (what the app uses at cutover: the class prior, because the import creates no set rows for the curve, C59) | 110.08 kg | **+39.38** | 4.58 kg | 6.58 kg |
| Personal curve, k = 30.63 (closed form from your two maximal points, BW x 18 and +32.5 x 3, 20 days apart; ALGORITHMS.md B5.2 / GF-RCM-01; for comparison only, not used, C59) | 109.94 kg | +39.24 | 4.72 kg | 6.72 kg |
| 80 % interval of the personal curve at cutover (class prior k = 30 with its prior spread sd 0.30, C3: k between 20.4 and 44.1) | | **+37.18 to +42.61** | | |

So the current strength estimate is about **+39.4 kg** instead of +42.8 kg, and the phase-1 strength
gate is about **4.6 kg** away instead of about 1.1 kg. These cutover values (+39.38, interval +37.18
to +42.61) are the same as ALGORITHMS.md C3.6 / GF-RCM-05 and CHALLENGE_ENGINE.md GF-CE-02. The
narrower interval +37.84 to +41.36 in ALGORITHMS.md GF-RCM-02 belongs to a hypothetical fit of the two
historical points, which C59 rules out at cutover; the interval narrows once your own logged sets
inform the curve. With k = 30, a test-day 3RM of about **+36.8
kg** meets the gate (about 4.3 kg more than your +32.5 kg), and about +38.7 kg meets the full
threshold.

**Why the app trusts the curve.** On 2026-08-30 you measured both a 3RM (+26 kg at 71 kg) and a
single (+32 kg). The personal curve predicts +32.5 kg from that 3RM; the x1.10 rule predicts +35.7 kg.
The curve matched the only real single you have (the single was grip-limited, with a dumbbell
between the ankles, so it is not a perfect check).

**What does not change.** Your session loads hang on the measured anchor (20 kg for 4 x 5 in C), not
on the e1RM: C 20 kg, A 22.5 kg, B 27.5 kg stay exactly the same. If you approve P10, the test-day
recalculation keeps this continuity explicitly (section 6, P10).

**What would change.** The artifact's negatives rule (105 % of the 1RM) would, with the lower 1RM,
drop your negatives from 47.5 kg to 42.5 kg at the next test even though they progress well on their
own 8 s / 5 s rule. P09 proposes to stop that.

**On test days the app shows** the gate value (personal curve, with the k it used), the x1.10 value
and the 80 % interval side by side, plus the general training estimate (SSE-1, C4), each labelled. No
extra max single is added to test days (D7). The k used, the x1.10 value and the interval are stored
with the result (the k and the alternatives in the observation's `calc_params`; the x1.10 value and
the interval also as the display-only metrics `wpu_e1rm_added_m110`, `wpu_e1rm_added_hi` and
`wpu_e1rm_added_lo`, DATA_MODEL 9.1.4) and the value is never recomputed later (C3).

**Which bodyweight counts (C62).** A gate is evaluated with the bodyweight you weighed in on that test
day (raw value, C32). The dashboard also shows the forward threshold at your current bodyweight BW(t)
(today: 0.65 x 70.7 - 2 = +43.96 kg), so you can see the target moving if your weight changes.

---

## 4. Full rule table

All rules of `oap@1.0.0`, grouped by stage in the engine's order (safety, data, structural,
progression, then in-session). Within one event every rule reads the state as it was **before** the
event; if two rules want to change the same value, the earlier one in this order wins, and a "lock"
(for example after a grip slip) blocks increases regardless of order (CHALLENGE_ENGINE.md section 5).
The exact conditions are FitExpr strings in the JSON; this table states them in words with every
threshold. Abbreviations: wpu = weighted pull-up, oaa = assisted one-arm pull-up, h90 = 90-degree
hold, sth = straight-arm hold, neg = weighted negatives, pin in lb, RIR = reps in reserve as you
reported it (C17). Negatives have no RIR (C66): they are logged by the lowering time in seconds, the
`jerky` flag and the effort chips Max / Hard / OK / Easy, so every negative condition below reads
the lowering seconds and the flags.

### 4.1 Safety rules

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `safety.elbow.stop` | A | Pain report or readiness check | Not already in injury mode, and any of the three stop signals: medial elbow ache hours after training (`medial_elbow_ache_after`), pain deep in the elbow crease on extension (`elbow_crease_pain_on_extension`), tingling in ring or little finger (`ulnar_tingling`) | Mode `injury` (14 days no unilateral work, light bilateral, prehab doubled, daily hang off); injury start = today; a clean session is needed before the next test; elbow trace on; STOP notice (once per day) | Artifact 08, 10 | Safety-critical. Only works if reporting is fast and honest; the app makes it one tap and asks before every pulling session. Not medical advice. A report during the protocol does not restart the 14 days (E): `safety.injury.exit` ends it after 14 days and P14 decides what persisting pain does. |
| `safety.elbow.trace` | A | Readiness check before a session | Always | Elbow trace = (elbow check >= 1 on the 0-3 scale: none / trace / mild / pain). A trace skips the negatives (B and the A calibration negative) and today's daily hang | Artifact 06, Todoist B ("skip at any trace of elbow pain") | E: "any trace" is mapped to score >= 1. |
| `safety.elbow.traceFromPain` | A | Pain report | Location is an elbow region (`elbow_medial`, `elbow_lateral`, `elbow_anterior`, and from 1.6.0 also `elbow_posterior` and `forearm`, D45; the DATA_MODEL 9.1.5 body-region codes; the P14 rules below use only the first three) | Elbow trace on until the next readiness check | Artifact 08 | |
| `safety.injury.exit` | A, replaced if P14 approved | Every day | In injury mode and >= 14 days since it started | Loads back to the level of two blocks ago (block index - 2, at least block 1, which is your imported state); mode normal; a new block starts next Monday | Artifact 10 ("daarna terug op het niveau van twee blokken geleden") | Ends the protocol even if pain persists; P14 adds a pain-free check. |
| `safety.injury.exit.painfree` | P (P14) | Every day | Injury, >= 14 days, and the last 3 days pain-free at the elbow | Same as `safety.injury.exit` | P14 | |
| `safety.injury.extend` | P (P14) | Every day | Injury, >= 14 days, but elbow pain within the last 3 days | Protocol extended by 7 days; advice to see a physiotherapist (once per block) | P14 | |

### 4.2 Data rules (test day)

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `data.test.e1rm` | D | Test completed | Valid test with a 3RM | Gate e1RM = (BW + 3RM) through your personal curve (k of the weighted pull-up; the class prior 30 until your own sets inform it, C59) - BW; the x1.10 value stored for display | D7, C3, C4 | k, the x1.10 value and the 80 % interval are stored with the value; never recomputed (C3). |
| `data.test.loads` | A, replaced if P10 approved | Test completed | Valid test, P10 not approved | A = anchor + 1 x kg-per-rep, B = anchor + 3 x kg-per-rep, rounded to the nearest loadable weight (the anchor itself stays the last measured C load) | Artifact 05, 10 (working loads are recalculated on every test day) | Without P10 (and P05) the anchor never rises, so A and B only follow C. |
| `data.test.loads.curve` | P (P10) | Test completed | Valid test with a 3RM | Anchor = anchorFraction x (BW + gate e1RM) - BW, clamped to +/-5 kg of the old anchor and rounded to nearest; kg per rep = slope of your curve at the anchor; A = anchor + 1 rep, B = anchor + 3 reps, each clamped to +/-5 kg of its old value | P10 | anchorFraction is frozen at import (0.8239 with k = 30), so the same 3RM gives the same 20 / 22.5 / 27.5 kg. A full +5 kg step is about +5.1 to +5.5 % of system load, above the generic cap: applied with a warning (D13, section 4.9). |
| `data.test.neg` | A, replaced if P09 approved | Test completed | Valid test with a 3RM | Negatives = 105 % x (BW + gate e1RM) - BW, rounded down to a loadable weight; reset level = that - 2.5 kg, rounded down | Artifact 05 | With D7 this gives 42.5 kg (from 47.5) at an unchanged 3RM. |
| `data.test.neg.keep` | P (P09) | Test completed | Valid test with a 3RM | If at least one valid negative session was logged in the block (including the pre-cutover count): negatives = max(current, min(105 % value, current + 5 kg)); otherwise the 105 % value clamped to +/-5 kg; reset level = negatives - 2.5 kg | P09 | |
| `data.test.pins` | P (P11) | Test completed | Valid test with a least-assistance result | Weaker arm's least-assist pin = w; A pin = w + 10 lb; C pin = w + 20 lb; 90-degree hold pin = w + 5 lb (all rounded up = more help); A fail streak reset | P11 | Resets in-block pin gains each test unless least assistance improved. A large improvement can lower a pin by 10 lb or more at once (+9.8 % at 40 -> 30 lb): applied with a warning (D13, section 4.9). |
| `data.test.hangTarget` | P (P06) | Test completed | Valid test with a one-arm hang | C hang target = floor(0.67 x weaker-arm hang), between 10 s and min(30 s, current + 5 s) | P06; Todoist C ("reviewed on test day") | |
| `data.test.deadStart` | A | Test completed | Valid test, weaker-arm fresh one-arm hang > 15 s, first time | Note: starts from a dead hang may return (their prescription needs a definition update) | Artifact 06 ("geparkeerd ... zodra je one-arm dead hang fris boven de 15 s komt op een testdag") | E: "above 15 s" read as the weaker arm. The exercise itself is not encoded yet (open issue). |
| `data.pinA.report` | A | Session completed | Session A, normal week, all 5 x 3 per arm done at a pin of 35 lb or less, first time | Note in your history: "the 30-08 baseline underestimated your pulling strength" | Todoist A ("Let op bij 35 lbs") | Information only. |

### 4.3 Structural rules (weeks, blocks, tests, phases)

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `struct.test.postpone` | A | Test day reached | No clean session since an interruption | Mode test pending; today's slot becomes session B in deload form; the test moves to the first Sunday after a clean session; your calendar event is rewritten (if the app owns it) or you are asked (C36) | Artifact 01 "Volgende meting", 10 | |
| `struct.cleanSession` | A, uses P25 | Session completed | A, B or C session, not in injury mode, no pain reported, and (with P25) >= 80 % of its working sets logged | The next test may go ahead | Artifact 01, 10 ("een gewone sessie zonder klachten") | Without P25 any completed session counts. |
| `struct.test.done` | A | Test completed | Valid test | New block from next Monday with the recalculated loads | Artifact 04, 07, 10 | Validity: no clean-session debt, in a deload or test-pending week, weigh-in recorded. |
| `struct.stall` | A, uses P04 | Test completed | Valid test and a previous valid test exists | Progress: counters reset. No progress: count + 1; at 2 tests without progress -> stage 1 (sleep and protein check every evening, message "not a signal to train harder"); at one more test without progress -> stage 2 (B switches to 1-2-3 ladders, an extra deload week after this test) | Artifact 05 ("twee blokken op rij"), 10 ("twee testdagen zonder vooruitgang ... blijft het daarna staan") | E: "blijft het daarna staan" = one more test without progress. Without P04, any strict improvement in any metric counts as progress. |
| `struct.stall.revert` | P (P24) | Test completed | Ladder variant active and this test shows progress | B back to 6 x 2 | P24 | |
| `struct.phase.gate` | A | Test completed or attempt logged | All criteria of the current phase pass (phases 1-2: on the same valid test day; phase 3: any valid attempt) | Next phase, regardless of the week you are in (phase 3: challenge complete) | Artifact 03, 10 ("ongeacht in welke week je zit") | Gates are coach heuristics, not research. |
| `struct.block.rollover` | A | Week closed | A valid test happened (block end pending) | New block: week 1 (or an extra deload week first if stage 2 of the stall rule asked for it) | Artifact 04, 10 | |
| `struct.week.close` | A, uses P02 | Week closed | No block end pending, not injury | Extra deload week: next week is normal (does not use a build week). Deload or test-pending week without a test: the week repeats, test still pending. Normal week: if the week counts, advance one week; otherwise repeat the week with the same loads and require a clean session before the next test | Artifact 10 ("niets inhalen ... dezelfde gewichten") | Without P02 a week is missed only when no challenge session was done at all. |
| `struct.deload.enter` | P (P07) | Week started | Normal mode and block week 5 | Deload; test on Sunday | P07, F9, artifact 04 | Block-relative: each block is 5 weeks from the test actually done. |
| `struct.deload.enter.fixed` | A, replaced if P07 approved | Week started | Normal mode and this ISO week contains the next date of the fixed list (2026-10-11, 11-08, 12-13, 2027-01-17, 02-21, 03-28, 05-02) that is at least 21 days after the last valid test | Deload; test on that date | Artifact 07 table | With the 11 Oct test, block 2 would be only 4 weeks (test 8 Nov). E: the 21-day minimum gap. |
| `struct.resume.interrupt` | A, replaced if P15 approved | Resume after a pause | Pause of at least 1 day | Same week, same loads; the next test waits for a clean session | Artifact 10 ("na een onderbreking schuift een testdag door") | |
| `struct.resume.repeat` | P (P15) | Resume | Pause of 7-27 days | This week repeats with the same loads; the test waits for a clean session | P15 | |
| `struct.resume.restart` | P (P15) | Resume | Pause of 28 days or more | Loads back to this block's starting loads; the block restarts at week 1; clean session needed | P15 | |

### 4.4 Progression rules (after a session or week)

In deload, test-pending (whether or not P19 is approved), extra-deload and injury weeks, progression is
frozen (C47): a progression rule that would change a load, pin or hold target is suppressed unless it
is a protective rule that only makes things easier (marked "allowed in frozen weeks" below,
`allowInFrozen`; CHALLENGE_ENGINE.md section 5.5). This settles the reading of "keep the weight".

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `prog.gripSlip.lock` | A | Session completed | A, B or C session with a hand slip before the last rep on any weighted pull-up set | No weighted pull-up load (A, B, C) may increase from this session | Artifact 05, Todoist A and B ("dat is greep, geen kracht") | |
| `prog.B.wpu` | A | Session completed | Session B, normal week, 6 x 2 variant, all six sets done with >= 2 reps and every set at RIR >= 2 | B load + 2.5 kg next week (nearest loadable) | Artifact 05, Todoist B | |
| `prog.B.neg.up` | A | Session completed | Session B, normal week, every negative >= 8 s and none jerky | Negatives + 5 kg (rounded down) | Artifact 05, Todoist B | |
| `prog.B.neg.reset` | A, allowed in frozen weeks | Session completed | Session B, any negative < 5 s or jerky | Negatives back to the reset level (45 kg now) | Artifact 05, Todoist B ("terug naar 45 kg") | Without P09 the reset level never moves up, so after a few increases a reset can drop more than 2.5 kg. |
| `prog.B.negReset.follow` | P (P09) | Session completed | Same condition as `prog.B.neg.up` | Reset level + 5 kg as well | P09 | |
| `prog.A.pin.weekly` | A, replaced if P01 approved | Session completed | Session A, normal week, assisted one-arm sets logged | Next A pin = the pin you finished on - 5 lb (- 10 lb if the calibration negative was controlled), not below 0 | Artifact 05, Todoist A | -5 lb per week is about +4.9 % one-arm load per week (+19.7 % per block): likely faster than you adapt; elbow risk. The -10 lb step (+9.8 %) is above the generic cap: applied with a warning (D13, section 4.9). |
| `prog.A.pin.gated` | P (P01), allowed in frozen weeks (only its +5 lb branch can act there) | Session completed | Session A, assisted sets logged | All 5 x 3 per arm done (normal week): -5 lb (-10 lb after a controlled negative), fail streak 0. Reps missed: same pin; second failed week in a row: +5 lb and streak 0 | P01 | In a re-entry week the base is the logged pin minus `reentryPinOffsetLb` (D63). |
| `prog.A.hold90` | A | Session completed | Session A, normal week, 90-degree holds logged | Next pin = the pin you finished on - 5 lb (not below 0) | Artifact 06, Todoist A ("elke week 5 lbs eraf") | The in-session +5 lb on a failed hold is `ins.h90.fail`. From a pin of 45 lb or more the -5 lb step is above the generic cap: applied with a warning (D13, section 4.9). |
| `prog.A.sth` | A | Session completed | Session A, straight-arm hold target below 8 s, all six holds (3 per arm) at least at target, no torso rotation | Target 6 s -> 8 s | Todoist A | |
| `prog.C.pin.easy` | A, uses P03 | Session completed | Session C, normal week, every set >= 4 reps | Easy session: second easy one in a row -> pin - 5 lb and streak 0; otherwise streak + 1. Not easy: streak 0 | Artifact 05, Todoist C | Without P03 only an explicit "too easy" tap counts as easy. At pin 50 the -5 lb step is +5.5 % one-arm load, above the generic cap: applied with a warning (D13, section 4.9). |
| `prog.C.pin.hard` | A, allowed in frozen weeks | Session completed | Session C, any set below 4 reps | Pin + 5 lb (more help), streak 0 | Todoist C ("kom je onder de vier reps, 5 lbs erbij") | In a re-entry week the step starts from the logged pin minus `reentryPinOffsetLb` (D63). |
| `prog.plank` | A, uses P22 | Week closed | Plank done on at least 3 days that week, 6 sets per day (18 in total), every set at the target time, hip never tilted | 20 s -> 30 s; at 30 s: a plate on the upper back (P22: 5 kg; declined: the app asks which plate) | Todoist Core and B | In deload and test weeks this cannot fire (fewer sets, B replaced). |
| `prog.wpu.A` | P (P05) | Session completed | Session A, normal week, all 4 x 4 done, every set RIR >= 2 | A load + 2.5 kg next week | P05 | |
| `prog.wpu.C` | P (P05) | Session completed | Session C, normal week, all 4 x 5 done, every set RIR >= 2 | Anchor + 2.5 kg next week | P05 | Moves the anchor in-block; the test recalculation (P10) is clamped against it. |
| `prog.finger` | P (P18) | Session completed | Phase >= 2, session A, normal week, pin 0 lb, all sets done | One finger less assistance (4 -> 3 -> 2 -> 1) | P18, artifact 03 phase 2 | |

### 4.5 In-session rules (during a session; applied at once with Undo, D14)

These rules change the remaining sets of the item immediately; the set card shows "Rule: … · Undo",
for example "Rule: Calibration set: pin +5 lb for the remaining sets · Undo" (D14, C46). Each rule
that changes the remaining sets applies at most once per item per session (CHALLENGE_ENGINE.md
section 6.10), so the calibration rule and P13 can each act once on the A machine exercise. The
message-only rules (grip slip, rotation, hip tilt) can show their message after every logged set, and
the left/right match of the scapular pulls acts after every left set (C71: `capTarget` rules only
change the next row's target, so the once-per-session limit does not apply to them). Undo
restores the planned value and is logged as an override with a reason, so reviews can see how often a
rule was undone; an undone rule does not fire again on that item in the same session.
This differs from the general (non-challenge) exercises, where in-session load changes stay one-tap
suggestions that are never applied automatically (D10, C16). The instruction rules (grip slip,
rotation, hip tilt) only show a message and never change a load.

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `ins.wpu.gripStop` | A | Weighted pull-up set logged | Hand slipped before the last rep | "End the set here" | Artifact 06, Todoist A | |
| `ins.A.oaa.calibrate` | A | First L/R pair of the A assisted one-arm logged | Weaker arm <= 2 reps, or >= 5 reps | + 5 lb (<= 2 reps) or - 5 lb (>= 5 reps) on the pin for the remaining sets, applied at once (Undo) | Todoist A ("de eerste set is je ijking") | E: the weaker arm decides because one pin serves both arms. The calibration set stops with about one rep left (never to failure, D10). |
| `ins.A.oaa.fail` | P (P13) | A assisted pair logged (pair 2 or later) | Weaker arm >= 2 reps below target | + 5 lb assistance for the remaining sets, applied at once (Undo) | P13 | |
| `ins.h90.fail` | A | 90-degree hold pair logged | Either side under 6 s | + 5 lb on the pin for the remaining rounds, applied at once (Undo) | Artifact 06, Todoist A ("lukt het niet, 5 lbs erbij") | |
| `ins.B.wpu.grind` | P (P12) | B weighted set logged | Fewer reps than the target, or RIR 0 | - 2.5 kg for the remaining sets, applied at once (Undo) | P12 | |
| `ins.scap.matchLeft` | A | Scapular pull, left side logged | Always | The right side's target = at most the left side's clean reps; the banner says so ("Right side reduced to 2 reps.", D52) | Todoist C ("links eerst, rechts matcht links") | |
| `ins.rotationStop.*` | A | Set of `finger`, `hang`, `oaa`, `scap` or `sth` logged | Torso rotated (`scap`: per side) | One message per item (D52): "end the set here"; hang "the clock stops here, log the time up to the rotation"; scap right side "nothing carries over to the next set"; sth "the hold time does not go up after this session" | Artifact 06, Todoist A and C | Before 1.9.0 one rule for every item: "The set (or the clock) stops here". |
| `ins.plank.hipStop` | A | Plank set logged | Hip tilted | "The set stops here" | Todoist Core and B | |

### 4.6 Definitions used by the rules

| Name | Tag | Definition | Source |
|---|---|---|---|
| `calNegControlled` | A (E reading) | Both arms' calibration negatives logged, each >= 5 s, none jerky, the hand did not shift | Todoist A ("lukt dat gecontroleerd"); E: "controlled" made concrete |
| `cEasy` | A, uses P03 | An explicit "too easy" tap on the C assisted exercise; with P03 also: all 3 x 5 per arm done with RIR >= 3 on every set | Artifact 05, Todoist C; P03 |
| `weekCounts` | A, uses P02 | With P02: at least 2 of the week's required sessions, each with >= 50 % of its working sets logged. Without P02: at least one session | Artifact 10; P02 |
| `testProgress` | A, uses P04 | Versus the previous valid test, on the weaker arm: with P04, e1RM + >= 1.0 kg, or hang + >= 2 s, or top hold + >= 1 s, or least assistance 5 lb lower; without P04, any strict improvement in one of these | Artifact 10; P04 |
| Test validity | A | No clean-session debt, in a deload or test-pending week, weigh-in recorded | Artifact 01, 07, 10 |

### 4.7 Modes

| Mode | Tag | Effect |
|---|---|---|
| normal | A | Sessions as written. |
| deload | A | Sets halved (rounded up, at least 1; warm-up and, from 1.7.0, prehab unchanged, D47), loads kept, hold times kept, daily hang sets and time halved, progression frozen. Test on Sunday instead of B. |
| test pending | P19 (else normal) | A waiting week after a postponed test; with P19 it keeps the deload form, without P19 the full build sessions. Progression is frozen either way (C47). |
| extra deload | A | Deload form; does not count as a block week. |
| injury | A (light bilateral defined by P27) | No unilateral and no eccentric items; daily hang off; prehab sets doubled; progression frozen; general training may not include grip-heavy or elbow-flexor-heavy work. With P27: pull-ups at bodyweight, rows and dips at RIR >= 3, half the sets. Without P27: the remaining items keep their loads with a "keep it light" note. |

### 4.8 Habit and test-day rules (encoded in `habits` and the test template)

| Id | Tag | Trigger | Condition | Action | Source | Risk note |
|---|---|---|---|---|---|---|
| `daily_hang` | A | Daily 17:15 reminder | Not a gym day (A, B, C or the test day; before 1.8.0 only A, D51), no elbow trace, not in the elbow protocol | Two-arm hang 4-5 sets, 2-3 min total, pronated or neutral (never supinated), at least half on a fixed bar or handle, chalk, thumb around, stop 5-10 s before slipping, no one-arm hanging; deload weeks: half the sets and half the time | Todoist Hangen; artifact 06, 08 | First thing dropped on elbow symptoms. |
| `daily_hang.measure` | A, uses P26 | Once a week | The measurement day (P26: Monday; declined: you mark the day) | One fresh max two-arm hang, recorded as `two_arm_hang_best_s`; the rest of the week stays submaximal | Todoist Hangen (weekly fresh best-time measurement; DECISIONS section 4) | Display only; not a gate. |
| `home_plank` | A | Tuesday and Thursday 17:30 | Not a gym day, not the day before B or the test | One-arm plank 3 x target per side, stop when the hip tilts | Todoist Core; artifact 06 | Counts toward `prog.plank`. |
| `sleep_protein_check` | A | Daily 21:30 | Stall stage 1 or 2 | Checklist: slept >= 8 h; protein 1.6-2.0 g/kg (113-141 g at 70.7 kg) | Artifact 09, 10 | |
| Test-day order | A, with P08 / P20 / P21 | Test day | | Weigh-in, warm-up, weighted 3RM, max strict reps, one-arm hang L/R (neutral handle), one-arm top hold L/R, least assistance L/R; then the unassisted 5 s negative per arm, which is part of every test day in the order the definition gives (C65: right after least assistance; P20 confirms that place, there is no free order); P08 moves max reps to the very end, after the negative; P21 adds the attempt protocols | Todoist Testdag (F10); C65 | Long battery; grip fatigue affects later items (P08). |
| Test replaces B | A | Deload week | | The Sunday of the deload week is the test day instead of session B | Artifact 04, 07; Todoist Testdag | |
| Machine busy | A | Assisted machine occupied (reason "occupied") | | Finger assistance (towel or free hand, count fingers, start at 4) for the remaining sets; those sets do not move the pin | Artifact 03 (towel only as the fallback when the machine is busy; count fingers, not grip height) | |

### 4.9 Your rules and the generic load-jump caps (D13, C45)

The general engine limits how fast it raises a load between sessions: for compound lifts at most
+5 % of the system load per step (`rx.cap_up_compound`, ALGORITHMS.md B9.14). You decided (D13) that
your approved challenge rules win: these caps do **not** limit the loads that challenge rules set
(RULE mode). When a rule takes a bigger step, the app applies it, shows a non-blocking warning (WE-S2
at WARN severity, with the numbers in the Why sheet) and logs it (C45). The other safety rules still
bind the challenge: the elbow protocol and pain handling, no failure on weighted or one-arm pulls, and
the protected windows.

System load = bodyweight x 1.00 + belt load, or bodyweight x 1.00 - assistance (C1, C2; assistance =
pin x 0.4536 + 6.5 kg). At 70.7 kg these rules can exceed the cap:

| Rule | Tag | Step that exceeds +5 % | Size |
|---|---|---|---|
| `prog.C.pin.easy` | A (uses P03) | C pin 50 -> 45 lb (today), and every later -5 lb step from a pin of 45 lb or more | 41.52 -> 43.79 kg, +5.5 %; 45 -> 40 lb +5.2 % |
| `prog.A.pin.weekly` | A (replaced if P01 approved) | -10 lb after a controlled calibration negative, 40 -> 30 lb; also any -5 lb step at a pin of 45 lb or more | 46.06 -> 50.59 kg, +9.8 %; 45 -> 40 lb +5.2 % |
| `prog.A.pin.gated` | P (P01) | The same steps as `prog.A.pin.weekly` | as above |
| `prog.A.hold90` | A | A -5 lb step of the 90-degree hold pin from 45 lb or more (not today: the pin is 35 lb; possible after P11 sets the pin from a weak least-assistance result) | 45 -> 40 lb +5.2 % |
| `data.test.loads.curve` | P (P10) | A full +5 kg recalculation step (the clamp) | C 20 -> 25 kg +5.5 %, A 22.5 -> 27.5 kg +5.4 %, B 27.5 -> 32.5 kg +5.1 % |
| `data.test.loads` | A (replaced if P10 approved) | Unclamped A = anchor + 1 rep and B = anchor + 3 reps; exceeds the cap only when the anchor rose in the block (P05) while A or B did not | for example +5 kg on A = +5.4 % |
| `data.test.pins` | P (P11) | A large least-assistance improvement lowers a pin by 10 lb or more at once | 40 -> 30 lb +9.8 % |
| `data.test.neg` | A (replaced if P09 approved) | Negatives follow 105 % of the gate e1RM, so they jump when the e1RM rises by more than about 5 % between two tests | 3RM +32.5 -> +40 kg: negatives 42.5 -> 52.5 kg, +8.8 % |

Steps that stay within the caps: B +2.5 kg (`prog.B.wpu`, +2.5 %), the P05 steps (+2.5 kg, about
+2.7 %), negatives +5 kg (`prog.B.neg.up`, +4.2 %; P09 clamps its own recalculation to the same +5 kg
step), the A pin -5 lb at 40 lb or less (+4.9 % and smaller), the 90-degree hold pin 35 -> 30 lb
(+4.7 %), and the in-session -5 lb calibration step at 40 lb (+4.9 %, inside the general in-session band
of -8 % / +6 % anyway). Hold times (straight-arm hold 6 -> 8 s, plank 20 -> 30 s) are not load jumps.
The first plank plate (`prog.plank`, 0 -> 5 kg with P22) is a load step on a hold whose bodyweight
fraction is 0, so a percentage cap is not defined for it; WE-S2 is not evaluated for holds (C70), and
the step is governed by the hold rules and explained in the Why sheet.
The B row and the dips are progressed by the general engine (`delegate:rx`), so the generic caps do
apply to them, with the exception that DOUBLE mode may always advance by exactly one loadable step
(C45; for high-risk exercises only when the rep ladder at the new load still starts at or above the
lower rep target).

---

## 5. Parameters

Every number the rules use is a parameter of the definition (never hardcoded). A parameters are your
program's values and are not tuned automatically; P parameters belong to a proposal and are used
only when it is approved; E parameters are engineering defaults. "Tunable" values are assumptions:
the named logged data is what a periodic review uses to suggest a change, which then needs your
approval as a new definition version.

| Parameter | Default | Bounds | Tag | Grade | Source | Tunable / tuned by |
|---|---|---|---|---|---|---|
| `grip` | supinated |  | A | expert-opinion | artifact section II | user setting Note: Changing grip after enrollment invalidates comparisons with earlier tests (Todoist Testdag: grip must be identical on every test day). |
| `thresholdRatioByGrip` | {supinated: 0.65, neutral: 0.7, pronated: 0.75} | 0.55 - 0.85 | A | expert-opinion | artifact section 01 (Kavadlo 65-75 %; neutral/pronated are the artifact's own unsourced interpolations) | no Note: Stored as a ratio x BW (C32), never a fixed kg. |
| `gate1GapKg` | 2 kg | 0 - 5 | A | expert-opinion | artifact section 03 (gate +44 kg = 0.65 x 70.7 - 2) | no |
| `gate1HangS` | 20 s |  | A | expert-opinion | artifact section 03 (lowered from 40 s on 2026-09-15) | no |
| `gate1TopHoldS` | 8 s |  | A | expert-opinion | artifact section 03 | no |
| `gate2TopHoldS` | 15 s |  | A | expert-opinion | artifact section 03 | no |
| `m3` | 1.1 | 1.03 - 1.14 | A | weak | artifact section 05 '(BW + 3RM) x 1.10 - BW' | no Note: Display only (D7): the gate metric uses the personal curve. |
| `anchorReps` | 5 |  | A | engineering-fact | artifact section 05 (anchor = C 4 x 5) | no |
| `negFraction` | 1.05 | 1 - 1.15 | A | expert-opinion | artifact section 05 (negatives = 105 % of 1RM) | no |
| `negResetStep` | 2.5 kg |  | A | expert-opinion | artifact section 05 / Todoist B ('terug naar 45' = 47.5 - 2.5) | no |
| `negUpEccS` | 8 s |  | A | expert-opinion | Todoist B / artifact section 05 | no |
| `negResetEccS` | 5 s |  | A | expert-opinion | Todoist B / artifact section 05 | no |
| `negUpStepKg` | 5 kg |  | A | expert-opinion | Todoist B / artifact section 05 | no |
| `bStepKg` | 2.5 kg |  | A | expert-opinion | artifact section 05 / Todoist B | no |
| `bMinRir` | 2 |  | A | expert-opinion | artifact section 05 / Todoist B | no |
| `pinDropLb` | 5 lb |  | A | engineering-fact | artifact section 05 / Todoist A and C | no Note: One machine step. |
| `pinDropNegLb` | 10 lb |  | A | expert-opinion | Todoist A (controlled calibration negative -> 10 lb instead of 5) | no |
| `calLowReps` | 2 |  | A | expert-opinion | Todoist A (first set 1-2 reps -> +5 lb) | no |
| `calHighReps` | 5 |  | A | expert-opinion | Todoist A (first set 5 or more -> -5 lb) | no |
| `calNegEccS` | 5 s |  | A | expert-opinion | Todoist A (calibration negative 5 s, no help) | no |
| `pinAReportLb` | 35 lb |  | A | engineering-fact | Todoist A ('Let op bij 35 lbs': 5 x 3 per arm there means the 30-08 baseline underestimated you) | no |
| `h90MinHoldS` | 6 s |  | A | expert-opinion | artifact section 06 / Todoist A (hold 6-8 s; fails -> +5 lb) | no |
| `sthTargetS` | 8 s |  | A | expert-opinion | Todoist A (all six attempts clean at 6 s -> 8 s) | no |
| `cEasySessions` | 2 |  | A | expert-opinion | artifact section 05 / Todoist C | no |
| `cHardBelowReps` | 4 |  | A | expert-opinion | Todoist C ('kom je onder de vier reps, 5 lbs erbij') | no |
| `plankTargetS` | 30 s |  | A | expert-opinion | Todoist Core / B | no |
| `injuryDays` | 14 days |  | A | expert-opinion | artifact section 08 / 10 | no |
| `maxHeavyUnilateralPerWeek` | 3 |  | A | expert-opinion | artifact section 08 | no |
| `stallTestsStage1` | 2 |  | A | expert-opinion | artifact section 10 (two test days without progress -> sleep and food) | no |
| `stallTestsStage2` | 3 |  | A | expert-opinion | artifact section 10 ('blijft het daarna staan' -> ladders + extra deload) | no |
| `deadStartUnlockHangS` | 15 s |  | A | expert-opinion | artifact section 06 / Todoist A (starts from dead hang return when the fresh one-arm hang exceeds 15 s on a test day) | no |
| `proteinGPerKgMin` | 1.6 |  | A | moderate | artifact section 09 | no |
| `proteinGPerKgMax` | 2 |  | A | moderate | artifact section 09 | no |
| `sleepHoursMin` | 8 |  | A | expert-opinion | artifact section 09 | no |
| `blockWeeks` | 5 |  | A | moderate | artifact section 04 | no |
| `fixedTestDates` | [2026-10-11, 2026-11-08, 2026-12-13, 2027-01-17, 2027-02-21, 2027-03-28, 2027-05-02] |  | A | engineering-fact | artifact section 07 table (first test moved from 4 to 11 Oct) | no Note: Used only when P07 is declined. |
| `fixedDateMinGapDays` | 21 days | 14 - 35 | E | engineering-fact |  | yes: number of build weeks actually achieved before each fixed test Note: Only when P07 is declined: a fixed date closer than this to the last valid test is skipped. |
| `aFailsToRaise` | 2 | 1 - 4 | P (P01) | expert-opinion | proposal P01 | yes: oaa completion rate per pin level and pin trajectory (challenge_rule_events for prog.A.pin.gated) |
| `minSessionsForWeek` | 2 | 1 - 3 | P (P02) | expert-opinion | proposal P02 | yes: weeks repeated vs performance in the following week |
| `sessionCountsRatio` | 0.5 | 0.25 - 1 | P (P02) | expert-opinion | proposal P02 | yes: completionRatio distribution of logged sessions |
| `cEasyMinRir` | 3 | 3 - 5 | P (P03) | expert-opinion | proposal P03 | yes: reported RIR on C oaa sets vs next-session performance after a -5 lb step |
| `progressEpsE1rm` | 1 kg | 0.5 - 2.5 | P (P04) | weak | proposal P04 | yes: test-retest noise of the gate e1RM (Grgic 2020: CV ~4 %, i.e. ~4 kg on 110 kg total) and SSE-1 posterior sd |
| `progressEpsHang` | 2 s |  | P (P04) | expert-opinion | proposal P04 | yes: day-to-day spread of fresh hang measurements |
| `progressEpsTopHold` | 1 s |  | P (P04) | expert-opinion | proposal P04 | yes: spread of top-hold attempts within a test |
| `progressEpsAssist` | 5 lb |  | P (P04) | engineering-fact | proposal P04 | no Note: One machine step; cannot be smaller. |
| `inBlockStepKg` | 2.5 kg |  | P (P05) | expert-opinion | proposal P05 | yes: reps/RIR in the session after an increase |
| `inBlockMinRir` | 2 | 2 - 4 | P (P05) | expert-opinion | proposal P05 | yes: RIR reported on the A and C weighted sets |
| `hangTargetFraction` | 0.67 | 0.5 - 0.8 | P (P06) | expert-opinion | proposal P06 | yes: effort chips (seconds in reserve) on the C dead-hang sets |
| `hangTargetMin` | 10 s |  | P (P06) | engineering-fact | proposal P06 | no Note: Current prescription. |
| `hangTargetMax` | 30 s |  | P (P06) | expert-opinion | proposal P06 | yes: session duration (TIME-1) and effort chips |
| `hangTargetMaxStep` | 5 s |  | P (P06) | expert-opinion | proposal P06 | yes: effort chips in the first C session after a change |
| `maxRecalcJumpKg` | 5 kg | 2.5 - 10 | P (P10) | expert-opinion | proposal P10 | yes: reps vs target and RIR in the first two sessions after a recalculation |
| `pinOffsetA` | 10 lb |  | P (P11) | expert-opinion | proposal P11 | yes: A oaa first-set (calibration) reps in the first session after a test |
| `pinOffsetC` | 10 lb |  | P (P11) | expert-opinion | proposal P11 | yes: C oaa reps and RIR after a test |
| `pinOffsetHold90` | -5 lb |  | P (P11) | expert-opinion | proposal P11 | yes: 90-degree hold seconds after a test |
| `bGrindStepKg` | 2.5 kg |  | P (P12) | engineering-fact | proposal P12 | no Note: Smallest belt step. |
| `oaaFailMargin` | 2 |  | P (P13) | expert-opinion | proposal P13 | yes: how often the automatically applied +5 lb is undone (D14) and reps on the following set |
| `painFreeDaysToExit` | 3 days | 1 - 7 | P (P14) | expert-opinion | proposal P14 | yes: pain reports in the 14 days after an injury exit |
| `injuryExtendDays` | 7 days |  | P (P14) | expert-opinion | proposal P14 | no |
| `pauseRepeatDays` | 7 days |  | P (P15) | expert-opinion | proposal P15 | yes: performance in the first sessions after pauses of different lengths |
| `pauseRestartDays` | 28 days |  | P (P15) | expert-opinion | proposal P15 | yes: performance in the first sessions after pauses of different lengths |
| `ladderCount` | 3 | 2 - 5 | P (P17) | expert-opinion | proposal P17 | yes: RIR on the last rung and session duration |
| `plankPlateKg` | 5 kg |  | P (P22) | expert-opinion | proposal P22 | yes: effort chips on the first loaded plank week |
| `cleanSessionRatio` | 0.8 | 0.5 - 1 | P (P25) | expert-opinion | proposal P25 | yes: test results after sessions with different completion ratios |
| `hangMeasureWeekday` | MON |  | P (P26) | engineering-fact | proposal P26 | user setting |

Values inside the definition but outside `params` (also assumptions, listed so a review can find
them):

| Value | Default | Tag | Grade | Tunable / tuned by |
|---|---|---|---|---|
| Assisted machine offset | 6.5 kg (`assistKg = pin x 0.45359237 x 1.0 + 6.5`; 35 lb -> 22.38 kg, 40 lb -> 24.64 kg, 50 lb -> 29.18 kg) | A ("6-7 kg") / F7 | engineering-fact for the lb factor; expert-opinion for the offset (your estimate) | Yes, per machine: stand on a bathroom scale on the platform at pin 0 (setup tip); the calibration record is versioned and stored with every set (C2). |
| Machine lever ratio | 1.0 | E (F7) | assumption | Same check at two pins. |
| Belt plate inventory | asked at setup (fixtures assume 20, 15, 10, 5, 2.5 kg x2) | F7 | engineering-fact once confirmed | Edited in the equipment checklist. |
| Session tags of general sessions (`pull_heavy` >= 4 hard lats/upper-back sets, `elbow_flexor_heavy` >= 3 hard biceps sets or elbow stress >= 2, `grip_heavy` >= 2 hard sets with grip demand >= 2) | as listed | E | expert-opinion | Yes: A/B performance and elbow-check scores after general sessions near challenge sessions. |
| Protected windows: hard 24 h before A, 24 h before B/TEST (all gym), 24 h after B (P23); penalty 30 h before A and 48 h before B/TEST at readiness 0.85 (elbow tissue 0.90); no whole-body (systemic) penalty term in v1 (D15, C50) | as listed | D (C12), P23 | expert-opinion | Yes: challenge performance after general sessions at different distances. |
| Add-on blocks: after C max 6 sets, after A max 4 sets, every set RIR >= 2, 5 min buffer (room = booked minutes - TIME-1 estimate - 5 min, C49), no grip demand above 1, no lats/biceps/forearms, no grip- or elbow-flexor-heavy work | as listed | D (D5, C34, C49) | expert-opinion | Yes: session overruns and the next challenge session's performance; the room itself follows TIME-1's calibration from your logged sessions. |
| Light pull work on gap days (general sessions): only exercises with grip demand <= 1 and a biceps-group coefficient below 0.5, outside the protected windows, and only when your goals ask for it (for example reverse pec deck) | as listed | D (D15, C50) | expert-opinion | Yes: A/B performance and elbow-check scores after such sessions. |
| Item `creditRir` values (planned RIR used for coverage only) | per item | E | expert-opinion | Yes: logged RIR on those items. |
| Rest times per item (60-240 s) | per item | A (artifact) | expert-opinion | Not tuned automatically (your program); REST-1 shows them as time vs performance trade-offs (C18). |

---

## 6. Proposals: your decisions (D8)

There are 27 proposals, all recommended, so setup starts with all of them on (D35). Each is approved
or declined as a whole. Your decision is stored per proposal id, so one approval switches every rule, template or definition the
proposal lists (C48). An undecided proposal is inactive, so the program runs exactly as written for
that part. You can change a decision later in the challenge's Proposals screen; it applies from then
on. All proposals are expert opinion or engineering choices unless noted; none comes from research
on the one-arm pull-up itself (there is almost none).

### P01. Gate the weekly session-A pin decrease

An unconditional -5 lb per week is about +4.9 % one-arm load per week; gating it on completed sets and adding +5 lb after two failed weeks mirrors the program's own 90-degree-hold logic.

- Replaces: `prog.A.pin.weekly`
- Switches: prog.A.pin.gated
- Parameters: aFailsToRaise = 2
- If you decline: The pin drops 5 lb (10 lb after a controlled calibration negative) after every session A in a normal week, as written.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P02. Definition of a missed week

The program says 'missed week: no make-up, same weights' but never defines 'missed'; proposal: a session counts when at least half of its working sets were logged, and a week counts when at least 2 of the 3 required sessions count.

- Replaces: nothing (fills a gap)
- Switches: definition weekCounts
- Parameters: minSessionsForWeek = 2; sessionCountsRatio = 0.5
- If you decline: A week is missed only when no challenge session at all was completed in it.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P03. Definition of an 'easy' session C

Session C drops 5 lb after two easy sessions in a row but 'easy' is undefined; proposal: all 3 x 5 per arm done with at least 3 reps in reserve on every set (target is 2), or you tapped 'too easy'.

- Replaces: nothing (fills a gap)
- Switches: definition cEasy
- Parameters: cEasyMinRir = 3
- If you decline: Only an explicit 'too easy' tap on the exercise counts as easy.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P04. Definition of 'progress' for the stall rule

'Two test days without progress' needs a metric; proposal: e1RM +1.0 kg, or weaker-arm hang +2 s, or weaker-arm top hold +1 s, or weaker-arm least assistance 5 lb lower, versus the previous valid test.

- Replaces: nothing (fills a gap)
- Switches: definition testProgress
- Parameters: progressEpsE1rm = 1 kg; progressEpsHang = 2 s; progressEpsTopHold = 1 s; progressEpsAssist = 5 lb
- If you decline: Any strict improvement in any of those four metrics counts as progress.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P05. In-block progression for A 4 x 4 and C 4 x 5

Only B has an in-block rule; proposal: +2.5 kg next week when every set reached its target reps with at least 2 reps in reserve and no grip slip (same rule as B).

- Replaces: nothing (fills a gap)
- Switches: prog.wpu.A, prog.wpu.C
- Parameters: inBlockStepKg = 2.5 kg; inBlockMinRir = 2
- If you decline: A and C weighted loads stay fixed until the next test day.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P06. One-arm dead hang target from your latest fresh hang

The C hang (4 x 10 s per arm) is 'reviewed on test day' without a formula; proposal: floor(0.67 x weaker-arm test-day hang), at least 10 s, at most +5 s per test, at most 30 s (15 s fresh -> 10 s, as now).

- Replaces: nothing (fills a gap)
- Switches: data.test.hangTarget
- Parameters: hangTargetFraction = 0.67; hangTargetMin = 10 s; hangTargetMax = 30 s; hangTargetMaxStep = 5 s
- If you decline: The target stays 10 s; the test-day summary asks you whether to change it.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P07. Block-relative test dates

Each block is 5 weeks counted from the test actually done (matches the Todoist 'every 5 weeks' recurrence); the artifact's fixed table would make block 2 only 4 weeks after the postponed first test (F9).

- Replaces: `struct.deload.enter.fixed`
- Switches: struct.deload.enter
- If you decline: Tests follow the fixed date list from the artifact (8 Nov, 13 Dec 2026, 17 Jan, 21 Feb, 28 Mar, 2 May 2027).
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P08. Max strict reps moved to the end of the test day

A set of 18+ reps drains grip before the one-arm hang and top-hold tests; testing it last protects the gate metrics (F10).

- Replaces: nothing (fills a gap)
- Switches: template T1
- If you decline: Todoist order: 3RM, max reps, hang, top hold, least assistance.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P09. Negatives keep their own 8 s / 5 s progression

With the personal curve (D7) the 105 % formula would drop negatives from 47.5 to 42.5 kg although they progress on their own rule; proposal: the test-day recalculation never lowers them if at least one valid negative session was logged in the block, and the reset level moves up 5 kg with each increase.

- Replaces: `data.test.neg`
- Switches: data.test.neg.keep, prog.B.negReset.follow
- If you decline: On each test day negatives = 105 % of the gate e1RM (rounded down) and the reset level = that minus 2.5 kg; the reset level does not follow increases.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P10. Test-day recalculation of the anchor and A/B loads via the personal curve

The program recalculates working weights on test day without a formula for the anchor; proposal: anchor = anchorFraction x new 1RM total - bodyweight (anchorFraction frozen at import, so today's 20 kg is reproduced), kg per rep = slope of your curve at the anchor, A = anchor + 1 rep, B = anchor + 3 reps, each change clamped to +/-5 kg.

- Replaces: `data.test.loads`
- Switches: data.test.loads.curve
- Parameters: maxRecalcJumpKg = 5 kg
- If you decline: The anchor stays the last measured C load; A = anchor + 1 x 2.17 kg and B = anchor + 3 x 2.17 kg, unclamped.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P11. Pins from the least-assistance test

Proposal: A pin = weaker-arm least-assist pin + 10 lb, C pin = A + 10 lb, 90-degree hold pin = A - 5 lb (reproduces today's 40 / 50 / 35 lb pattern and the original 35 + 10 = 45 lb start).

- Replaces: nothing (fills a gap)
- Switches: data.test.pins
- Parameters: pinOffsetA = 10 lb; pinOffsetC = 10 lb; pinOffsetHold90 = -5 lb
- If you decline: Pins are not recalculated on test day; they continue from their weekly rules.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P12. In-session grind rule for B

A B set with fewer than 2 reps or 0 reps in reserve means the load is above today's capacity; taking 2.5 kg off the remaining sets avoids failure on weighted pulls (D10). Like every in-session challenge rule it applies immediately and shows 'Rule: ... · Undo' (D14, C46).

- Replaces: nothing (fills a gap)
- Switches: ins.B.wpu.grind
- Parameters: bGrindStepKg = 2.5 kg
- If you decline: No automatic change; you decide.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P13. In-session +5 lb for the A machine after a failed set

After the calibration set, a set where the weaker arm got 2 or more reps below target adds 5 lb assistance to the remaining sets, to stop grinding one-arm reps. It applies immediately and can be undone (D14, C46).

- Replaces: nothing (fills a gap)
- Switches: ins.A.oaa.fail
- Parameters: oaaFailMargin = 2
- If you decline: Only the first-set calibration adjusts the pin during the session.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P14. Injury exit needs 3 pain-free days

The elbow protocol ends after two weeks; proposal: only if the last 3 days were pain-free, otherwise extend by 7 days and advise a physiotherapist.

- Replaces: `safety.injury.exit`
- Switches: safety.injury.exit.painfree, safety.injury.extend
- Parameters: painFreeDaysToExit = 3 d; injuryExtendDays = 7 d
- If you decline: The protocol ends after exactly 14 days.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P15. Pause handling

After a pause of 7 days or more the current week repeats with the same loads and the test waits for a clean session; after 28 days or more the block-start loads are restored and the block restarts at week 1.

- Replaces: `struct.resume.interrupt`
- Switches: struct.resume.repeat, struct.resume.restart
- Parameters: pauseRepeatDays = 7 d; pauseRestartDays = 28 d
- If you decline: Any pause resumes the same week with the same loads; the test waits for a clean session.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P16. Completion with either arm

The challenge against your friend is 'one rep'; one valid rep with either arm completes it.

- Replaces: nothing (fills a gap)
- Switches: phase 3 gate (completionArms = any)
- If you decline: A valid rep with each arm is required.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P17. Three ladders in the 1-2-3 variant of B

The stall step switches B to ladders of 1-2-3 without a count; 3 ladders = 18 reps, close to the 6 x 2 = 12 reps plus the extra singles.

- Replaces: nothing (fills a gap)
- Switches: template B1
- Parameters: ladderCount = 3
- If you decline: The app asks for the number of ladders when the variant activates.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P18. Phase 2 and 3 sessions as drafts

Phases 2 and 3 are only sketched; encode drafts now (machine to finger ladder, three-angle holds, typewriters if a wide bar exists, attempts first in phase 3) and refine them with you before gate 1 can realistically pass.

- Replaces: nothing (fills a gap)
- Switches: prog.finger, template C2, template A2, template A3, template B3, template C3, template T2
- If you decline: After a gate pass the phase-1 sessions continue until the definition is revised.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P19. Test-pending weeks stay deload weeks

When the test is postponed, the waiting week keeps the deload format (half sets, same loads) so you arrive fresh at the test.

- Replaces: nothing (fills a gap)
- Switches: mode test_pending
- If you decline: Waiting weeks use the full build sessions.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P20. One-arm 5 s negative placed after least assistance on test day

The phase-1 gate needs the negative on a test day, so it is part of every test day (C65) although the Todoist order omits it; testing it right after least assistance keeps it away from the hang and top-hold tests and before max reps when P08 moves them to the end. Since C65 the definition fixes the test-day order, so this proposal confirms the place the template already gives it.

- Replaces: nothing (fills a gap)
- Switches: template T1
- If you decline: The negative keeps its place in the test template (right after least assistance); the test-day wizard has no free order (C65). Declining therefore changes nothing in practice (open issue OAP-OI-14).
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P21. Test protocols for least assistance and the 3RM

Least assistance: start at the A pin + 10 lb, -5 lb after each clean rep, stop at the first failure, max 5 attempts per arm, 3 min rest. 3RM: ramp 50 / 70 / 85 % of the expected 3RM total, then +2.5 kg attempts, max 3 working attempts, 4 min rest.

- Replaces: nothing (fills a gap)
- Switches: template T1
- If you decline: The wizard only states the goal ('least assistance for one clean rep', 'heaviest clean triple').
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P22. Plank plate size

After 30 s the program adds 'a plate on the upper back' without a size; proposal: 5 kg.

- Replaces: nothing (fills a gap)
- Switches: prog.plank
- Parameters: plankPlateKg = 5 kg
- If you decline: The app asks for the plate weight when the step is reached.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P23. 24 h protected window after B for elbow flexors

B is the heaviest elbow-flexor session (weighted 6 x 2 plus 47.5 kg negatives); no curls or heavy grip work in the 24 h after it (C12 marks this window as a proposal).

- Replaces: nothing (fills a gap)
- Switches: pw.afterB
- If you decline: No window after B.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P24. B returns to 6 x 2 after a test with progress

The ladder variant is a stall remedy; once a test shows progress again, B returns to the standard 6 x 2 progression.

- Replaces: nothing (fills a gap)
- Switches: struct.stall.revert
- If you decline: B stays on ladders until you change it.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P25. Definition of a 'clean session'

The test waits for 'a normal session without complaints'; proposal: an A, B or C session with at least 80 % of its working sets logged and no pain report.

- Replaces: nothing (fills a gap)
- Switches: struct.cleanSession
- Parameters: cleanSessionRatio = 0.8
- If you decline: Any completed A, B or C session without a pain report counts.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P26. Day of the weekly fresh two-arm hang measurement

The daily hang task says 'measure your best time fresh once a week' without a day; proposal: Monday (or the first day of the week without a gym session).

- Replaces: nothing (fills a gap)
- Switches: habit daily_hang
- Parameters: hangMeasureWeekday = MON
- If you decline: You pick the day each week (a 'measure today' toggle on the hang task).
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline

### P27. What 'light bilateral' means during the elbow protocol

The protocol says 'no unilateral work, keep training bilaterally light'; proposal: pull-ups at bodyweight, rows and dips with at least 3 reps in reserve, half the sets, prehab doubled.

- Replaces: nothing (fills a gap)
- Switches: mode injury
- If you decline: Unilateral and eccentric items are removed; the remaining items keep their loads with a 'keep it light' note.
- Recommendation: approve
- Your decision: [ ] Approve   [ ] Decline


---

## 7. Engineering interpretations (E) you can object to

These are not proposals (they add no training content), but they are choices about how to read your
program. Say so if any of them is not what you meant.

| # | Your program says | The app reads it as |
|---|---|---|
| E1 | First A set: 1-2 reps -> +5 lb, 5 or more -> -5 lb (Todoist A) | One pin serves both arms, so the **weaker arm's** reps decide. The calibration set stops with about one rep in reserve (never to failure, D10); "3 clean reps with one in reserve is right". The change applies to the remaining sets at once, with Undo (D14). |
| E2 | "Skip the negatives at any trace of elbow pain" (Todoist B) | A trace = elbow check 1 or higher on the 0-3 scale (none / trace / mild / pain). |
| E3 | "Controlled" calibration negative -> -10 lb (Todoist A) | Both arms at least 5 s, not jerky, hand did not shift. |
| E4 | "Starts from a dead hang return when the fresh one-arm hang is above 15 s on a test day" | The weaker arm must be above 15 s. The app then tells you; the exercise needs a definition update (it is not encoded yet). |
| E5 | Stall: "two test days without progress -> sleep and food; if it still does not move -> ladders + extra deload" | Stage 1 at the 2nd test without progress; stage 2 at the next test without progress. |
| E6 | Deload week: "halve the sets, keep the weight" | No load, pin or hold target increases during deload, test-pending, extra-deload and injury weeks. Protective steps back still happen there: negatives reset after a < 5 s or jerky rep, C pin +5 lb after a set under 4 reps, and (with P01) the A pin +5 lb after a second failed week. Now fixed by C47 (no longer only an interpretation). |
| E7 | Fixed test dates (only if you decline P07) | A fixed date closer than 21 days to the last valid test is skipped. |
| E8 | Plank: "when all six sets on all three days are clean" | Clean = the full target time and the hip did not tilt. No extra effort condition. |
| E9 | "After an interruption the test waits for a normal session without complaints" | Applies after every pause of at least one day (unless P15 replaces it). |
| E10 | Session C/A/B weighted loads round to "what can really be loaded at Basic-Fit" | Rounded to your own belt plates: nearest for A and B, down for negatives; the plate breakdown is shown. |

---

## 8. Test day

**When.** The Sunday of the deload week, instead of session B (A). With P07 each block is 5 weeks
counted from the test actually done; without P07 the artifact's fixed dates are used. The test is
valid for the gates only if (A) there was a clean session since any interruption, it is in a deload
or test-pending week, and you weighed in that morning. If no clean session happened, the test is
postponed to the first Sunday after one and that Sunday becomes session B in deload form (C36).

**Order (A, from the Todoist test-day task, F10; the 5 s negative added by C65).** Each test entry
is logged with "Record" (a 3RM is stored with RIR 0); warm-up sets are logged with "Done" (C63).
The test negative (step 8) is logged like every negative: lowering seconds, the `jerky` and
`hand_shift` flags and an effort chip, no RIR (C66).

| Step | Item | Protocol | Records |
|---|---|---|---|
| 1 | Weigh-in | Before the warm-up; this raw value is used for the gates (C32). | `bw` |
| 2 | Warm-up | The usual 10 minutes. | |
| 3 | Weighted 3RM | Supinated, own dip belt, first while fresh; rest 4 min. With P21: ramp 50 / 70 / 85 % of the expected 3RM total for 1-3 reps, then +2.5 kg attempts, at most 3 working attempts. | `wpu_3rm_added`, and the gate e1RM (D7) |
| 4 | Max strict reps | Supinated, bodyweight; rest 3 min. **With P08 this moves to the very end.** | `bw_max_reps` |
| 5 | One-arm dead hang, L then R | Fixed neutral handle, fresh; the clock stops when the torso starts to rotate; one attempt per arm; rest 3 min. | `oa_hang_s` L/R |
| 6 | One-arm top hold, L then R | Supinated, best of 3 attempts per arm; rest 2 min. | `oa_top_hold_s` L/R |
| 7 | Least assistance for one clean rep, L then R | Assisted machine; effective assistance = pin + the machine's own 6-7 kg. With P21: start at the A pin + 10 lb, -5 lb after each clean rep, stop at the first failure, at most 5 attempts per arm, rest 3 min. | `oa_least_assist_pin` L/R (the effective kg is also kept as `oa_least_assist_kg`, DATA_MODEL 9.1.4) |
| 8 | One-arm 5 s negative without any help, L then R | Part of every test day because gate criterion 3 needs it (C65), always right after step 7 (the definition fixes the order; P20 confirms it). The hand does not shift; skipped at an elbow trace. | `oa_neg5_unassisted` L/R |
| (phase 2+) | One-finger rep L/R; one-arm attempts L/R | Draft test template T2 (P18). | `oa_one_finger_rep`, `oa_rep` |

The grip must be the same on every test day (the `grip` parameter); a hang measured under the old
protocol (2026-08-30: supinated on the bar, until release) is never compared with the new protocol.

**After you submit** (in this order, all on the state before the test): the gate e1RM is computed
(D7); A and B loads (and with P10 the anchor) are recalculated; negatives are recalculated (A) or kept
(P09); pins are set from the least-assistance test (P11); the C hang target is updated (P06); the
dead-hang note fires if your weaker arm is above 15 s; the test is recorded and the next block
starts on Monday; the stall rule compares with the previous valid test; the phase gate is checked
with that morning's weigh-in (C62).
You see a report "old -> new" with the reason for every change, and the calendar descriptions of the
next week's sessions are updated.

**Worked example** (golden fixture GF-OAP-05; all proposals approved; k = 30, the class prior, C59): weigh-in 70.7 kg,
3RM +32.5 kg, hang L 16 / R 21 s, top hold L 1.0 / R 1.5 s, least assistance L 30 / R 25 lb,
negatives not achieved. Result: gate e1RM +39.38 kg (x1.10: +42.82; 80 % range +37.18 to +42.61);
anchor 0.8239 x 110.08 - 70.7 = 20.0 kg; slope 90.7 / 34 = 2.67 kg per rep; A 22.5 kg, B 27.5 kg
(unchanged); negatives stay 47.5 kg (P09, negatives were done in block 1), reset 45 kg; pins A 40,
C 50, 90-degree hold 35 lb (unchanged); C hang target floor(0.67 x 16) = 10 s; dead-hang note fires
(16 > 15 s); progress versus 2026-08-30 (e1RM +6.9 kg); phase 1 gate not yet met (strength 39.38 <
43.96; hang 16 < 20; negative no; top hold 1.0 < 8).

---

## 9. Sessions as encoded

### 9.1 Shared blocks

| Block | Content |
|---|---|
| Warm-up (never skipped) | 20 face pulls (cable); 10 scapular pulls on the bar; 2 ramp-up sets of pull-ups. |
| Prevention (5 min, every session) | Reverse wrist curls 2 x 15 light; dumbbell pronation/supination 2 x 10 per side; face pulls 2 x 15. Doubled in the elbow protocol. |

### 9.2 Phase-1 templates (A, with today's imported values)

**C1, volume and technique** (90 min booked; tags pull-, grip-, elbow-flexor-heavy, unilateral):
assisted one-arm pull-up 3 x 5 per arm, pin 50 lb (about 29.2 kg help), RIR 2, rest 2 min (the earlier "stop
the set when the torso rotates" was dropped 2026-10-08: the knee platform prevents rotation); weighted pull-ups 4 x 5 at +20 kg, rest 2.5 min, stop when the grip slips
(the anchor); one-arm scapular pulls 4 x 3 per arm on the neutral handle, left first, right matches
left, rest 90 s; one-arm dead hang 4 x 10 s per arm on the neutral handle, not to failure, the time
counts until the torso rotates, rest 60 s; prevention.

**A1, one arm heavy** (90 min; also tag heavy-unilateral): assisted one-arm pull-up 5 x 3 per arm,
pin 40 lb (about 24.6 kg help, 46.1 kg one-arm load at 70.7 kg), rest 2.5 min, fresh; the first set
is the calibration set; calibration negative 1 per arm, 5 s down, no help (skipped at an elbow
trace; log the lowering seconds, tick `jerky` or `hand_shift` if needed and pick an effort chip, no
RIR, C66); weighted pull-ups 4 x 4 at +22.5 kg with chalk, rest 3 min, a slip before the 4th rep ends
the set and blocks the increase; top hold 3 rounds x 4 short attempts per arm (release one hand from a
two-arm pull-up and re-grip), rest 90 s; 90-degree hold 3 x 6-8 s per arm at pin 35 lb (about 22.4
kg), rest 90 s; straight-arm hold 3 x 6 s per arm on the neutral handle, deliberately last, replaces
today's 17:15 hang; prevention.

**B1, max strength** (90 min; tags pull-, grip-, elbow-flexor-heavy): weighted pull-ups 6 x 2 at
+27.5 kg, RIR 2, rest 3 min (with the ladder variant: 3 ladders of 1-2-3, 60 s between rungs, 3 min
between ladders, P17); weighted negatives 3 x 2 at +47.5 kg, two arms, 5 s down, skipped at any elbow
trace (logged by the shortest lowering of the set in seconds, the `jerky` flag and an effort chip, no
RIR, C66); heavy pronated row 4 x 8 at RIR 1 (barbell, seated cable or chest-supported, by your
preference; load by the general engine); dips 3 x 8-10 on neutral handles at RIR 2 (first time: log
your clean bodyweight reps, the load follows); one-arm plank 3 x 20 s per side, stop when the hip
tilts; prevention.

**T1, test day**: section 8.

### 9.3 Home habits

| Habit | When | Content | Rules |
|---|---|---|---|
| Two-arm hang | Daily 17:15 at home | 4-5 sets, 2-3 min in total, pronated or neutral (never supinated), at least half on a fixed bar or handle, chalk, thumb around, stop 5-10 s before the hand slips, no one-arm hanging | Skipped on gym days (A, B, C and the test day, D51) and at an elbow trace; halved in deload weeks; off in the elbow protocol (first thing dropped). Once a week a fresh max hang is measured (day: P26, Monday). |
| One-arm plank | Tue and Thu 17:30 at home (not on a gym day, not the day before B or the test) | 3 x target per side (20 s now), stop when the hip tilts | Counts with B's plank for the 20 -> 30 s -> plate rule. |
| Sleep and protein check | Daily 21:30, only at stall stage 1 or 2 | Slept >= 8 h; protein 113-141 g (1.6-2.0 g/kg at 70.7 kg) | Artifact 09-10. |

### 9.4 Phase 2 and 3 drafts (P18)

Encoded now so the engine supports phase changes; to be refined with you before the phase-1 gate can
realistically pass. Declining P18 keeps the phase-1 sessions after a gate pass until the definition is
revised.

| Template | Change from phase 1 |
|---|---|
| C2 | Assisted one-arm on the machine while the C pin is above 0, then finger assistance (one more finger than A); typewriter pull-ups 3 x 3 per side if a wide bar exists (the Basic-Fit bar is too narrow). |
| A2 | Machine until the A pin reaches 0, then finger assistance (4 -> 3 -> 2 -> 1 fingers; one finger less after a fully completed A); holds at three angles (top, 90 degrees, 120 degrees) 2 x 5-8 s per arm instead of the machine 90-degree hold; two calibration negatives per arm. |
| A3, B3, C3 | Attempts first (3 per arm, 3 min rest, after the warm-up); weighted pull-ups reduced to 2 heavy sets (B fixed at 6 x 2 form). |
| T2 | Test day plus a one-finger rep per arm and one-arm attempts per arm. |

### 9.5 Exercises, catalog slugs and stored codes (C10, C38, C43)

Every exercise of the definition is a catalog entry (C10). Catalog slugs are snake_case, and the M1
catalog seed (ALGORITHMS_PLANNING.md section 10.8) must contain every slug below; the DATA_MODEL
migration slug list (migration 009) is the union of these slugs and the general catalog (C38).

| Kind | Slugs |
|---|---|
| Catalog references (15) | `chinup_weighted`, `neutral_grip_pullup_weighted`, `pullup_weighted` (the definition key `weighted_pullup` resolves to one of them by the `grip` parameter); `chinup_bw`, `neutral_grip_pullup_bw`, `pullup_bw` (the definition key `pullup_bw` resolves by grip, so for your supinated grip it is `chinup_bw`); `dip_neutral`, `barbell_row_pronated`, `seated_cable_row_wide_pronated`, `chest_supported_row_pronated`, `reverse_wrist_curl_db`, `db_pronation_supination`, `face_pull_cable`, `scap_pull_bar`, `pallof_press` (D40; `rower_erg` left with the warm-up row in 1.5.0, D44) |
| Challenge-local entries (14, tag `challenge`, validated like catalog rows) | `weighted_negative_pullup`, `oap_assisted_machine`, `oap_finger_assisted`, `oa_negative`, `oa_top_hold`, `oa_hold90_machine`, `oa_hold_angle`, `oa_straight_arm_hold`, `oa_scap_pull`, `oa_dead_hang`, `oap_attempt`, `typewriter_pullup`, `one_arm_plank`, `two_arm_dead_hang` |

`weighted_pullup` is a definition key, not a catalog slug (D7's `userK('weighted_pullup')` resolves
through the grip map to `chinup_weighted`).

Stored codes in the definition are the DATA_MODEL section 8 values (C38): load modes `bodyweight`,
`bodyweight_plus_external` (weighted pull-ups, negatives, plank with a plate), `assisted_machine`
(the assisted one-arm pull-up and the 90-degree hold) and `assisted_ordinal` (finger assistance); set
flags `grip_slip_before_last`, `jerky`, `torso_rotation`, `hip_rotation` and `hand_shift`; metric
codes from DATA_MODEL 9.1.4 (including the display-only `wpu_e1rm_added_m110`, `wpu_e1rm_added_hi` and
`wpu_e1rm_added_lo`; the import also uses `oa_least_assist_kg`); pain locations from DATA_MODEL
9.1.5 (`elbow_medial`, `elbow_lateral`, `elbow_anterior`; from 1.6.0 also `elbow_posterior`, `forearm`). Every hold exercise carries a hold class for
HOLD-1 (C43): grip for the one- and two-arm dead hangs, elbow for the top hold, the 90-degree hold and
the three-angle holds, shoulder for the straight-arm hold, trunk for the one-arm plank.

---

## 10. Schedule and calendar

**Preferred times** (editable in setup; the planner uses your calendar first): C Wednesday 19:45,
A Friday 19:45, B Sunday 11:00, test Sunday 11:00 (your current test is Sunday 2026-10-11 at 13:00,
taken from your calendar). Weekday sessions after dinner; weekends within the 09:00-16:00 opening
hours; Monday evenings are chess until 2026-11-30 (busy).

**Hard rules** (the planner never breaks them; events you place yourself that break them get a
warning, never an automatic move):

| Rule | Tag | Scope |
|---|---|---|
| At least one full day between sessions that load pull, grip or elbow flexors | A + D (C11, D5) | Only sessions tagged pull-, grip- or elbow-flexor-heavy. A legs/push session on a gap day is allowed, also the day before A (D15). |
| A rest day before B and before the test | A + D (C11) | All gym sessions; a run that day is only a warning. |
| At most 3 heavy unilateral sessions per week | A | Sessions tagged heavy-unilateral (A and the test). |
| The test is on Sunday | A | |

**Session length (F11, C49).** Your calendar gym events are training time (you leave before them, for
example "leave 19:35" for a 19:45 start), so a session's budget is the event length minus 5 minutes;
without an event the default is 75 minutes.

**Extra training (D5, D15, C50).** Legs or push sessions are allowed on any gap day, including the day
before A (D15); never the day before B or a test, never heavy grip or elbow-flexor work in the
protected windows (the hard 24 h before A still excludes lats, biceps and forearms), at most 4 gym
visits per week (setting) and one gym visit per day, so on a challenge day extra work is only possible
as an add-on. There is no whole-body (systemic) fatigue penalty in v1. Light pull work on a gap day is
allowed only for exercises with grip demand 1 or less and a biceps-group coefficient below 0.5 (for
example reverse pec deck), outside the protected windows, and only when your muscle goals ask for it
(C50). Optional add-on blocks at the end of C and A, with no lats, biceps or forearm work and every set
at RIR 2 or more. Their room is computed every time as booked minutes - TIME-1 estimate - 5 minutes
(C49) and follows TIME-1's calibration from your logged sessions; D5's "~35" and "~20" minutes are
indicative. The worked values are owned by ALGORITHMS_PLANNING.md 17.6 (TIME-1): with the uncalibrated
model and your 90-minute events about 24 minutes after C and about 5 minutes after A in a normal week
(below the 8-minute minimum, so no add-on after A until calibration shows more room), and about 48 and
34 minutes in a deload week.

**Calendar (D11, C37, F25).** The app recognises your existing titles in Dutch and English ("sessie
C", "volume en techniek", "sessie A", "één arm zwaar", "sessie B", "maximale kracht", "testdag",
"meten"). It writes challenge sessions and test days for the current and next ISO week into your
primary calendar with Dutch titles that match your existing events (F25: "Gym — sessie C (volume en
techniek)", "Gym — testdag 2 (meten)"; an English-title setting exists), the exercise list with today's
loads in English in the description (F25), and as the last line of the description a marker
`[fitapp:v1 <enrollment id> oap/C/2026-W42]` (C37: the challenge's enrollment id plus the slot key
`oap/<slot>/<isoYear>-W<ww>`), so it only ever edits its own events. Your hand-made events of this week
(A Thursday, test Sunday) are never edited unless you tap "Adopt" once. If a test is postponed, the app
rewrites its own test event to session B, or asks you to rename an event it does not own (C36). Your
edits win (edit policy owned by ANDROID.md 16.7): if you move an app event, the app keeps your time; if
you edit its title or description, the app stops updating that event; the app deletes only its own
future events that you have not changed; and if you delete one, it is not re-created for that week.


---

## 11. Import plan for the switch-over (D4)

*Done 2026-10-22 (D40).* The test of 11 October did not happen (elbow complaint from 8 October), so the import was the
elbow protocol from 2026-10-08 with the last pre-injury loads of 11.1, ending on the setup day into the P29 lighter week;
the tables below are the plan as written on 2026-10-07.

Until you confirm the cutover, the current chat workflow stays in charge. At cutover the app imports
your current state, you check every value, and from then on the app is the source of truth.

### 11.1 Current state to import (as of 2026-10-07, before the 11 October test)

| Field | Value | Source / note |
|---|---|---|
| Definition | `oap@1.0.0` | |
| Grip | supinated | Artifact II |
| Phase | 1 | |
| Block / week | block 1, week 5 | Block 1 started Monday 2026-08-31 (ISO 2026-W36). |
| Mode | `test_pending` | Week 5 (2026-09-28 .. 10-04) was the deload week; the 4 October test was postponed to 11 October, so this week (2026-W41) is a waiting week in deload form (with P19). |
| Week ordinal | 6 | W36 .. W41. |
| Planned test | Sunday 2026-10-11 13:00 (postponed from 2026-10-04) | Calendar; artifact 01 "Volgende meting". |
| Clean session pending | yes, unless session C on Tuesday 6 October went without complaints (you answer at import); session A on Thursday 8 October can clear it | Artifact 01 (names Wed 7 / Fri 9; your calendar moved them to Tue 6 / Thu 8). |
| Weighted loads | C 20 kg (anchor, measured 17 Sep), A 22.5 kg, B 27.5 kg (since 13 Sep), negatives 47.5 kg, reset 45 kg | Artifact 05 |
| kg per rep | 2.1667 (= 32.5 / (18 - 3)) | Artifact 05 ("2,17 kg") |
| Pins | A 40 lb, C 50 lb, 90-degree hold 35 lb | Artifact 05 |
| Hold and hang targets | straight-arm hold 6 s (8 s if the Todoist rule already moved it: you confirm), plank 20 s (30 s if already moved: you confirm), plank load 0 kg, C one-arm hang 10 s | Artifact 06, Todoist A, Core |
| B variant | 6 x 2 | |
| Counters and flags | C easy streak 0 (you may correct), A fail streak 0, tests without progress 0, stall stage 0, elbow trace no, no injury, no extra deload pending, dead-hang starts not unlocked (the 29 Sep hang was not a test day), 35 lb note not shown, finger level 4 | |
| Pre-cutover block counts | negatives done in block 1: yes (number of B sessions with negatives, at least 1); the same for the other items if known | Needed so P09 protects the 47.5 kg negatives at the 11 October test (`blockItemCounts`). |
| Strength display values | gate e1RM +39.38 kg (personal curve with the class prior k = 30, C59, from the 19 Sep 3RM; **not valid for gates**, display only); x1.10 value +42.82 kg | D7, C59 |
| anchorFraction (P10) | 0.8239 = (70.7 + 20) / 110.08, frozen | So the first recalculation reproduces 20 kg. |
| Block-1 snapshot | the values above | "Two blocks back" after an elbow protocol in blocks 1-3 returns to these values. |

### 11.2 Historical tests

| Date | What | BW | 3RM | 1RM | Max reps | One-arm hang L/R | Top hold L/R | Least assistance | Valid for gates | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-08-30 | Baseline (nulmeting) | 71.0 | +26 kg | +32 kg measured | 18 | 3 / 8 s | 0.5 / 1 s | `oa_least_assist_kg` 22.5 kg effective, both arms (about pin 35 lb) | **Yes** (test day), except the hang values | Hang under the old protocol (supinated on the bar, until release): imported with protocol code `oa_hang_bar_supinated_release_v0`, flagged "old protocol, not comparable", never used for gates or progress. The 1RM was grip-limited with a dumbbell between the ankles. Least assistance was recorded as one number without an arm or pin; imported for both arms as `oa_least_assist_kg` (C59). Gate e1RM stored with the personal curve: +32.47 kg (x1.10: +35.70 kg). |
| 2026-09-19 | 3RM re-test | 70.7 | +32.5 kg (own dip belt) | (artifact: +42.8 calculated) | | | | | **No**: "3RM only, not a test day" | Stored as measured 3RM; calculated values +39.38 (personal curve) and +42.82 (x1.10) for display. |
| 2026-09-29 | Loose measurement | | | | | 15 / 20 s (with reserve) | | | **No**: "not a test day" | New protocol (neutral handle, until the torso rotates); comparable with later tests. |
| 2026-10-11 | Test day 1 | | | | | | | | If done before cutover: valid when a clean session preceded it and you weighed in | See 11.3. |

These tests are imported as metric observations with their validity flags only (C59). No historical
set rows are created, so your rep curve (RCM-1) and therefore the D7 gate start from the class prior
k = 30 until your logged sessions give the curve informative windows.

### 11.3 Depending on when you switch

| Cutover moment | What the import does |
|---|---|
| Before Thursday 8 October | As in 11.1. Session A on Thursday is the first app session (deload form with P19). |
| Between Thursday 8 and the test | As in 11.1, with "clean session pending" from your answer about Tuesday's C and Thursday's A. |
| After the 11 October test, loads already recalculated in the chat | The test is imported as history (valid if it was); the new loads from the chat are imported as the current state; block 2, week 1 from Monday 12 October (`importedTestHandling = historyOnly`). |
| After the 11 October test, not yet recalculated | The test is imported and replayed as a test-day event after the enrollment is created, so the app's rules (with your proposal decisions) recalculate and show the report (`recalculate`). |

### 11.4 Cutover steps

1. Run the challenge setup (section 12) with "Enter my numbers" and "I'm mid-program"; check every value.
2. Check the proposals under Customize (section 6); all 27 start on (D35).
3. Calendar: allow calendar access; tap "Adopt" for this week's future hand-made events (A Thursday,
   test Sunday) so the app may keep them up to date (D11).
4. Confirm the cutover. Then, and only after your explicit confirmation in chat, Claude completes or
   deletes the Todoist session tasks (sessie A, B, C, Testdag) and the home tasks (Hangen, Core); you
   say which. The artifact is frozen as a reference page; the chat no longer updates it.
5. The home habits (daily hang 17:15, plank Tuesday/Thursday 17:30) become in-app reminders.

---

## 12. Onboarding steps (challenge setup)

| Step | Content | Required |
|---|---|---|
| 1 Overview | What the challenge is, sessions per week, typical length, the evidence note (thresholds and hold/hang gates are coach heuristics), the elbow stop signals and the 14-day protocol, "not medical advice" | yes |
| 2 Rules and parameters | Grip (supinated) | yes |
| 3 Equipment | Assisted machine: unit lb, step 5, offset 6.5 kg, ratio 1.0, maximum pin (with the bathroom-scale tip); your belt plates; wide bar (no -> typewriters excluded); chalk; home bar with neutral handles | yes |
| 4 Starting point | Import current state (section 11), enter known values, or schedule a baseline test | yes |
| 5 Import history | The historical tests with validity flags (section 11.2) | optional |
| 6 Gate method | Information about D7 (section 3) | information |
| 7 Schedule | Preferred day and time per session, habit times, reminders, calendar title language (Dutch by default for OAP, F25) | yes |
| 8 Calendar | Permission; this week's matching events with "Adopt" | yes |
| 9 Proposals | All 27, each decided (stored per proposal id, C48) | yes (every proposal) |
| 10 Review | The current and next week materialized with loads, pins, holds and rests; warnings | yes |
| 11 Cutover | Confirmation (D4) and "Start challenge" | yes |

---

## 13. Risks and safety (elbow first)

1. **The elbow decides the match.** Golfer's elbow (medial elbow pain), brachialis overload from too
   many or too fast negatives, and ulnar nerve irritation are the expected injuries (evidence thin:
   climbing-injury literature, coach reports; weak). The three stop signals start the 14-day protocol
   automatically (one tap to report; the check is asked before every pulling session and never
   skipped while the protocol rules are active, F15). Two weeks off unilateral work cost two weeks;
   training through it costs months (artifact 08).
2. **Supination.** In supination the biceps tendon at the elbow takes the largest share. All
   full-extension one-arm hanging (dead hang, scapular pulls, straight-arm hold) uses the fixed
   neutral handle; the top hold stays supinated because it is the end position (A).
3. **Negatives.** 47.5 kg added = 118.2 kg total, about 104 % of the x1.10 1RM and about 107 % of the
   personal-curve 1RM: a normal eccentric overload, but the first thing removed at any elbow trace.
   Their own 8 s / 5 s rule regulates them; P09 stops the lower personal-curve 1RM from cutting them.
4. **The weekly A pin decrease.** -5 lb per week is about +2.27 kg one-arm load per week (+4.9 %),
   +9.1 kg (+19.7 %) over four build weeks. That is faster than strength usually grows after the first
   weeks; failing reps lead to rotation and compensation through the supinated elbow. P01 gates it.
   The -10 lb step after a controlled calibration negative (+9.8 %) is above the app's generic
   load-jump cap; as you decided (D13), it runs as written and the app shows a warning (section 4.9).
5. **No failure on weighted or one-arm pulls** (D10). The calibration set stops with about one rep in
   reserve; a grip slip ends the set; P12 takes 2.5 kg off the remaining sets after a grinding B set
   (applied at once, with Undo, D14); rest-pause or to-failure sets are never prescribed. These safety
   rules stay in force even though your rules may exceed the generic load-jump caps (D13, C45).
6. **At most three heavy unilateral sessions per week** (A), whatever the general plan wants.
7. **Machine uncertainty.** The 6.5 kg offset is your estimate ("6-7 kg") and the lever ratio is
   assumed 1.0; another Basic-Fit's machine can differ. Every set stores the machine and pin;
   least-assistance results are compared only on the same machine.
8. **Test-day fatigue.** The battery is long; max reps last (P08) and the negative right after least
   assistance (C65, P20) protect the grip-limited tests.
9. **Asymmetry.** Your left side rotates more: rotation stops the set (and the hang clock), the right
   side matches the left on scapular pulls, and gates use the weaker arm.
10. **Numbers are heuristics.** The 65-75 % threshold, the 20 s hang and 8 s top-hold gates have no
    research behind them; the e1RM depends on the model (section 3). The app labels these honestly.
11. **Bodyweight.** Thresholds are a ratio of bodyweight (C32); weigh in on test days; the app asks for
    a weigh-in when the last one is older than 14 days.
12. **Not medical advice.** Persistent pain: see a physiotherapist (P14 adds an explicit prompt).

---

## 14. Golden fixtures / tests (OAP)

Shared input: the real week of CHALLENGE_ENGINE.md 14.1 (Wednesday 2026-10-07; C Tue 06-10 19:45,
A Thu 08-10 19:45, test Sun 11-10 13:00; block 1 week 5; BW 70.7 kg; C 20 / A 22.5 / B 27.5 kg,
negatives 47.5 kg; pins A 40 / C 50 / 90-degree hold 35 lb; k = 30, sd_lnk 0.30; DST ends
2026-10-25 Europe/Amsterdam). "All P" = every proposal approved; "no P" = every proposal declined.

| Id | Scenario | Expected |
|---|---|---|
| GF-OAP-01 | Import 11.1 | State as listed; snapshot 1 = imported vars; e1rmAdded +39.38, e1rmAddedM110 +42.82; anchorFraction 0.8239; validation passes. |
| GF-OAP-02 | Materialize A on Thu 2026-10-08, test pending, P19 approved | Warm-up full; oaa 3 pairs x 3 at 40 lb (24.64 kg help, 46.06 kg one-arm load); calibration negative 1 pair; wpu 2 x 4 at +22.5 kg (20 + 2.5); top hold 2 rounds x 4 attempts per arm; 90-degree hold 2 pairs x 6-8 s at 35 lb; straight-arm hold 2 pairs x 6 s; prevention 1 set each; daily hang skipped (A day). With P19 declined: the full A1. |
| GF-OAP-03 | That A completed, 100 % logged, no pain | `struct.cleanSession`: clean-session debt cleared. No load or pin changes (test-pending week). |
| GF-OAP-04 | A, first pair L 2 / R 4 reps | `ins.A.oaa.calibrate` applies +5 lb at once (weaker arm, E1): the remaining pairs show 45 lb and the card shows "Rule: Calibration set: pin +5 lb for the remaining sets · Undo" (D14, C46). Undo restores 40 lb and is logged as an override with a reason; the rule does not fire again for that item in this session. The logged pins drive the next rule. |
| GF-OAP-05 | Test Sun 2026-10-11, valid, all P (inputs in section 8) | e1RM +39.38 (x1.10 +42.82, range +37.18 .. +42.61); C 20, A 22.5, B 27.5 kg; negatives 47.5 / reset 45 kg; pins 40 / 50 / 35 lb; C hang target 10 s; dead-hang note; progress vs 2026-08-30; phase 1 kept; block 2 week 1 from Mon 2026-10-12. |
| GF-OAP-06 | Same test, no P | e1RM +39.38; A = 20 + 2.1667 -> 22.5 kg, B = 20 + 6.5 -> 27.5 kg (nearest); negatives 105 % -> 42.5 kg, reset 40 kg; pins unchanged 40 / 50 / 35; hang target unchanged 10 s. |
| GF-OAP-07 | A on Thursday skipped (no clean session); Sunday arrives | `struct.test.postpone`: Sunday is session B in deload form (wpu 3 x 2 at +27.5, negatives 2 x 2 at +47.5 unless elbow trace, row 2 x 8, dips 2 x 8-10, plank 2 x 20 s per side, prevention 1 set each); week W41 closes as test pending, block week stays 5; W42 slots C, A, TEST; if C on Wed 10-14 is clean, the test is Sun 2026-10-18. |
| GF-OAP-08 | Block 2 week 1 (W42): B Sun 10-18, all 6 x 2 at RIR >= 2, no slip, every negative >= 8 s | wpuB 30.0 kg; negatives 52.5 kg (20 + 20 + 10 + 2.5); with P09 reset 50 kg (without: 45 kg). |
| GF-OAP-09 | Same B but a grip slip on set 3 | No wpu increase (`suppressed_lock`); negatives still +5 kg. |
| GF-OAP-10 | A Fri 10-16, all 5 x 3 per arm at 40 lb, both calibration negatives 5 s clean | No P01: pin 30 lb (-10; +9.8 % one-arm load, above the generic cap, so WE-S2 WARN is shown and logged, D13, C45). With P01: 30 lb (same warning). With P01 and reps missed: 40 lb, fail streak 1; missed again the next week: 45 lb, streak 0. |
| GF-OAP-11 | C Wed 10-14 all 3 x 5 at RIR 3 (P03), C Wed 10-21 the same | Streak 1, then pin C 45 lb and streak 0 (+5.5 %: applied, WE-S2 WARN logged, D13). A C with one set of 3 reps instead: pin 55 lb. |
| GF-OAP-12 | W43 with only C done (P02 approved) | Week repeats (block week 2 again in W44), same loads, clean session required before the next test. Without P02: the week counts (one session was done). |
| GF-OAP-13 | DST: B on Sun 2026-10-25 11:00 CET | Hard all-gym window [2026-10-24T10:00Z, 2026-10-25T10:00Z) = Sat 12:00 CEST to Sun 11:00 CET; Saturday is blocked for gym (rest day before B); one `day.tick` for 25 October. |
| GF-OAP-14 | Next test date after a valid 11 October test | P07 approved: deload in W46, test Sun 2026-11-15, calendar "Gym — testdag 2 (meten)". Declined: deload in W45, test Sun 2026-11-08 (block 2 is 4 weeks). |
| GF-OAP-15 | Medial elbow ache reported at the readiness check on Wed 2026-10-21 (block 2) | Injury mode from 10-21; daily hang off; sessions without unilateral and eccentric items, prevention doubled, (P27) pull-ups at bodyweight and half sets at RIR >= 3. With P14 and no pain since 11-01: exit on Wed 11-04, loads restored to snapshot 1 (the imported values), block 3 from Mon 11-09. With pain on 11-03: extended, exit check again on 11-11. Without P14: exit on 11-04 regardless. |
| GF-OAP-16 | Two consecutive tests without progress, then a third | Stage 1 at the second (sleep and protein check habit, message); stage 2 at the third (B ladders 3 x 1-2-3; the next block starts with an extra deload week that does not count as a block week). With P24, the next test with progress returns B to 6 x 2. |
| GF-OAP-17 | Plank on B (Sun) and at home (Tue, Thu), 3 x 20 s per side each day, clean | Plank 30 s from the next week. At 30 s clean again: plate 5 kg (P22) or a question. |
| GF-OAP-18 | Calendar, week W41 and W42 (W42 projected with a valid test on 11 October: block 2 week 1, slots C, A, B) | As GF-CE-25 and GF-CE-26 (no duplicates; adopt offered for A Thursday and the test Sunday; three marked inserts for W42 with Dutch titles, F25: "Gym — sessie C (volume en techniek)", "Gym — sessie A (één arm zwaar)", "Gym — sessie B (maximale kracht)"). Each W42 insert ends its English description with the marker `[fitapp:v1 <enrollment id> oap/C/2026-W42]` (and `oap/A/2026-W42`, `oap/B/2026-W42`), C37. If the test is postponed instead (GF-OAP-07), the W42 slots are C, A, TEST and the third marker is `oap/TEST/2026-W42`. |
| GF-OAP-19 | 26-week personas (CHALLENGE_ENGINE 2.6), all P and no P | Snapshot files stable; invariants hold. |
| GF-OAP-20 | Test-pending week W41 with P19 declined (full A1 on Thu 10-08): all six straight-arm holds clean at 6 s | `prog.A.sth` is suppressed (`suppressed_lock`, `mode:test_pending`): straight-arm hold stays 6 s because progression is frozen in test-pending weeks (C47). The same session in normal week W43: 8 s. |
| GF-OAP-21 | Pain report at `elbow_anterior` on Mon 10-12 (not a stop signal) | `safety.elbow.traceFromPain` sets the elbow trace (negatives and the daily hang skipped until the next readiness check); with P14 during an injury protocol this report counts as elbow pain for the 3 pain-free days. |

---

## 15. Open issues

Status after the spec pass (DECISIONS D13-D16, F21-F27, C37-C65). Resolved items stay listed so the
history is visible.

| Id | Issue | Proposed handling |
|---|---|---|
| OAP-OI-01 | D8's wording ("executed exactly as written unless the user declines a P replacement") read inverted. | **Resolved by C48** (D8 wording corrected): the A rule runs unless its P replacement is approved; undecided proposals are inactive; decisions are stored per proposal id. |
| OAP-OI-02 | "Starts from a dead hang" (unlocked at a fresh one-arm hang above 15 s on a test day) has no prescription in the artifact. | Open. The app only notifies you (E4); the exercise needs a definition update with you when it unlocks. |
| OAP-OI-03 | The 2026-08-30 least assistance is one number without an arm. | **Resolved by C59**: imported for both arms (as `oa_least_assist_kg`, 22.5 kg). |
| OAP-OI-04 | Some current values are not in the artifact: whether Tuesday's C was clean, the straight-arm hold (6 or 8 s), the plank (20 or 30 s), the C easy streak, the number of B sessions with negatives in block 1. | Open. The import asks you for each (section 11.1). |
| OAP-OI-05 | The B row ("heavy, pronated") and the dips ("load follows from your first clean reps") have no progression rule in the artifact. | **Resolved by D49** (user, 2026-10-27): they are delegated to the general load engine (RX-1, C15), where the generic caps and C45's one-step DOUBLE exception apply; built in M3. |
| OAP-OI-06 | The deload interpretation E6 (no increases in deload/test-pending weeks, protective steps back allowed) and the other E items in section 7. | E6 **resolved by C47** (frozen progression in deload, test-pending, extra-deload and injury weeks, except `allowInFrozen` rules). E1-E5 and E7-E10 stay open for your objection. |
| OAP-OI-07 | Phase 2/3 drafts (P18) are sketches. | Open. Refine together before the phase-1 gate is realistically within reach. |
| OAP-OI-08 | Your belt plate inventory and whether you ever hang a dumbbell on the belt are unknown; the fixtures assume a 2.5 kg grid. | Open. Asked in the equipment step. |
| OAP-OI-09 | Is the assisted machine always the same one? | Open. Per-machine calibration; least-assistance comparisons only on the same machine. |
| OAP-OI-10 | Language of the calendar titles the app writes. | **Resolved by F25**: Dutch titles for OAP (matching your events), English descriptions; an English-title setting remains. |
| OAP-OI-11 | Engine-level items that touch this program: add-on time (F11 vs D5, CE-OI-05), the in-session suggestion limit (C16, CE-OI-04), catalog ids (CE-OI-08), whether imported history feeds the curve (CE-OI-09). | **Resolved**: add-on time by C49 (budget = event - 5 min; worked values in ALGORITHMS_PLANNING.md 17.6), the in-session limit by D14/C46 (auto-apply with Undo, once per rule per item), catalog ids by C38 (slugs listed in section 9.5), imported history by C59 (no set rows; class prior k = 30). |
| OAP-OI-12 | New conflict with DATA_MODEL.md 13.2 (migration 009) and 19.6 (fixture identifiers): the slug list contains `weighted_pullup`, which is a definition key, not a catalog slug (C38), and the fixtures derive a UUIDv5 from `exercise:weighted_pullup`. | **Resolved** (DATA_MODEL 13.2 and 19.6 now use the catalog slug `chinup_weighted`; DATA_MODEL issue 3 closed by C38). |
| OAP-OI-13 | New conflict with DATA_MODEL.md 19.7 (real-week fixture): the 2026-09-19 observation stores `wpu_e1rm_added` = 42.80 kg with derivation `calculated:multiplier3rm`, and the fixture enrollment state has `e1rmAdded: 42.8` and no `e1rmAddedM110`. Under D7 the gate code and the state var `e1rmAdded` hold the personal-curve value (+39.38 kg, k = 30, C59; display only, not valid for gates), and the x1.10 value (+42.82 kg) belongs in `wpu_e1rm_added_m110` / `e1rmAddedM110` (sections 11.1, 11.2; GF-OAP-01). | **Resolved** (DATA_MODEL 19.7 now stores +39.38 kg personal-curve and +42.82 kg x1.10 as in sections 11.1 and 11.2). |
| OAP-OI-14 | P20 after C65: the test-day order is now fixed by the definition (C65; CHALLENGE_ENGINE.md 2.4.7 has no free order), so the T1 negative sits right after least assistance whether P20 is approved (`after:assist`) or declined (`inPlace`, CE-OI-15, GF-CE-38). P20 no longer changes anything. | **Resolved by D33**: P20 stays in `oap@1.0.0` (no effect either way), so the 27 proposal ids referenced by UX.md and CHALLENGE_ENGINE.md remain valid; retiring it waits for a later definition version. |
| OAP-OI-15 | New conflict with CHALLENGE_ENGINE.md 6.10 (behaviour point 3): only notify-only rules are exempt from "at most once per item per session", but `ins.scap.matchLeft` (a `capTarget` rule) must act after each of the 4 left scapular-pull sets so that every right set matches its left set. | **Resolved by C71**: `capTarget` and notify-only rules may act after every set; the once-per-item limit applies only to rules that change load or reps. |
| OAP-OI-16 | D13/C45 and the plank plate: `prog.plank` adds a 5 kg plate (P22) to a hold whose bodyweight fraction is 0, so the relative load-jump cap of ALGORITHMS.md B9.14 (`w <= w_last x 1.05`) is undefined from 0 kg, and B9.11 does not say whether WE-S2 is evaluated for hold items. | **Resolved by C70**: WE-S2 is not evaluated for HOLD-1 items; the plank plate step follows the hold rules and is explained in the Why sheet. |
| OAP-OI-17 | Other documents' open issues about this definition: CHALLENGE_ENGINE CE-OI-13 (P12/P13 texts as "one-tap" suggestions) and CE-OI-15 (old vocabulary, `{linkUuid}`, `afterAssist`/`freeOrder`, missing `holdClass`); ANDROID OI-29 (marker template uses the `calendar_link` id); ALGORITHMS_PLANNING OI-01 (semicolon marker format); DATA_MODEL open issue 3 (definition vocabulary). | **Closed (informational).** Resolved in this definition version by C37, C38, C43, C65 and D14; the remaining parts belong to UX 11.10, the DATA_MODEL registry and DATA_MODEL issue 3 (closed with OAP-OI-12). |

---

## 16. Implementation notes for Claude Code

- The definition is `docs/challenges/oap.challenge.v1.json` (valid JSON; 27 proposals, 43 rules, 8
  in-session rules). Copy it to `algo/src/main/resources/fitness/challenges/oap.challenge.v1.json` at
  M2 (bundled data lives under `resources/fitness/`, C40, CHALLENGE_ENGINE.md 2.1);
  validate with `sim challenge validate`; publish with a reviewed migration (F3) as
  `challenge_definition:oap@1.0.0` with its RFC 8785 JCS hash.
- Changes made to the earlier draft during this specification (all inside the JSON): pain kinds use
  the canonical symptom codes (`medial_elbow_ache_after`); equipment ids `dip_station` and
  `home_bar`; marker line with the human line and default title locale `nl`; the plank rule no longer
  has an effort condition (D8: A rules as written); the A calibration note says "never to failure"
  (D10); `allowInFrozen` on three protective rules; pause handling as rules (`struct.resume.*`, P15)
  instead of hidden engine behaviour (D3); `anchorFraction` marked static; import fields
  `blockItemCounts` and `importedTestHandling`.
- Changes after the spec pass (2026-10-07; DECISIONS D13-D16, F25, C37-C65), all inside the JSON
  and still version 1.0.0 because nothing is published yet:
  - C38 vocabulary: set flags in snake_case (`grip_slip_before_last`, `torso_rotation`,
    `hip_rotation`, `hand_shift`, `jerky`) in `flagsOffered`, `stopSetOn`, `clockStopsOn` and every
    FitExpr (`ex.wpu.grip_slip_before_last`, `set.torso_rotation`, ...); load modes
    `bodyweight_plus_external` and `assisted_machine`; the two-arm hang's catalog grip is `pronated`
    (E43 has no "pronated or neutral"); pain locations use the DATA_MODEL body regions
    (`elbow_medial`, `elbow_lateral`, `elbow_anterior`) in `safety.elbow.traceFromPain` and in the
    P14 `daysSincePain` checks. Metric codes already matched DATA_MODEL 9.1.4. The rule id
    `prog.gripSlip.lock` keeps its camelCase part (rule ids are not stored codes).
  - C43: `holdClass` on every hold exercise (grip, elbow, shoulder, trunk; section 9.5).
  - C37: `writeBack.marker` documents `[fitapp:v1 {enrollmentId} {slug}/{slot}/{isoWeek}]` as the
    last description line, with the edit policy of ANDROID.md 16.7; F25: Dutch titles, English
    descriptions (the Dutch human line was removed).
  - D14/C46: the in-session `suggest` actions are applied automatically with Undo (engine semantics
    in CHALLENGE_ENGINE.md 6.10); proposal texts and messages reworded accordingly.
  - D13/C45: tag legend states that generic caps do not limit RULE loads (WE-S2 WARN instead);
    section 4.9 lists the affected rules.
  - C47: `test_pending` and `extra_deload` declare `progression: frozen` explicitly.
  - C48: tag legend states decisions per proposal id. C49: add-on notes point to
    ALGORITHMS_PLANNING.md 17.6. C50/D15: `generalSessions` note (the light-pull filter is engine
    behaviour, CHALLENGE_ENGINE.md 8.4). C59: import note (observations only, class prior k = 30,
    least assistance for both arms). C65: T1 source names the 5 s negative.
  - C65 and CHALLENGE_ENGINE.md 2.4.7 (closes CE-OI-15): the T1 negative's position uses the CDS
    values `after:assist` (P20 approved) and `inPlace` (declined) instead of `afterAssist` /
    `freeOrder`; the P20 texts no longer offer a free order. D14: the `oaaFailMargin` tuning source is
    the Undo rate of the automatically applied step, not a suggestion acceptance rate.
- Keep the cutover import as data: `algo/src/test/resources/fixtures/oap/cutover_2026-10-07.json`
  (the values of 11.1 and 11.2), reused by the setup wizard's prefill and by tests.
- Tests (JUnit 5): `OapImportTest` (GF-OAP-01), `OapMaterializeTestPendingTest` (02),
  `OapCleanSessionTest` (03), `OapCalibrationRuleTest` (04), `OapTestDayRecalcTest` (05, 06),
  `OapPostponeTest` (07), `OapBProgressionTest` (08, 09), `OapAPinTest` (10), `OapCPinTest` (11),
  `OapMissedWeekTest` (12), `OapDstWindowTest` (13), `OapTestDateBasisTest` (14),
  `OapElbowProtocolTest` (15), `OapStallTest` (16), `OapPlankTest` (17), `OapCalendarWeekTest` (18),
  `OapPersonaGoldenTest` (19), `OapFrozenTestPendingTest` (20), `OapPainLocationTest` (21). `OapAPinTest` and
  `OapCPinTest` also assert the WE-S2 WARN entries of D13 (including the 45 -> 40 lb steps of
  section 4.9); `OapCalibrationRuleTest` covers the automatic application and the Undo of D14.
- Never hardcode an OAP value in Kotlin; everything above comes from the definition or the import.
