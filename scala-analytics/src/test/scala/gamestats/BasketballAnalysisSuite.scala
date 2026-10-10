package gamestats

import java.nio.file.{Files, Path}
import io.circe.{Json, parser}
import io.circe.syntax.*
import JsonCodec.given

class BasketballAnalysisSuite extends munit.FunSuite {
  private def fixture(n: Int): MatchBatch = JsonCodec.decodeBatch(
    Files.readString(Path.of(s"testdata/basket-$n.json"))).toOption.get
  private def result(b: MatchBatch): BasketballResult = BasketballAnalysis.analyze(b).toOption.get
  private def data(text: String): Json = parser.parse(text).toOption.get
  private def field(e: Event, k: String, v: Json): Event =
    e.copy(data = Json.fromJsonObject(e.data.asObject.get.add(k, v)))
  private val base = fixture(1)
  // Rebuild valid envelopes and final scores so edge tests exercise the intended rule.
  private def build(actions: Vector[Event]): MatchBatch = {
    val scores = Vector("b1-home", "b1-away").map { team =>
      val points = actions.filter(_.`type` == "SHOT_ATTEMPTED").map(e => JsonCodec.payload[Shot](e).toOption.get)
        .filter(s => s.teamId == team && s.made).map(_.shotValue).sum
      Json.obj("teamId" -> Json.fromString(team), "points" -> Json.fromInt(points))
    }
    val finish = base.events.last.copy(data = Json.obj("scores" -> Json.fromValues(scores)))
    base.copy(events = (Vector(base.events.head) ++ actions ++ Vector(finish)).zipWithIndex.map { (e, i) =>
      e.copy(sequence = i.toLong + 1, timestamp = "2026-09-01T12:00:00Z")
    })
  }
  private def shot(id: String, value: Int, made: Boolean = true, away: Boolean = false): Event =
    base.events(1).copy(eventId = id, playerId = Some(if (away) "b1-a1" else "b1-h1"),
      data = data(s"""{"teamId":"${if (away) "b1-away" else "b1-home"}","period":1,"periodTimeRemainingMs":0,"shotValue":$value,"made":$made}"""))
  private def turnover(id: String): Event = base.events(1).copy(eventId = id, `type` = "TURNOVER_COMMITTED",
    data = data("""{"teamId":"b1-home","period":1,"reason":"bad pass"}"""))

