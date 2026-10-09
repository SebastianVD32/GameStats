package gamestats

import java.nio.file.{Files, Path}
import io.circe.{Json, parser}
import io.circe.syntax.*
import JsonCodec.given

class ChessAnalysisSuite extends munit.FunSuite {
  private def fixture(name: String): MatchBatch =
    JsonCodec.decodeBatch(Files.readString(Path.of(s"testdata/$name.json"))).toOption.get
  private val batch = fixture("chess-1")
  private def result(b: MatchBatch): ChessResult = ChessAnalysis.analyze(b).toOption.get
  private def field(e: Event, key: String, value: Json): Event =
    e.copy(data = Json.fromJsonObject(e.data.asObject.get.add(key, value)))

  test("Go chess-1 matches the complete independently calculated JSON") {
    val expected = parser.parse(Files.readString(Path.of("testdata/chess-1.expected.json"))).toOption.get
    assertEquals(result(batch).asJson, expected)
  }
  test("all chess fixtures: draw, black timeout win, no moves and null averages") {
    val draw = result(fixture("chess-2"))
    val timeout = result(fixture("chess-3"))
    assertEquals(draw.reason, "DRAW")
    assertEquals(draw.winnerPlayerId, None)
    assertEquals(draw.totalMoves, 0)
    assertEquals(draw.durationMs, 9000L)
    assert(draw.players.forall(p => p.averageDecisionTimeMs.isEmpty && !p.timePressure))
    assertEquals(timeout.winnerColor, Some("BLACK"))
    assertEquals(timeout.reason, "TIMEOUT")
    assertEquals(timeout.durationMs, 9000L)
  }
  test("pressure uses own consecutive moves and strictly less than 60 seconds") {
    val moveEvents = batch.events.filter(_.`type` == "MOVE_PLAYED")
    val own = Vector(moveEvents(0), moveEvents(2), moveEvents(4), moveEvents(4).copy(eventId = "fourth"))
    def windows(clocks: Vector[Long]) = ChessAnalysis.pressureWindows(own.zip(clocks).map { (e, clock) =>
      e -> JsonCodec.payload[ChessMove](e).toOption.get.copy(clockBeforeMs = clock)
    })
    assertEquals(windows(Vector(59999, 59999, 59999, 59999)).size, 2)
    assertEquals(windows(Vector(59999, 60000, 59999, 59999)).size, 0)
    assertEquals(windows(Vector(60000, 59999, 59999, 59999)).size, 1)
  }
  test("win percentages include draws and ignore identical replayed matches") {
    val results = Vector("chess-1", "chess-2", "chess-3").map(n => result(fixture(n)))
    val colors = ChessAnalysis.winsByColor(results :+ results.head).toOption.get
    assert(colors.forall(c => c.wins == 1 && c.completedMatches == 3))
    colors.foreach(c => assertEqualsDouble(c.winPercentage.get, 100.0 / 3, 0.00001))
    assert(ChessAnalysis.winsByColor(Vector.empty).toOption.get.forall(_.winPercentage.isEmpty))
    assert(ChessAnalysis.winsByColor(results :+ results.head.copy(totalMoves = 999)).isLeft)
  }
  test("reject incomplete matches, duplicate events, sequence gaps and mismatched identities") {
    Vector(batch.copy(events = Vector.empty), batch.copy(events = batch.events.dropRight(1)),
      batch.copy(events = batch.events.updated(1, batch.events(1).copy(eventId = batch.events.head.eventId))),
      batch.copy(events = batch.events.updated(1, batch.events(1).copy(sequence = 99))),
      batch.copy(events = batch.events.updated(1, batch.events(1).copy(matchId = "other"))),
      batch.copy(gameId = "PACMAN")).foreach(b => assert(ChessAnalysis.analyze(b).isLeft))
  }
  test("reject clock inconsistency, wrong turn, invalid capture reference and duplicate capture") {
    val invalidClock = field(batch.events(1), "clockAfterMs", Json.fromLong(1))
    val wrongTurn = batch.events(1).copy(playerId = Some("chess-bob"))
    val invalidCapture = field(batch.events(6), "moveEventId", Json.fromString("absent"))
    Vector(batch.copy(events = batch.events.updated(1, invalidClock)),
      batch.copy(events = batch.events.updated(1, wrongTurn)),
      batch.copy(events = batch.events.updated(6, invalidCapture)),
      batch.copy(events = batch.events.dropRight(1) ++ Vector(batch.events(6).copy(eventId = "repeat", sequence = 8),
        batch.events.last.copy(sequence = 9)))).foreach(b => assert(ChessAnalysis.analyze(b).isLeft))
  }
  test("UTC timestamps, chronological order and winner are validated") {
    Vector(batch.copy(events = batch.events.updated(1, batch.events(1).copy(timestamp = "invalid"))),
      batch.copy(events = batch.events.updated(1, batch.events(1).copy(timestamp = "2026-09-01T12:00:02+01:00"))),
      batch.copy(events = batch.events.updated(1, batch.events(1).copy(timestamp = "2020-09-01T12:00:02Z"))),
      batch.copy(events = batch.events.updated(7, field(batch.events.last, "winnerPlayerId", Json.Null))))
      .foreach(b => assert(ChessAnalysis.analyze(b).isLeft))
  }
  test("wire decoder requires nullable fields and rejects unknown fields at every level") {
    val json = batch.asJson
    def decode(j: Json) = JsonCodec.decodeBatch(j.noSpaces)
    assertEquals(decode(json), Right(batch))
    assert(decode(Json.fromJsonObject(json.asObject.get.add("extra", Json.True))).isLeft)
    val missingPlayer = Json.fromJsonObject(batch.events.head.asJson.asObject.get.remove("playerId"))
    val wrongEvents = Json.fromValues(missingPlayer +: batch.events.tail.map(_.asJson))
    assert(decode(Json.fromJsonObject(json.asObject.get.add("events", wrongEvents))).isLeft)
    val extraPayload = field(batch.events.head, "extra", Json.True)
    assert(ChessAnalysis.analyze(batch.copy(events = batch.events.updated(0, extraPayload))).isLeft)
    val missingWinner = batch.events.last.copy(data = Json.fromJsonObject(batch.events.last.data.asObject.get.remove("winnerPlayerId")))
    assert(ChessAnalysis.analyze(batch.copy(events = batch.events.updated(7, missingWinner))).isLeft)
  }
  test("increments and capture decision average use decisionTimeMs, not wall time") {
    val start = field(batch.events.head, "incrementMs", Json.fromLong(2000))
    val adjusted = batch.events.map { e =>
      if (e.`type` != "MOVE_PLAYED") e
      else {
        val prior = batch.events.takeWhile(_.eventId != e.eventId).count(p => p.`type` == "MOVE_PLAYED" && p.playerId == e.playerId)
        val m = JsonCodec.payload[ChessMove](e).toOption.get
        field(field(e, "clockBeforeMs", Json.fromLong(m.clockBeforeMs + prior * 2000)),
          "clockAfterMs", Json.fromLong(m.clockAfterMs + (prior + 1) * 2000))
      }
    }.updated(0, start)
    val stats = result(batch.copy(events = adjusted))
    assertEquals(stats.players.head.averageCaptureDecisionTimeMs, Some(1000.0))
    assert(!stats.players.head.timePressure)
  }

