package gamestats

import java.time.{Duration, Instant, OffsetDateTime}
import scala.util.Try
import JsonCodec.given

object BasketballAnalysis {
  private case class State(shots: Vector[(Event, Shot)] = Vector.empty,
    assists: Vector[(Event, Assist)] = Vector.empty, rebounds: Vector[(Event, Rebound)] = Vector.empty,
    turnovers: Vector[Event] = Vector.empty, fouls: Vector[(Event, Foul)] = Vector.empty)
  private def check(ok: Boolean, message: String): Either[String, Unit] = Either.cond(ok, (), message)
  private def shooting(shots: Vector[Shot]): ShootingStats = {
    val made = shots.count(_.made)
    ShootingStats(made, shots.size, Option.when(shots.nonEmpty)(made * 100.0 / shots.size))
  }
  private def points(shots: Vector[Shot]): Long = shots.filter(_.made).map(_.shotValue.toLong).sum

  def analyze(batch: MatchBatch): Either[String, BasketballResult] = {
    val es = batch.events
    for {
      _ <- check(batch.gameId == "BASKETBALL" && batch.matchId.trim.nonEmpty && batch.gameVersion.trim.nonEmpty,
        "Invalid basketball identity")
      _ <- check(es.size >= 2, "Complete match required")
      _ <- check(es.head.`type` == "MATCH_STARTED" && es.last.`type` == "MATCH_FINISHED" &&
        es.head.playerId.isEmpty && es.last.playerId.isEmpty, "Invalid start/finish")
      _ <- check(es.map(_.eventId).distinct.size == es.size && es.zipWithIndex.forall { (e, i) =>
        e.sequence == i.toLong + 1 && e.gameId == batch.gameId && e.gameVersion == batch.gameVersion &&
        e.matchId == batch.matchId && e.eventId.trim.nonEmpty && e.data.isObject && e.playerId.forall(_.trim.nonEmpty)
      }, "Invalid envelope, duplicate or sequence")
      times <- es.foldLeft[Either[String, Vector[Instant]]](Right(Vector.empty)) { (acc, e) => for {
        ts <- acc
        t <- Try(OffsetDateTime.parse(e.timestamp)).toEither.left.map(_ => "Invalid timestamp")
        _ <- check(t.getOffset.getTotalSeconds == 0, "Timestamp must be UTC")
      } yield ts :+ t.toInstant }
      _ <- check(times.zip(times.drop(1)).forall((a, b) => !b.isBefore(a)), "Timestamps go backwards")
      start <- JsonCodec.payload[BasketballStart](es.head)
      ids = start.teams.flatMap(_.playerIds)
      _ <- check(start.periods > 0 && start.teams.size == 2 &&
        start.teams.map(_.teamId).distinct.size == 2 && start.teams.forall(t => t.teamId.trim.nonEmpty && t.playerIds.nonEmpty) &&
        ids.forall(_.trim.nonEmpty) && ids.distinct.size == ids.size, "Invalid teams/participants")
      membership = start.teams.flatMap(t => t.playerIds.map(_ -> t.teamId)).toMap
      state <- es.slice(1, es.size - 1).foldLeft[Either[String, State]](Right(State())) {
        (acc, e) => acc.flatMap(s => action(s, e, start.periods, membership))
      }
      finish <- JsonCodec.payload[BasketballFinish](es.last)
      _ <- check(finish.scores.size == 2 && finish.scores.map(_.teamId).toSet == start.teams.map(_.teamId).toSet &&
        finish.scores.forall(score => score.points == points(state.shots.map(_._2).filter(_.teamId == score.teamId))),
        "Final scores disagree with shots")
    } yield {
      val byId = state.shots.map { (e, s) => e.eventId -> s }.toMap
      val assisted = state.assists.map(_._2.shotEventId).toSet
      val players = start.teams.flatMap(t => t.playerIds.map { id =>
        val own = state.shots.filter(_._1.playerId.contains(id))
        val ss = own.map(_._2)
        val fs = state.fouls.filter(_._1.playerId.contains(id))
        val rs = state.rebounds.filter(_._1.playerId.contains(id)).map(_._2)
        val assists = state.assists.filter(_._1.playerId.contains(id))
        BasketballPlayerStats(id, t.teamId, points(ss), assists.size, rs.size,
          rs.count(_.reboundType == "OFFENSIVE"), rs.count(_.reboundType == "DEFENSIVE"),
          state.turnovers.count(_.playerId.contains(id)), fs.size, shooting(ss.filter(_.shotValue >= 2)),
          shooting(ss.filter(_.shotValue == 3)), shooting(ss.filter(_.shotValue == 1)),
          points(own.filter(x => assisted(x._1.eventId)).map(_._2)),
          assists.map(a => byId(a._2.shotEventId).shotValue.toLong).sum, fs.size >= 5,
          fs.lift(4).map { (e, f) => FifthFoul(e.eventId, e.sequence, e.timestamp, f.period) })
      })
      val teams = start.teams.map { t =>
        val own = state.shots.filter(_._2.teamId == t.teamId)
        val ss = own.map(_._2)
        BasketballTeamStats(t.teamId, points(ss), points(own.filter(x => assisted(x._1.eventId)).map(_._2)),
          shooting(ss.filter(_.shotValue >= 2)), shooting(ss.filter(_.shotValue == 3)), shooting(ss.filter(_.shotValue == 1)))
      }
      BasketballResult(batch.gameId, batch.gameVersion, batch.matchId, Duration.between(times.head, times.last).toMillis,
        players, teams, scoringRuns(state.shots))
    }
  }