  test("three Go fixtures match independently calculated complete expected JSON") {
    (1 to 3).foreach { n =>
      val expected = parser.parse(Files.readString(Path.of(s"testdata/basket-$n.expected.json"))).toOption.get
      assertEquals(result(fixture(n)).asJson, expected)
    }
  }
  test("basket-1 counts assisted points once, credits assister and defensive rebound") {
    val r = result(base)
    assertEquals(r.teams.map(_.points), Vector(2L, 0L))
    assertEquals(r.players.head.assistedBasketPoints, 2L)
    assertEquals(r.players(1).pointsCreatedByAssists, 2L)
    assertEquals(r.players.head.defensiveRebounds, 1)
    assertEquals(r.players(2).threePointers.percentage, Some(0.0))
    assertEquals(r.players(3).fieldGoals.percentage, None)
  }
  test("basket-2 detects exactly eight points; basket-3 identifies fifth foul moment") {
    val run = result(fixture(2)).scoringRuns.head
    assertEquals(run.points, 8L)
    assertEquals(run.thresholdEventId, "b2-e4")
    val p = result(fixture(3)).players.head
    assertEquals(p.fouls, 5)
    assert(p.excluded)
    assertEquals(p.fifthFoul, Some(FifthFoul("b3-e6", 6L, "2026-09-01T12:00:16Z", 4)))
  }
  test("field goals include triples and exclude free throws; null differs from zero") {
    val r = result(build(Vector(shot("s1", 2), shot("s2", 2, false), shot("s3", 3),
      shot("s4", 3, false), shot("s5", 1), shot("s6", 1, false), turnover("t"))))
    val p = r.players.head
    assertEquals(p.points, 6L)
    assertEquals(p.fieldGoals, ShootingStats(2, 4, Some(50.0)))
    assertEquals(p.threePointers, ShootingStats(1, 2, Some(50.0)))
    assertEquals(p.freeThrows, ShootingStats(1, 2, Some(50.0)))
    assertEquals(p.turnovers, 1)
    assertEquals(r.teams.head.fieldGoals, p.fieldGoals)
    assert(r.players.tail.forall(_.freeThrows.percentage.isEmpty))
    assertEquals(result(build(Vector.empty)).scoringRuns, Vector.empty)
  }
  test("runs survive misses, turnovers, fouls and period boundaries; extend once and split on rival free throw") {
    val foul = fixture(3).events(1).copy(eventId = "f", gameId = base.gameId, gameVersion = base.gameVersion,
      matchId = base.matchId, playerId = Some("b1-h1"), data = data(
        """{"teamId":"b1-home","period":1,"affectedPlayerId":"b1-a1","reason":"personal"}"""))
    val later = field(shot("s4", 3), "period", Json.fromInt(2))
    val actions = Vector(shot("s1", 3), shot("miss", 3, false, true), turnover("t"), foul,
      shot("s2", 3), shot("s3", 2), later, shot("rival", 1, true, true), shot("s5", 3), shot("s6", 3))
    val runs = result(build(actions)).scoringRuns
    assertEquals(runs.size, 1)
    assertEquals(runs.head.points, 11L)
    assertEquals(runs.head.shotEventIds, Vector("s1", "s2", "s3", "s4"))
    assertEquals(runs.head.thresholdEventId, "s3")
    assertEquals(result(build(Vector(shot("a", 3), shot("b", 3), shot("c", 1)))).scoringRuns.size, 0)
  }
  test("two qualifying runs are distinct after opposing score") {
    val shots = Vector(shot("a", 3), shot("b", 3), shot("c", 2), shot("d", 3, true, true),
      shot("e", 3, true, true), shot("f", 2, true, true))
    assertEquals(result(build(shots)).scoringRuns.map(_.points), Vector(8L, 8L))
  }
  test("assists use referenced older shot, disallow miss, free throw, self, wrong scorer and duplicates") {
    val assist = base.events(2)
    val older = build(Vector(base.events(1), shot("new", 3), assist))
    assertEquals(result(older).players(1).pointsCreatedByAssists, 2L)
    val invalid = Vector(field(assist, "shotEventId", Json.fromString("missing")),
      field(assist, "scorerId", Json.fromString("b1-a1")), assist.copy(playerId = Some("b1-h1")),
      field(assist, "period", Json.fromInt(2)))
    invalid.foreach(a => assert(BasketballAnalysis.analyze(build(Vector(base.events(1), a))).isLeft))
    Vector(shot("b1-e2", 1), shot("b1-e2", 2, false)).foreach(s =>
      assert(BasketballAnalysis.analyze(build(Vector(s, assist))).isLeft))
    assert(BasketballAnalysis.analyze(build(Vector(base.events(1), assist, assist.copy(eventId = "again")))).isLeft)
    assert(BasketballAnalysis.analyze(build(Vector(assist, base.events(1)))).isLeft)
  }
  test("rebounds require a prior miss, correct side and period, and unique reference") {
    val miss = base.events(3)
    val rebound = base.events(4)
    val offensive = field(rebound.copy(playerId = Some("b1-a2")), "teamId", Json.fromString("b1-away"))
    val off = field(offensive, "reboundType", Json.fromString("OFFENSIVE"))
    assertEquals(result(build(Vector(miss, off))).players(3).offensiveRebounds, 1)
    Vector(build(Vector(field(miss, "made", Json.True), rebound)), build(Vector(miss, offensive)),
      build(Vector(miss, field(rebound, "period", Json.fromInt(2)))),
      build(Vector(miss, rebound, rebound.copy(eventId = "again"))), build(Vector(rebound, miss)))
      .foreach(b => assert(BasketballAnalysis.analyze(b).isLeft))
  }
  test("every action including sixth foul is rejected after fifth foul; other players may act") {
    val b = fixture(3)
    val f = b.events(5)
    val extra = f.copy(eventId = "sixth", sequence = 7, timestamp = b.events.last.timestamp)
    val bad = b.copy(events = b.events.dropRight(1) ++ Vector(extra, b.events.last.copy(sequence = 8)))
    assert(BasketballAnalysis.analyze(bad).isLeft)
    val turnover = extra.copy(`type` = "TURNOVER_COMMITTED", data = data(
      """{"teamId":"b3-home","period":4,"reason":"bad pass"}"""))
    assert(BasketballAnalysis.analyze(bad.copy(events = bad.events.updated(6, turnover))).isLeft)
    assert(BasketballAnalysis.analyze(bad.copy(events = bad.events.updated(6, turnover.copy(playerId = Some("b3-h2"))))).isRight)
  }
  test("reject invalid envelope, participants, final score and unknown fields") {
    val score = field(base.events.last, "scores", data("""[{"teamId":"b1-home","points":3},{"teamId":"b1-away","points":0}]"""))
    val invalid = Vector(base.copy(events = Vector.empty), base.copy(events = base.events.dropRight(1)),
      base.copy(events = base.events.updated(1, base.events(1).copy(sequence = 99))),
      base.copy(events = base.events.updated(1, base.events(1).copy(timestamp = "bad"))),
      base.copy(events = base.events.updated(1, base.events(1).copy(timestamp = "2026-09-01T12:00:00+01:00"))),
      base.copy(events = base.events.updated(1, base.events(1).copy(matchId = "other"))),
      base.copy(events = base.events.updated(1, base.events(1).copy(playerId = None))),
      base.copy(events = base.events.updated(0, field(base.events.head, "periods", Json.fromInt(0)))),
      base.copy(events = base.events.updated(1, field(base.events(1), "extra", Json.True))),
      base.copy(events = base.events.updated(5, score)))
    invalid.foreach(b => assert(BasketballAnalysis.analyze(b).isLeft))
  }
  test("invalid shot values, periods, team and reasons fail") {
    Vector(field(shot("x", 2), "shotValue", Json.fromInt(4)),
      field(shot("x", 2), "period", Json.fromInt(5)),
      field(shot("x", 2), "periodTimeRemainingMs", Json.fromInt(-1)),
      field(shot("x", 2), "teamId", Json.fromString("b1-away")),
      field(turnover("t"), "reason", Json.fromString(" "))).foreach { e =>
        val b = base.copy(events = Vector(base.events.head, e.copy(sequence = 2), base.events.last.copy(sequence = 3)))
        assert(BasketballAnalysis.analyze(b).isLeft)
      }
  }
}
