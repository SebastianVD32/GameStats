package gamestats

import java.time.{Instant, OffsetDateTime, Duration}
import scala.util.Try
import JsonCodec.given

object PacmanAnalytics {
  private case class Active(id: String, expires: Instant)
  private case class State(level: Int, lives: Int, pellets: Set[(Int, String)] = Set.empty,
    normal: Int = 0, score: BigInt = 0, lost: Int = 0, completed: Int = 0,
    active: Option[Active] = None, effects: Vector[PowerEffect] = Vector.empty)
  private def check(ok: Boolean, error: String): Either[String, Unit] = Either.cond(ok, (), error)
  private def time(e: Event): Either[String, Instant] = Try(OffsetDateTime.parse(e.timestamp)).toEither
    .left.map(_ => s"${e.eventId}: invalid timestamp").flatMap(t =>
      check(t.getOffset.getTotalSeconds == 0, "timestamp must be UTC").map(_ => t.toInstant))

  def analyze(batch: MatchBatch): Either[String, PacmanResult] = {
    val es = batch.events.sortBy(_.sequence)
    for {
      _ <- check(batch.gameId == "PACMAN" && batch.matchId.trim.nonEmpty && batch.gameVersion.trim.nonEmpty, "Invalid Pac-Man identity")
      _ <- check(es.size >= 2, "Complete match required")
      _ <- check(es.head.`type` == "MATCH_STARTED" && es.last.`type` == "MATCH_FINISHED" &&
        es.head.playerId.isEmpty && es.last.playerId.isEmpty, "Invalid start/finish")
      _ <- check(es.map(_.eventId).distinct.size == es.size && es.zipWithIndex.forall { (e, i) =>
        e.sequence == i.toLong + 1 && e.eventId.trim.nonEmpty && e.gameId == batch.gameId &&
        e.gameVersion == batch.gameVersion && e.matchId == batch.matchId && e.data.isObject
      }, "Invalid envelope, duplicate or sequence")
      ts <- es.foldLeft[Either[String, Vector[Instant]]](Right(Vector.empty))((a, e) => for { xs <- a; t <- time(e) } yield xs :+ t)
      _ <- check(ts.zip(ts.drop(1)).forall((a, b) => !b.isBefore(a)), "Timestamps go backwards")
      start <- JsonCodec.payload[PacmanStart](es.head)
      _ <- check(start.playerId.trim.nonEmpty && start.initialLives > 0 && start.initialLevel > 0, "Invalid player/lives/level")
      s <- es.slice(1, es.size - 1).zip(ts.slice(1, ts.size - 1)).foldLeft[Either[String, State]](
        Right(State(start.initialLevel, start.initialLives))) { case (acc, (e, t)) =>
          acc.flatMap(s => action(s, e, t, start.playerId))
        }
      finish <- if (es.last.data.hcursor.downField("score").succeeded)
        JsonCodec.payload[GoPacmanFinish](es.last).flatMap { f =>
          check(Set("GAME_OVER", "SESSION_ENDED")(f.reason), "Invalid Go finish reason").map { _ =>
            PacmanFinish(f.score, s.completed, s.lives,
              if (f.reason == "GAME_OVER") "NO_LIVES" else "SIMULATION_ENDED")
          }
        }
      else JsonCodec.payload[PacmanFinish](es.last)
      _ <- check(finish.finalScore == s.score && finish.levelsCompleted == s.completed && finish.livesRemaining == s.lives &&
        ((finish.reason == "NO_LIVES" && s.lives == 0) || (finish.reason == "SIMULATION_ENDED" && s.lives > 0)), "Inconsistent final result/reason")
    } yield PacmanResult(batch.gameId, batch.gameVersion, batch.matchId, start.playerId, s.normal,
      s.effects.size, s.effects.map(_.ghostIds.size).sum, s.score, s.lost, s.lives, s.completed,
      Duration.between(ts.head, ts.last).toMillis, s.effects)
  }

  private def action(s: State, e: Event, t: Instant, player: String): Either[String, State] = for {
    _ <- check(s.lives > 0 && e.playerId.contains(player), s"${e.eventId}: no lives or unknown player")
    level <- e.data.hcursor.get[Int]("level").left.map(_.message)
    _ <- check(BigInt(level) == BigInt(s.level), s"${e.eventId}: invalid level")
    next <- e.`type` match {
      case "PELLET_EATEN" => for {
        d <- JsonCodec.payload[Pellet](e)
        _ <- check(d.pelletId.trim.nonEmpty && !s.pellets((level, d.pelletId)), "Duplicate/empty pellet")
      } yield s.copy(pellets = s.pellets + ((level, d.pelletId)), normal = s.normal + 1, score = s.score + 10)
      case "POWER_UP_COLLECTED" => for {
        d <- JsonCodec.payload[PowerPellet](e)
        _ <- check(d.durationMs > 0 && d.pelletId.trim.nonEmpty && !s.pellets((level, d.pelletId)), "Invalid power pellet")
        expiry <- Try(t.plusMillis(d.durationMs)).toEither.left.map(_ => "Power duration overflow")
      } yield s.copy(pellets = s.pellets + ((level, d.pelletId)), score = s.score + 50,
        active = Some(Active(e.eventId, expiry)), effects = s.effects :+ PowerEffect(e.eventId, Vector.empty, Vector.empty, None))
      case "GHOST_EATEN" => for {
        d <- JsonCodec.payload[Ghost](e)
        a <- s.active.toRight("No active power effect")
        _ <- check(a.id == d.powerUpEventId && t.isBefore(a.expires), "Missing/replaced/expired power reference")
        effect = s.effects.last
        _ <- check(Set("BLINKY", "PINKY", "INKY", "CLYDE")(d.ghostId) && !effect.ghostIds.contains(d.ghostId), "Invalid/repeated ghost")
        updated = effect.copy(ghostIds = effect.ghostIds :+ d.ghostId, ghostEventIds = effect.ghostEventIds :+ e.eventId,
          chainEventId = if (effect.ghostIds.size == 2) Some(e.eventId) else effect.chainEventId)
      } yield s.copy(score = s.score + (BigInt(200) << effect.ghostIds.size), effects = s.effects.init :+ updated)
      case "LIFE_LOST" => for {
        d <- JsonCodec.payload[LifeLost](e)
        _ <- check(d.cause.trim.nonEmpty && d.livesRemaining == s.lives - 1, "Inconsistent lives/cause")
      } yield s.copy(lives = s.lives - 1, lost = s.lost + 1, active = None)
      case "LEVEL_COMPLETED" => for {
        d <- JsonCodec.payload[LevelCompleted](e)
        _ <- check(s.level < Int.MaxValue, "Level overflow")
      } yield s.copy(level = s.level + 1, completed = s.completed + 1, active = None)
      case other => Left(s"${e.eventId}: unexpected Pac-Man event $other")
    }
  } yield next

  def accumulated(results: Vector[PacmanResult]): Vector[PacmanPlayerStats] =
    results.groupBy(_.playerId).toVector.sortBy(_._1).map { (id, rs) =>
      PacmanPlayerStats(id, rs.size, rs.map(_.totalScore).sum, rs.map(_.totalScore).max)
    }
}
