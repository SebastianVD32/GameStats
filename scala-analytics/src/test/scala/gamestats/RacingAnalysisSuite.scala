package gamestats

import java.nio.file.{Files, Path}
import io.circe.{Json, parser}
import io.circe.syntax.*
import JsonCodec.given

class RacingAnalysisSuite extends munit.FunSuite {
  private def json(s: String): Json = parser.parse(s).toOption.get
  private def fixture(n: Int): MatchBatch = JsonCodec.decodeBatch(Files.readString(Path.of(s"testdata/race-$n.json"))).toOption.get
  private def result(b: MatchBatch): RacingResult = RacingAnalysis.analyze(b).fold(e => fail(e), identity)
  private def correctedThird: MatchBatch = {
    val b = fixture(3)
    b.copy(events = b.events.init :+ b.events.last.copy(data = json(
      """{"classification":[{"playerId":"race-erin","position":1},{"playerId":"race-alice","position":2},{"playerId":"race-dan","position":3}]}""")))
  }
  private def field(e: Event, key: String, value: Json): Event = e.copy(data = Json.fromJsonObject(e.data.asObject.get.add(key, value)))
  private def action(id: String, kind: String, payload: String): Event =
    Event("unused", "2026-09-01T12:00:00Z", "RACING", "1.0", "edge", 1, Some(id), kind, json(payload))
  private def lap(id: String, n: Int, ms: Long, position: Int) = action(id, "LAP_COMPLETED",
    s"""{"lapNumber":$n,"lapTimeMs":$ms,"position":$position}""")
  private def penalty(id: String, kind: String, value: Long) = action(id, "PENALTY_APPLIED",
    s"""{"penaltyType":"$kind","value":$value,"reason":"test","lapNumber":1}""")
  private def entry(id: String, stop: String, n: Int = 1) = action(id, "PIT_ENTERED",
    s"""{"stopId":"$stop","lapNumber":$n,"reason":"tires"}""")
  private def exit(id: String, stop: String, n: Int = 1) = action(id, "PIT_EXITED",
    s"""{"stopId":"$stop","lapNumber":$n,"tireCompound":"SOFT"}""")
  private def build(actions: Vector[Event], order: Vector[String] = Vector("a", "b", "c"), laps: Int = 1): MatchBatch = {
    val start = action("a", "MATCH_STARTED", s"""{"driverIds":["a","b","c"],"plannedLaps":$laps}""").copy(playerId = None)
    val finish = start.copy(`type` = "MATCH_FINISHED", data = Json.obj("classification" -> Json.fromValues(
      order.zipWithIndex.map((id, i) => Json.obj("playerId" -> Json.fromString(id), "position" -> Json.fromInt(i + 1))))))
    MatchBatch("RACING", "1.0", "edge", (Vector(start) ++ actions ++ Vector(finish)).zipWithIndex.map { (e, i) =>
      e.copy(eventId = s"e$i", sequence = i.toLong + 1, timestamp = s"2026-09-01T12:00:${f"$i%02d"}Z")
    })
  }
  private val basic = Vector(lap("a", 1, 100, 1), lap("b", 1, 200, 2), lap("c", 1, 300, 3))
  private def reject(b: MatchBatch): Unit = assert(RacingAnalysis.analyze(b).isLeft)