  private def action(s: State, e: Event, periods: Int, membership: Map[String, String]): Either[String, State] = for {
    id <- e.playerId.toRight("Action requires playerId")
    team <- membership.get(id).toRight("Unknown participant")
    _ <- check(s.fouls.count(_._1.playerId.contains(id)) < 5, "Action after fifth foul")
    next <- {
      def common(t: String, p: Int) = check(t == team && p >= 1 && p <= periods, "Invalid team/period")
      def shot(ref: String) = s.shots.find(_._1.eventId == ref).toRight("Missing prior shot")
      e.`type` match {
        case "SHOT_ATTEMPTED" => for {
          d <- JsonCodec.payload[Shot](e); _ <- common(d.teamId, d.period)
          _ <- check(Set(1, 2, 3)(d.shotValue) && d.periodTimeRemainingMs >= 0, "Invalid shot")
        } yield s.copy(shots = s.shots :+ (e -> d))
        case "ASSIST_RECORDED" => for {
          d <- JsonCodec.payload[Assist](e); _ <- common(d.teamId, d.period); ref <- shot(d.shotEventId)
          _ <- check(ref._2.made && ref._2.shotValue >= 2 && ref._2.teamId == team && ref._2.period == d.period &&
            ref._1.playerId.contains(d.scorerId) && d.scorerId != id && !s.assists.exists(_._2.shotEventId == d.shotEventId), "Invalid assist")
        } yield s.copy(assists = s.assists :+ (e -> d))
        case "REBOUND_COLLECTED" => for {
          raw <- JsonCodec.payload[Rebound](e)
          d = raw.copy(reboundType = raw.reboundType.trim.toUpperCase(java.util.Locale.ROOT))
          _ <- common(d.teamId, d.period); ref <- shot(d.shotEventId)
          _ <- check(!ref._2.made && ref._2.period == d.period &&
            ((d.reboundType == "OFFENSIVE" && ref._2.teamId == team) ||
              (d.reboundType == "DEFENSIVE" && ref._2.teamId != team)) &&
            !s.rebounds.exists(_._2.shotEventId == d.shotEventId), "Invalid rebound")
        } yield s.copy(rebounds = s.rebounds :+ (e -> d))
        case "TURNOVER_COMMITTED" => for {
          d <- JsonCodec.payload[Turnover](e); _ <- common(d.teamId, d.period)
          _ <- check(d.reason.trim.nonEmpty, "Empty turnover reason")
        } yield s.copy(turnovers = s.turnovers :+ e)
        case "FOUL_COMMITTED" => for {
          d <- JsonCodec.payload[Foul](e); _ <- common(d.teamId, d.period)
          _ <- check(d.reason.trim.nonEmpty && membership.get(d.affectedPlayerId).exists(_ != team), "Invalid foul")
        } yield s.copy(fouls = s.fouls :+ (e -> d))
        case other => Left(s"Unexpected basketball event: $other")
      }
    }
  } yield next

  // One maximal run per scoring team; misses and non-scoring actions never split it.
  private def scoringRuns(shots: Vector[(Event, Shot)]): Vector[ScoringRun] = {
    val groups = shots.filter(_._2.made).foldLeft(Vector.empty[Vector[(Event, Shot)]]) { (runs, shot) =>
      if (runs.lastOption.exists(_.last._2.teamId == shot._2.teamId)) runs.init :+ (runs.last :+ shot)
      else runs :+ Vector(shot)
    }
    groups.filter(g => points(g.map(_._2)) >= 8).map { g =>
      val threshold = g.scanLeft(0L)((sum, x) => sum + x._2.shotValue).tail.indexWhere(_ >= 8)
      ScoringRun(g.head._2.teamId, points(g.map(_._2)), g.map(_._1.eventId),
        g.head._1.eventId, g.last._1.eventId, g(threshold)._1.eventId)
    }
  }
}
