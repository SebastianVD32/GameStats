package gamestats

import java.nio.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import JsonCodec.given

class GameStatisticsSuite extends munit.FunSuite {
  private def fixture(name: String): MatchBatch =
    JsonCodec.decodeBatch(Files.readString(Path.of(s"testdata/$name.json"))).toOption.get
  private def stored(b: MatchBatch): StoredMatch = StoredMatch(b, Analysis.analyze(b).toOption.get)

  // Inserts an AI_DECISION right before the first move of the batch and renumbers the sequence.
  private def withAiDecision(b: MatchBatch, moveNumber: Int = 1): MatchBatch = {
    val (head, tail) = b.events.splitAt(1)
    val decision = tail.head.copy(eventId = s"${b.matchId}-ai-1", `type` = "AI_DECISION", data = Json.obj(
      "moveNumber" -> moveNumber.asJson, "from" -> "e2".asJson, "to" -> "e4".asJson, "depth" -> 3.asJson,
      "nodesEvaluated" -> 1500.asJson, "score" -> 25.asJson, "thinkTimeMs" -> 120.asJson))
    val events = (head ++ Vector(decision) ++ tail).zipWithIndex.map((e, i) => e.copy(sequence = i.toLong + 1))
    b.copy(events = events)
  }

  test("chess accepts AI_DECISION without changing the match result") {
    val original = fixture("chess-1")
    val withAi = withAiDecision(original)
    val a = ChessAnalysis.analyze(original).toOption.get
    val b = ChessAnalysis.analyze(withAi).toOption.get
    assertEquals(b.totalMoves, a.totalMoves)
    assertEquals(b.players, a.players)
    assertEquals(ChessAnalysis.aiDecisions(withAi).map(_.nodesEvaluated), Vector(1500L))
  }

  test("AI_DECISION for the wrong turn is rejected") {
    assert(ChessAnalysis.analyze(withAiDecision(fixture("chess-1"), moveNumber = 2)).isLeft)
  }

  test("summary separates general and per-game statistics") {
    val chess = Vector(withAiDecision(fixture("chess-1")), fixture("chess-2"), fixture("chess-3")).map(stored)
    val others = Vector(stored(fixture("race-1")), stored(fixture("basket-1")))
    val json = GameStatistics.summary(chess ++ others).hcursor
    val general = json.downField("general")
    assertEquals(general.get[Int]("completedMatches"), Right(5))
    assertEquals(general.downField("matchesByGame").get[Int]("CHESS"), Right(3))
    assertEquals(general.downField("matchesByGame").get[Int]("PACMAN"), Right(0))
    val c = json.downField("games").downField("CHESS")
    assertEquals(c.get[Int]("completedMatches"), Right(3))
    assertEquals(c.downField("results").get[Int]("CHECKMATE"), Right(1))
    assertEquals(c.downField("results").get[Int]("DRAW"), Right(1))
    assertEquals(c.downField("results").get[Int]("TIMEOUT"), Right(1))
    assertEquals(c.downField("ai").get[Int]("decisions"), Right(1))
    assertEquals(c.downField("ai").get[Double]("averageDepth"), Right(3.0))
    assertEquals(json.downField("games").downField("RACING").get[Int]("completedMatches"), Right(1))
    assertEquals(json.downField("games").downField("PACMAN").get[Option[Double]]("averageScore"), Right(None))
  }

  test("empty store yields zero counts and null averages") {
    val json = GameStatistics.summary(Vector.empty).hcursor
    assertEquals(json.downField("general").get[Int]("completedMatches"), Right(0))
    assertEquals(json.downField("general").get[Option[Double]]("averageDurationMs"), Right(None))
    assertEquals(json.downField("games").downField("CHESS").downField("ai").get[Int]("decisions"), Right(0))
  }

  test("stored statistics survive a store restart") {
    val directory = Files.createTempDirectory("stats-store")
    val batch = withAiDecision(fixture("chess-1"))
    val store = new ResultStore(directory)
    try assert(store.save(batch, Analysis.analyze(batch).toOption.get).isRight)
    finally store.close()
    val reopened = new ResultStore(directory)
    try assertEquals(reopened.stats.hcursor.downField("games").downField("CHESS").get[Int]("completedMatches"), Right(1))
    finally reopened.close()
  }
}
