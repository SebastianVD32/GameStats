package gamestats

import io.circe.Json
import io.circe.syntax.*
import JsonCodec.given

final case class StoredMatch(batch: MatchBatch, result: Json)

/** Aggregates over stored (completed) matches, in general and per game.
  * Pure functions: the same stored results always produce the same statistics.
  */
object GameStatistics {
  val games: Vector[String] = Vector("CHESS", "PACMAN", "RACING", "BASKETBALL")
  private val chessReasons = Vector("CHECKMATE", "RESIGNATION", "DRAW", "TIMEOUT")

  private def average(values: Vector[Double]): Option[Double] =
    Option.when(values.nonEmpty)(values.sum / values.size)
  private def longField(json: Json, name: String): Option[Long] = json.hcursor.get[Long](name).toOption
  private def durations(records: Vector[StoredMatch]): Vector[Long] =
    records.flatMap(r => longField(r.result, "durationMs"))
  private def array(json: Json, name: String): Vector[Json] =
    json.hcursor.downField(name).as[Vector[Json]].getOrElse(Vector.empty)

  def summary(records: Vector[StoredMatch]): Json = {
    val byGame = records.groupBy(_.batch.gameId).withDefaultValue(Vector.empty)
    Json.obj(
      "general" -> general(records),
      "games" -> Json.obj(
        "CHESS" -> chess(byGame("CHESS")),
        "PACMAN" -> pacman(byGame("PACMAN")),
        "RACING" -> racing(byGame("RACING")),
        "BASKETBALL" -> basketball(byGame("BASKETBALL"))
      )
    )
  }

  def general(records: Vector[StoredMatch]): Json = {
    val times = durations(records)
    Json.obj(
      "completedMatches" -> records.size.asJson,
      "matchesByGame" -> Json.obj(games.map(g => g -> records.count(_.batch.gameId == g).asJson)*),
      "totalEvents" -> records.map(_.batch.events.size.toLong).sum.asJson,
      "totalPlayTimeMs" -> times.sum.asJson,
      "averageDurationMs" -> average(times.map(_.toDouble)).asJson
    )
  }

  def chess(records: Vector[StoredMatch]): Json = {
    val results = records.flatMap(r => ChessAnalysis.analyze(r.batch).toOption)
    val entries = results.flatMap(r => r.players.map(p => (r, p)))
    val players = entries.groupBy(_._2.playerId).toVector.sortBy(_._1).map { (id, played) =>
      val moves = played.map(_._2.moves.toLong).sum
      val decision = played.map(_._2.totalDecisionTimeMs).sum
      val wins = played.count(_._1.winnerPlayerId.contains(id))
      val draws = played.count(_._1.winnerPlayerId.isEmpty)
      Json.obj(
        "playerId" -> id.asJson,
        "matches" -> played.size.asJson,
        "wins" -> wins.asJson,
        "draws" -> draws.asJson,
        "losses" -> (played.size - wins - draws).asJson,
        "moves" -> moves.asJson,
        "averageDecisionTimeMs" -> Option.when(moves > 0)(decision.toDouble / moves).asJson
      )
    }
    val decisions = records.flatMap(r => ChessAnalysis.aiDecisions(r.batch))
    Json.obj(
      "completedMatches" -> results.size.asJson,
      "averageDurationMs" -> average(results.map(_.durationMs.toDouble)).asJson,
      "averageMoves" -> average(results.map(_.totalMoves.toDouble)).asJson,
      "totalCaptures" -> results.map(_.totalCaptures).sum.asJson,
      "averageCaptures" -> average(results.map(_.totalCaptures.toDouble)).asJson,
      "results" -> Json.obj(chessReasons.map(reason => reason -> results.count(_.reason == reason).asJson)*),
      "winsByColor" -> ChessAnalysis.winsByColor(results).getOrElse(Vector.empty).asJson,
      "players" -> players.asJson,
      "ai" -> Json.obj(
        "decisions" -> decisions.size.asJson,
        "averageDepth" -> average(decisions.map(_.depth.toDouble)).asJson,
        "averageNodes" -> average(decisions.map(_.nodesEvaluated.toDouble)).asJson,
        "maxNodes" -> decisions.map(_.nodesEvaluated).maxOption.asJson,
        "averageThinkTimeMs" -> average(decisions.map(_.thinkTimeMs.toDouble)).asJson
      )
    )
  }

  def pacman(records: Vector[StoredMatch]): Json = {
    val scores = records.flatMap(r => r.result.hcursor.get[BigInt]("totalScore").toOption)
    Json.obj(
      "completedMatches" -> records.size.asJson,
      "averageDurationMs" -> average(durations(records).map(_.toDouble)).asJson,
      "averageScore" -> average(scores.map(_.toDouble)).asJson,
      "bestScore" -> scores.maxOption.asJson,
      "totalGhosts" -> records.flatMap(r => longField(r.result, "ghosts")).sum.asJson,
      "totalLevelsCompleted" -> records.flatMap(r => longField(r.result, "levelsCompleted")).sum.asJson
    )
  }

  def racing(records: Vector[StoredMatch]): Json = {
    val drivers = records.flatMap(r => array(r.result, "drivers"))
    val laps = records.flatMap(r => array(r.result, "fastestLaps")).flatMap { lap =>
      for {
        id <- lap.hcursor.get[String]("playerId").toOption
        time <- longField(lap, "lapTimeMs")
      } yield (id, time)
    }
    val best = laps.minByOption(_._2).map((id, time) => Json.obj("playerId" -> id.asJson, "lapTimeMs" -> time.asJson))
    Json.obj(
      "completedMatches" -> records.size.asJson,
      "averageDurationMs" -> average(durations(records).map(_.toDouble)).asJson,
      "bestLap" -> best.asJson,
      "totalPitStops" -> drivers.flatMap(d => longField(d, "pitStops")).sum.asJson,
      "comebacks" -> drivers.count(d => d.hcursor.get[Boolean]("comeback").contains(true)).asJson
    )
  }

  def basketball(records: Vector[StoredMatch]): Json = {
    val totals = records.map(r => array(r.result, "teams").flatMap(t => longField(t, "points")).sum.toDouble)
    Json.obj(
      "completedMatches" -> records.size.asJson,
      "averageDurationMs" -> average(durations(records).map(_.toDouble)).asJson,
      "averageTotalPoints" -> average(totals).asJson
    )
  }
}