  test("Go race-1 and race-2 plus corrected race-3 match complete hand-calculated JSON") {
    (1 to 3).foreach(n => assertEquals(result(if (n == 3) correctedThird else fixture(n)).asJson,
      json(Files.readString(Path.of(s"testdata/race-$n.expected.json")))))
  }
  test("unchanged Go race-3 reports the inconsistent final order") {
    val error = RacingAnalysis.analyze(fixture(3)).swap.toOption.get
    assert(error.contains("Classification inconsistency"))
    assert(error.contains("ClassificationEntry(race-alice,2)"))
    assert(error.contains("ClassificationEntry(race-dan,3)"))
  }
  test("Go pit duration is reported but never added to total; fastest lap ignores penalties") {
    val carol = result(fixture(2)).drivers.head
    assertEquals(carol.totalTimeMs, BigInt(95000))
    assertEquals(carol.stops.head.durationMs, 1000L)
    val r = result(correctedThird)
    assertEquals(r.classification.map(_.playerId), Vector("race-erin", "race-alice", "race-dan"))
    assertEquals(r.fastestLaps.head.playerId, "race-dan")
    assertEquals(r.drivers(1).bestLapTimeMs, 88000L)
    assert(r.drivers.forall(!_.comeback))
  }
  test("multiple laps compute best, average, total and all tied fastest laps") {
    val r = result(build(Vector(lap("a", 1, 100, 3), lap("b", 1, 100, 2), lap("c", 1, 300, 1),
      lap("a", 2, 150, 1), lap("b", 2, 200, 2), lap("c", 2, 100, 3)), laps = 2))
    assertEquals(r.drivers.head.averageLapTimeMs, 125.0)
    assertEquals(r.drivers.head.totalTimeMs, BigInt(250))
    assertEquals(r.drivers.head.bestLapTimeMs, 100L)
    assertEquals(r.fastestLaps.size, 3)
    assert(r.drivers.head.comeback)
    assert(!r.drivers(1).comeback)
  }
  test("raw and adjusted ties use final lap position, not participant or event order") {
    val r = result(build(Vector(lap("a", 1, 100, 3), lap("b", 1, 100, 2), lap("c", 1, 100, 1)), Vector("c", "b", "a")))
    assertEquals(r.classificationWithoutPenalties.map(_.playerId), Vector("c", "b", "a"))
    val adjusted = result(build(Vector(lap("a", 1, 100, 2), lap("b", 1, 200, 1), lap("c", 1, 300, 3),
      penalty("a", "TIME", 100)), Vector("b", "a", "c")))
    assertEquals(adjusted.drivers.head.adjustedTimeMs, BigInt(200))
    assertEquals(adjusted.drivers.head.finalPosition, 2)
  }
  test("time sanctions precede position sanctions even if the latter event comes first") {
    val r = result(build(basic ++ Vector(penalty("b", "POSITION", 1), penalty("a", "TIME", 500)), Vector("c", "b", "a")))
    assertEquals(r.classification.map(_.playerId), Vector("c", "b", "a"))
    assert(r.drivers(2).comeback)
    assertEquals(r.drivers(2).positionChange, 2)
  }
  test("position sanctions follow sequence and cap at last place; accumulated value differs from movement") {
    val actions = basic ++ Vector(penalty("a", "POSITION", 2), penalty("b", "POSITION", Long.MaxValue), penalty("a", "POSITION", 1))
    val r = result(build(actions, Vector("c", "b", "a")))
    assertEquals(r.drivers.head.positionPenalty, BigInt(3))
    assertEquals(r.drivers.head.positionChange, -2)
    assertEquals(r.drivers(1).positionPenalty, BigInt(Long.MaxValue))
    val reversed = basic ++ Vector(penalty("b", "POSITION", 2), penalty("a", "POSITION", 2))
    assertEquals(result(build(reversed, Vector("c", "b", "a"))).classification.map(_.playerId), Vector("c", "b", "a"))
    assertEquals(result(build(basic ++ Vector(penalty("a", "POSITION", 2), penalty("b", "POSITION", 2)), Vector("c", "a", "b")))
      .classification.map(_.playerId), Vector("c", "a", "b"))
  }
  test("winner with no last-place lap is not a comeback; losing last-place driver is not a comeback") {
    assert(result(build(basic)).drivers.forall(!_.comeback))
    val r = result(build(Vector(lap("a", 1, 100, 3), lap("b", 1, 200, 1), lap("c", 1, 300, 2))))
    assert(r.drivers.head.comeback)
  }
  test("multiple stops relate by stopId across laps, not adjacent events") {
    val actions = Vector(entry("a", "one"), entry("b", "two"), exit("b", "two"), exit("a", "one", 2),
      entry("a", "three", 2), exit("a", "three", 2)) ++ basic ++
      Vector(lap("a", 2, 100, 1), lap("b", 2, 200, 2), lap("c", 2, 300, 3))
    val r = result(build(actions, laps = 2))
    assertEquals(r.drivers.head.pitStops, 2)
    assertEquals(r.drivers.head.stops.map(_.durationMs), Vector(3000L, 1000L))
    assertEquals(r.drivers.head.totalTimeMs, BigInt(200))
    val zero = build(Vector(entry("a", "one"), exit("a", "one")) ++ basic)
    val same = zero.copy(events = zero.events.updated(2, zero.events(2).copy(timestamp = zero.events(1).timestamp)))
    assertEquals(result(same).drivers.head.stops.head.durationMs, 0L)
  }
  test("reject missing, duplicate, wrong-player, backwards-lap and unclosed pit stops") {
    Vector(Vector(exit("a", "x")), Vector(entry("a", "x")), Vector(entry("a", "x"), entry("b", "x")),
      Vector(entry("a", "x"), exit("b", "x")), Vector(entry("a", "x"), exit("a", "x"), exit("a", "x")))
      .foreach(stops => reject(build(stops ++ basic)))
    reject(build(Vector(entry("a", "x", 2), exit("a", "x", 1)) ++ basic ++
      Vector(lap("a", 2, 100, 1), lap("b", 2, 200, 2), lap("c", 2, 300, 3)), laps = 2))
  }
  test("classification mismatch is reported with both orders; wire array order does not matter") {
    val b = build(basic, Vector("b", "a", "c"))
    val error = RacingAnalysis.analyze(b).swap.toOption.get
    assert(error.contains("Classification inconsistency") && error.contains("calculated") && error.contains("reported"))
    val valid = build(basic)
    val reversed = valid.events.last.copy(data = Json.obj("classification" -> Json.fromValues(
      valid.events.last.data.hcursor.downField("classification").focus.get.asArray.get.reverse)))
    assertEquals(result(valid.copy(events = valid.events.init :+ reversed)).classification, result(valid).classification)
  }
  test("reject incomplete laps, duplicate lap positions and invalid penalties") {
    reject(build(basic.dropRight(1)))
    reject(build(Vector(lap("a", 2, 100, 1)) ++ basic.tail))
    reject(build(Vector(lap("a", 1, 0, 1)) ++ basic.tail))
    reject(build(Vector(lap("a", 1, 100, 2)) ++ basic.tail))
    Vector(penalty("a", "TIME", 0), penalty("a", "OTHER", 1),
      field(penalty("a", "TIME", 1), "lapNumber", Json.fromInt(2))).foreach(p => reject(build(basic :+ p)))
  }
  test("large totals and penalty accumulation do not overflow Long") {
    val actions = Vector(lap("a", 1, Long.MaxValue, 1), lap("b", 1, Long.MaxValue, 2), lap("c", 1, Long.MaxValue, 3),
      lap("a", 2, Long.MaxValue, 1), lap("b", 2, Long.MaxValue, 2), lap("c", 2, Long.MaxValue, 3),
      penalty("a", "TIME", Long.MaxValue), penalty("a", "TIME", Long.MaxValue))
    val r = result(build(actions, Vector("b", "c", "a"), 2))
    assertEquals(r.drivers.head.totalTimeMs, BigInt(Long.MaxValue) * 2)
    assertEquals(r.drivers.head.adjustedTimeMs, BigInt(Long.MaxValue) * 4)
  }
  test("validate envelopes, strict payloads, drivers and final classification structure") {
    val b = build(basic)
    Vector(b.copy(events = Vector.empty), b.copy(events = b.events.dropRight(1)),
      b.copy(events = b.events.updated(1, b.events(1).copy(playerId = None))),
      b.copy(events = b.events.updated(1, b.events(1).copy(playerId = Some("other")))),
      b.copy(events = b.events.updated(1, b.events(1).copy(sequence = 99))),
      b.copy(events = b.events.updated(1, b.events(1).copy(eventId = b.events.head.eventId))),
      b.copy(events = b.events.updated(1, b.events(1).copy(timestamp = "2020-01-01T00:00:00Z"))),
      b.copy(events = b.events.updated(1, b.events(1).copy(timestamp = "2026-09-01T12:00:00+01:00"))),
      b.copy(events = b.events.updated(1, field(b.events(1), "extra", Json.True))),
      b.copy(events = b.events.updated(0, field(b.events.head, "driverIds", json("""["a","a","c"]""")))),
      b.copy(events = b.events.updated(4, field(b.events.last, "classification", json("""[{"playerId":"a","position":1}]""")))))
      .foreach(reject)
  }
}