  test("different decision times and a capture referencing an older move yield correct averages") {
    val initial = (Map("chess-alice" -> 59000L, "chess-bob" -> 59000L), Vector.empty[Event])
    val modified = batch.events.foldLeft(initial) { case ((clocks, events), e) =>
      if (e.`type` != "MOVE_PLAYED") (clocks, events :+ e)
      else {
        val id = e.playerId.get
        val move = JsonCodec.payload[ChessMove](e).toOption.get
        val decision = move.moveNumber * 1000
        val updated = field(field(field(e, "decisionTimeMs", Json.fromLong(decision)),
          "clockBeforeMs", Json.fromLong(clocks(id))), "clockAfterMs", Json.fromLong(clocks(id) - decision))
        (clocks.updated(id, clocks(id) - decision), events :+ updated)
      }
    }._2
    // Capture e4 (decision 3000), rather than the most recent e6 (decision 5000).
    val events = modified.updated(6, field(modified(6), "moveEventId", Json.fromString("c1-e4")))
    val stats = result(batch.copy(events = events))
    assertEquals(stats.players.head.totalDecisionTimeMs, 9000L)
    assertEquals(stats.players.head.averageDecisionTimeMs, Some(3000.0))
    assertEquals(stats.players.head.averageCaptureDecisionTimeMs, Some(3000.0))
    assertEquals(stats.players(1).averageDecisionTimeMs, Some(3000.0))
  }
}
