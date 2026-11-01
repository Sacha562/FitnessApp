package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.OapFixture.row
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** GF-CE-24, 24b and the other OAP in-session rules. */
class InSessionRuleTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val date = LocalDate.parse("2026-10-14")
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.4.json")) }

    private fun live(state: EnrollmentState, slot: String, key: String): LiveItem {
        val plan = Materializer.materialize(def, params, MaterializeInput(slot, date, state, OapFixture.facts, catalog, changeoverMin = 5))
        val item = plan.items.first { it.key == key }
        return LiveItem(item, item.rows, emptyList())
    }

    private fun log(item: LiveItem, state: EnrollmentState, slot: String, r: RowLog): InSessionRuleRunner.Result =
        InSessionRuleRunner.onRowLogged(def, params, state, OapFixture.facts, date, slot, item.copy(logged = item.logged + r))

    private fun normal(except: Map<String, Boolean> = emptyMap()) =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false), except = except)

    private fun pair(item: LiveItem, s: EnrollmentState, index: Int, l: Int, r: Int, pin: String): Pair<LiveItem, List<RuleApplication>> {
        val a = log(item, s, "A", row("L", index, reps = l, rir = "1", pin = pin))
        val b = log(a.item, s, "A", row("R", index, reps = r, rir = "1", pin = pin))
        return b.item to (a.applications + b.applications)
    }

    @Test
    fun `GF-CE-24 calibration applies at once and Undo sticks`() {
        val s = normal(except = mapOf("P13" to false))
        val start = live(s, "A", "oaa")
        val (after, apps) = pair(start, s, 1, l = 2, r = 4, pin = "40")
        val app = apps.single { it.kind == "suggest" }
        app.ruleId shouldBe "ins.A.oaa.calibrate"
        app.msg shouldBe "calibration_adjust"
        app.msgParams["value"] shouldBe "45 lb"
        after.rows.drop(2).map { it.pin } shouldBe List(8) { BigDecimal(45) }
        after.rows.drop(2).all { it.changedByRule == "ins.A.oaa.calibrate" } shouldBe true
        after.rows.take(2).map { it.pin } shouldBe listOf(BigDecimal(40), BigDecimal(40))
        val undone = InSessionRuleRunner.undo(def, params, s, OapFixture.facts, date, after, app)
        undone.rows.drop(2).map { it.pin } shouldBe List(8) { BigDecimal(40) }
        // The undone rule does not fire again on this item, even after another weak pair.
        val (again, apps2) = pair(undone, s, 2, l = 1, r = 1, pin = "40")
        apps2.none { it.kind == "suggest" } shouldBe true
        again.rows.drop(4).map { it.pin } shouldBe List(6) { BigDecimal(40) }
    }

    @Test
    fun `GF-CE-24b two different rules each apply once`() {
        val s = normal()
        var item = live(s, "A", "oaa")
        val (p1, a1) = pair(item, s, 1, l = 2, r = 4, pin = "40")
        a1.single { it.kind == "suggest" }.ruleId shouldBe "ins.A.oaa.calibrate"
        val (p2, a2) = pair(p1, s, 2, l = 3, r = 3, pin = "45")
        a2.none { it.kind == "suggest" } shouldBe true
        val (p3, a3) = pair(p2, s, 3, l = 1, r = 3, pin = "45")
        a3.single { it.kind == "suggest" }.ruleId shouldBe "ins.A.oaa.fail"
        p3.rows.drop(6).map { it.pin } shouldBe List(4) { BigDecimal(50) }
        item = p3
        val (_, a4) = pair(item, s, 4, l = 1, r = 1, pin = "50")
        a4.none { it.kind == "suggest" } shouldBe true
        // Undo of the calibration takes back its own +5 lb only; the failure rule's +5 lb stays.
        val undone = InSessionRuleRunner.undo(def, params, s, OapFixture.facts, date, p3, a1.single { it.kind == "suggest" })
        undone.rows.drop(6).map { it.pin } shouldBe List(4) { BigDecimal(45) }
        undone.rows.drop(6).all { it.changedByRule == "ins.A.oaa.fail" } shouldBe true
        undone.rows.drop(6).map { it.assistKg } shouldBe p1.rows.drop(6).map { it.assistKg }
        undone.rows.subList(2, 6).map { it.pin } shouldBe List(4) { BigDecimal(40) }
    }

    @Test
    fun `the right side is capped at the left side's reps`() {
        val s = normal()
        val scap = live(s, "C", "scap")
        val r = log(scap, s, "C", row("L", 1, reps = 2, rir = "2"))
        r.applications.single().kind shouldBe "capTarget"
        r.item.rows[1].targetReps shouldBe 2
    }

    @Test
    fun `a scap rotation on the left caps the right side, on the right it carries over to nothing (D52)`() {
        val s = normal()
        val scap = live(s, "C", "scap")
        val left = log(scap, s, "C", row("L", 1, reps = 2, rir = "2", flags = listOf("torso_rotation")))
        left.applications.map { Triple(it.ruleId, it.kind, it.msg ?: it.to) } shouldBe listOf(
            Triple("ins.rotationStop.scapL", "notify", "rotation_stop_set"),
            Triple("ins.scap.matchLeft", "capTarget", "2"),
        )
        val right = log(left.item, s, "C", row("R", 1, reps = 1, rir = "2", flags = listOf("torso_rotation")))
        right.applications.map { it.ruleId to it.msg } shouldBe listOf("ins.rotationStop.scapR" to "rotation_stop_scap_right")
        right.item.rows.drop(2) shouldBe scap.rows.drop(2)
    }

    @Test
    fun `B grind takes 2_5 kg off the remaining sets`() {
        val s = normal()
        val wpu = live(s, "B", "wpu")
        val r = log(wpu, s, "B", row("both", 1, reps = 1, rir = "0", added = "27.5"))
        val app = r.applications.single { it.kind == "suggest" }
        app.ruleId shouldBe "ins.B.wpu.grind"
        r.item.rows.drop(1).map { it.addedKg } shouldBe List(5) { BigDecimal(25) }
    }

    @Test
    fun `a grip slip and a torso rotation show their instructions`() {
        val s = normal()
        val wpu = live(s, "A", "wpu")
        log(wpu, s, "A", row("both", 1, reps = 3, rir = "1", added = "22.5", flags = listOf("grip_slip_before_last"))).applications
            .map { it.ruleId to it.msg } shouldBe listOf("ins.wpu.gripStop" to "grip_slip_stop")
        val hang = live(s, "C", "hang")
        log(hang, s, "C", row("L", 1, hold = "8", reserve = "2", flags = listOf("torso_rotation"))).applications
            .map { it.ruleId to it.msg } shouldBe listOf("ins.rotationStop.hang" to "rotation_stop_hang")
        val sth = live(s, "A", "sth")
        log(sth, s, "A", row("L", 1, hold = "5", reserve = "2", flags = listOf("torso_rotation"))).applications
            .map { it.ruleId to it.msg } shouldBe listOf("ins.rotationStop.sth" to "rotation_stop_sth")
    }
}
