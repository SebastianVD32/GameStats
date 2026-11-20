package gamestats

import java.nio.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import JsonCodec.given

class RacingAbortSuite extends munit.FunSuite {
  private def batch(actions: Vector[Event] = Vector.empty, reason: String = "USER_QUIT"): MatchBatch = {
    val start = Event("start", "2026-11-19T12:00:00Z", "RACING", "1.0", "abort-case", 1, None,
      "MATCH_STARTED", Json.obj("driverIds" -> Vector("a", "b").asJson, "plannedLaps" -> 1.asJson))
    val end = start.copy(`type` = "MATCH_ABORTED", data = Json.obj("reason" -> reason.asJson))
    MatchBatch("RACING", "1.0", "abort-case", (Vector(start) ++ actions ++ Vector(end)).zipWithIndex.map((e, i) =>
      e.copy(eventId = s"abort-$i", sequence = i.toLong + 1, timestamp = f"2026-11-19T12:00:$i%02d" + "Z")))
  }
  private def lap(id: String, position: Int): Event = batch().events.head.copy(playerId = Some(id), `type` = "LAP_COMPLETED",
    data = Json.obj("lapNumber" -> 1.asJson, "lapTimeMs" -> 1.asJson, "position" -> position.asJson))

  test("abort retains real progress but has no classification, winners or records") {
    val b = batch(Vector(lap("a", 1)))
    val r = RacingAnalysis.analyzeAborted(b).toOption.get
    assertEquals(r.status, "ABORTED"); assertEquals(r.reason, "USER_QUIT")
    assertEquals(r.driverProgress.map(_.completedLaps), Vector(1, 0))
    assertEquals(r.driverProgress.map(_.completedLapTimeMs), Vector(BigInt(1), BigInt(0)))
    val encoded = Analysis.analyze(b).toOption.get
    assert(encoded.hcursor.downField("classification").focus.isEmpty)
    assert(encoded.hcursor.downField("fastestLaps").focus.isEmpty)
    assert(RacingAnalysis.analyze(b).isLeft)
  }

  test("no laps and an unclosed pit are valid aborts, completed races are not") {
    assert(RacingAnalysis.analyzeAborted(batch()).isRight)
    val pit = batch().events.head.copy(playerId = Some("a"), `type` = "PIT_ENTERED",
      data = Json.obj("stopId" -> "pit".asJson, "lapNumber" -> 1.asJson, "reason" -> "tires".asJson))
    assertEquals(RacingAnalysis.analyzeAborted(batch(Vector(pit))).toOption.get.driverProgress.head.openPitStops, 1)
    assert(RacingAnalysis.analyzeAborted(batch(Vector(lap("a", 1), lap("b", 2)))).isLeft)
  }

  test("invalid reasons, payload fields, players, sequence and chronology are rejected") {
    assert(RacingAnalysis.analyzeAborted(batch(reason = "OTHER")).isLeft)
    val b = batch()
    val invalid = Vector(
      b.events.last.copy(playerId = Some("a")),
      b.events.last.copy(sequence = 99),
      b.events.last.copy(timestamp = "2026-11-18T12:00:00Z"),
      b.events.last.copy(data = Json.obj("reason" -> "USER_QUIT".asJson, "classification" -> Vector.empty[Json].asJson)))
    invalid.foreach(e => assert(Analysis.analyze(b.copy(events = b.events.init :+ e)).isLeft))
    assert(Analysis.analyze(batch(Vector(lap("outsider", 1)))).isLeft)
  }

  test("abort counts are distinct and cannot improve completed race records") {
    val complete = JsonCodec.decodeBatch(Files.readString(Path.of("testdata/race-1.json"))).toOption.get
    val aborted = batch(Vector(lap("a", 1)))
    val records = Vector(complete, aborted).map(b => StoredMatch(b, Analysis.analyze(b).toOption.get))
    val summary = GameStatistics.summary(records).hcursor
    assertEquals(summary.downField("general").get[Int]("completedMatches"), Right(1))
    assertEquals(summary.downField("general").get[Int]("processedMatches"), Right(2))
    assertEquals(summary.downField("general").get[Int]("abortedMatches"), Right(1))
    val racing = summary.downField("games").downField("RACING")
    assertEquals(racing.get[Int]("completedMatches"), Right(1))
    assertEquals(racing.get[Int]("abortedMatches"), Right(1))
    assertEquals(racing.downField("bestLap").get[Long]("lapTimeMs"), Right(90000L))
    val empty = GameStatistics.racing(Vector(records.last)).hcursor
    assertEquals(empty.get[Option[Json]]("bestLap"), Right(None))
    assertEquals(empty.get[Option[Double]]("averageDurationMs"), Right(None))
  }

  test("aborted records survive storage restart without changing existing finished records") {
    val directory = Files.createTempDirectory("abort-store")
    val aborted = batch()
    val completed = JsonCodec.decodeBatch(Files.readString(Path.of("testdata/race-1.json"))).toOption.get
    val store = new ResultStore(directory)
    try {
      for (b <- Vector(aborted, completed)) assert(store.save(b, Analysis.analyze(b).toOption.get).isRight)
    } finally store.close()
    val reopened = new ResultStore(directory)
    try {
      assertEquals(reopened.get(aborted.matchId), Analysis.analyze(aborted).toOption)
      assertEquals(reopened.get(completed.matchId), Analysis.analyze(completed).toOption)
    } finally reopened.close()
  }
}
