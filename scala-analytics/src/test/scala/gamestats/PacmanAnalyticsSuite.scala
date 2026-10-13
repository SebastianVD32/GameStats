package gamestats

import java.nio.file.{Files, Path}
import io.circe.{Json, parser}
import io.circe.syntax.*
import JsonCodec.given
import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration.*

class PacmanAnalyticsSuite extends munit.FunSuite {
  private def fixture(n: Int) = JsonCodec.decodeBatch(Files.readString(Path.of(s"testdata/pacman-$n.json"))).toOption.get
  private def result(b: MatchBatch) = PacmanAnalytics.analyze(b).fold(e => fail(e), identity)
  private def field(e: Event, k: String, v: Json) = e.copy(data = Json.fromJsonObject(e.data.asObject.get.add(k, v)))
  private def modify(n: Int, index: Int)(f: Event => Event): MatchBatch = {
    val b = fixture(n); b.copy(events = b.events.updated(index, f(b.events(index))))
  }
  private def reject(b: MatchBatch) = assert(PacmanAnalytics.analyze(b).isLeft)
  private def insert(b: MatchBatch, index: Int, e: Event): MatchBatch = b.copy(events =
    b.events.patch(index, Vector(e), 0).zipWithIndex.map((e, i) => e.copy(sequence = i.toLong + 1)))

  test("three complete fixtures equal independently calculated results") {
    (1 to 3).foreach { n => assertEquals(result(fixture(n)).asJson,
      parser.parse(Files.readString(Path.of(s"testdata/pacman-$n.expected.json"))).toOption.get) }
    val rs = (1 to 3).map(n => result(fixture(n))).toVector
    assertEquals(PacmanAnalytics.accumulated(rs), Vector(PacmanPlayerStats("p1", 2, BigInt(1520), BigInt(1450)),
      PacmanPlayerStats("p2", 1, BigInt(3110), BigInt(3110))))
    assertEquals(rs(2).powerEffects.count(_.chainEventId.nonEmpty), 1)
    assertEquals(rs.head.powerEffects.head.ghostIds, Vector.empty[String])
  }
  test("sequence determines processing order") {
    assertEquals(result(fixture(2).copy(events = fixture(2).events.reverse)), result(fixture(2)))
  }
  test("ghost exactly at expiration is rejected") {
    reject(modify(2, 1)(e => field(e, "durationMs", Json.fromLong(1000))))
  }
  test("replaced effect cannot be referenced") {
    val b = fixture(2)
    val power = b.events(1).copy(eventId = "replacement", timestamp = b.events(2).timestamp,
      data = field(b.events(1), "pelletId", Json.fromString("other")).data)
    reject(insert(b, 2, power))
  }
  test("new effect resets ghost score and permits same ghost") {
    val b = fixture(2)
    val p = b.events(1).copy(eventId = "replacement", timestamp = b.events(3).timestamp,
      data = field(b.events(1), "pelletId", Json.fromString("other")).data)
    val changed = b.copy(events = b.events.take(3) ++ Vector(p,
      field(b.events(3), "powerUpEventId", Json.fromString("replacement")),
      field(b.events(4), "powerUpEventId", Json.fromString("replacement")),
      field(b.events.last, "finalScore", Json.fromInt(900))))
    val normalized = changed.copy(events = changed.events.zipWithIndex.map((e, i) => e.copy(sequence = i.toLong + 1)))
    assertEquals(result(normalized).powerEffects.map(_.ghostIds.size), Vector(1, 2))
  }
  test("life loss and level completion interrupt effects") {
    val b = fixture(3)
    val ghost = b.events(2).copy(eventId = "late", timestamp = b.events(7).timestamp,
      data = field(b.events(2), "level", Json.fromInt(2)).data)
    reject(insert(b, 7, ghost))
    val afterLoss = field(ghost.copy(timestamp = b.events(10).timestamp), "powerUpEventId", Json.fromString("pacman-3-e9"))
    reject(insert(b, 10, afterLoss))
  }
  test("missing reference, repeated ghost and repeated pellet are rejected") {
    reject(modify(2, 2)(e => field(e, "powerUpEventId", Json.fromString("missing"))))
    reject(modify(2, 3)(e => field(e, "ghostId", Json.fromString("BLINKY"))))
    reject(modify(1, 2)(e => field(e, "pelletId", Json.fromString("a"))))
    reject(modify(1, 3)(e => field(e, "pelletId", Json.fromString("a"))))
  }
  test("inconsistent remaining lives, final score, level and player are rejected") {
    reject(modify(3, 9)(e => field(e, "livesRemaining", Json.fromInt(3))))
    reject(modify(1, 4)(e => field(e, "finalScore", Json.fromInt(71))))
    reject(modify(1, 1)(e => field(e, "level", Json.fromInt(2))))
    reject(modify(1, 1)(_.copy(playerId = Some("other"))))
    reject(modify(1, 0)(e => field(e, "initialLives", Json.fromInt(0))))
    reject(modify(2, 1)(e => field(e, "durationMs", Json.fromInt(0))))
  }
  test("zero lives allows only finish with NO_LIVES") {
    val b = fixture(3)
    val es = b.events.updated(0, field(b.events.head, "initialLives", Json.fromInt(2)))
      .updated(9, field(b.events(9), "livesRemaining", Json.fromInt(1)))
      .updated(11, field(b.events(11), "livesRemaining", Json.fromInt(0)))
    val finish = field(field(es.last, "livesRemaining", Json.fromInt(0)), "reason", Json.fromString("NO_LIVES"))
    val valid = b.copy(events = es.init :+ finish)
    assertEquals(result(valid).livesRemaining, 0)
    val action = b.events(7).copy(eventId = "after-zero", timestamp = es(11).timestamp,
      data = Json.obj("level" -> Json.fromInt(3), "pelletId" -> Json.fromString("new")))
    reject(insert(valid, 12, action))
    reject(valid.copy(events = es))
  }
  test("concurrent retries, conflicts and restart preserve exactly one result") {
    given ExecutionContext = ExecutionContext.global
    val directory = Files.createTempDirectory("pacman-store")
    val b = fixture(2); val r = result(b).asJson
    val store = new ResultStore(directory)
    try {
      val saved = Await.result(Future.sequence((1 to 20).map(_ => Future(store.save(b, r)))), 20.seconds)
      assert(saved.forall(_ == Right(r)))
      assertEquals(store.player("p1").hcursor.get[Int]("completedMatches").toOption, Some(1))
      assert(store.save(fixture(1).copy(matchId = b.matchId), r).isLeft)
    } finally store.close()
    val reopened = new ResultStore(directory)
    try { assertEquals(reopened.get(b.matchId), Some(r)); assertEquals(reopened.player("p1").hcursor.get[BigInt]("totalScore").toOption, Some(BigInt(1450))) }
    finally reopened.close()
  }
}
