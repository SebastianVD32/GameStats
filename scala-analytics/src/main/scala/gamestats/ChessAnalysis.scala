package gamestats

import java.time.{Duration, OffsetDateTime}
import scala.util.Try
import JsonCodec.given

object ChessAnalysis {
  private final case class State(clocks: Map[String, Long],
    moves: Vector[(Event, ChessMove)] = Vector.empty,
    captures: Vector[(Event, ChessCapture)] = Vector.empty)
  private val pieces = Set("KING", "QUEEN", "ROOK", "BISHOP", "KNIGHT", "PAWN")
  private def normalized(value: String): String = value.trim.toUpperCase(java.util.Locale.ROOT)
  private def check(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), message)
  private def time(event: Event): Either[String, java.time.Instant] =
    Try(OffsetDateTime.parse(event.timestamp)).toEither.left.map(_ => s"${event.eventId}: invalid timestamp")
      .flatMap(t => check(t.getOffset.getTotalSeconds == 0, "timestamp must be UTC").map(_ => t.toInstant))
  private def average(values: Vector[Long]): Option[Double] =
    Option.when(values.nonEmpty)(values.foldLeft(BigDecimal(0))(_ + _).toDouble / values.size)

  def analyze(batch: MatchBatch): Either[String, ChessResult] = {
    val events = batch.events
    for {
      _ <- check(batch.gameId == "CHESS", "Only CHESS is supported in stage 1")
      _ <- check(batch.matchId.trim.nonEmpty && batch.gameVersion.trim.nonEmpty, "Empty match identity")
      _ <- check(events.size >= 2, "A complete match needs start and finish")
      _ <- check(events.head.`type` == "MATCH_STARTED" && events.last.`type` == "MATCH_FINISHED",
        "Expected MATCH_STARTED first and MATCH_FINISHED last")
      _ <- check(events.head.playerId.isEmpty && events.last.playerId.isEmpty, "Start/finish playerId must be null")
      _ <- check(events.map(_.eventId).distinct.size == events.size, "Duplicate eventId")
      _ <- check(events.zipWithIndex.forall { (e, i) =>
        e.sequence == i.toLong + 1 && e.gameId == batch.gameId && e.gameVersion == batch.gameVersion &&
          e.matchId == batch.matchId && e.eventId.trim.nonEmpty && e.data.isObject &&
          e.playerId.forall(_.trim.nonEmpty)
      }, "Invalid envelope, sequence or batch identity")
      times <- events.foldLeft[Either[String, Vector[java.time.Instant]]](Right(Vector.empty)) {
        (acc, e) => for { ts <- acc; t <- time(e) } yield ts :+ t
      }
      _ <- check(times.zip(times.drop(1)).forall((a, b) => !b.isBefore(a)), "Timestamps go backwards")
      start <- JsonCodec.payload[ChessStart](events.head)
      participants = start.participants.map(p => p.copy(playerId = p.playerId.trim, color = normalized(p.color)))
      _ <- check(participants.size == 2 && participants.map(_.playerId).distinct.size == 2 &&
        participants.forall(_.playerId.nonEmpty) && participants.map(_.color).toSet == Set("WHITE", "BLACK"),
        "Expected two distinct players, one per color")
      _ <- check(start.initialClockMs > 0 && start.incrementMs >= 0, "Invalid initial clock or increment")
      state <- events.slice(1, events.size - 1).foldLeft[Either[String, State]](
        Right(State(participants.map(p => p.playerId -> start.initialClockMs).toMap))) {
        (acc, e) => acc.flatMap(s => applyAction(s, e, participants, start.incrementMs))
      }
      finish <- JsonCodec.payload[ChessFinish](events.last)
      reason = normalized(finish.reason)
      _ <- check((reason == "DRAW" && finish.winnerPlayerId.isEmpty) ||
        (Set("CHECKMATE", "RESIGNATION", "TIMEOUT")(reason) &&
          finish.winnerPlayerId.exists(id => participants.exists(_.playerId == id))), "Invalid winner/reason")
    } yield {
      val capturedIds = state.captures.map(_._2.moveEventId).toSet
      val movesByPlayer = state.moves.groupBy(_._1.playerId.get)
      val capturesByPlayer = state.captures.groupBy(_._1.playerId.get)
      val players = participants.map { p =>
        val moves = movesByPlayer.getOrElse(p.playerId, Vector.empty)
        val windows = pressureWindows(moves)
        PlayerStats(p.playerId, p.color, moves.size, capturesByPlayer.getOrElse(p.playerId, Vector.empty).size,
          moves.map(_._2.decisionTimeMs).sum, average(moves.map(_._2.decisionTimeMs)),
          average(moves.filter(m => capturedIds(m._1.eventId)).map(_._2.decisionTimeMs)),
          windows.nonEmpty, windows)
      }
      ChessResult(batch.gameId, batch.gameVersion, batch.matchId, finish.winnerPlayerId,
        finish.winnerPlayerId.flatMap(id => participants.find(_.playerId == id).map(_.color)), reason,
        Duration.between(times.head, times.last).toMillis, state.moves.size, state.captures.size, players)
    }
  }

  private def applyAction(s: State, e: Event, players: Vector[Participant], increment: Long): Either[String, State] =
    for {
      id <- e.playerId.toRight(s"${e.eventId}: action requires playerId")
      player <- players.find(_.playerId == id).toRight(s"${e.eventId}: unknown participant")
      next <- e.`type` match {
        case "MOVE_PLAYED" => for {
          m <- JsonCodec.payload[ChessMove](e)
          _ <- check(m.moveNumber == s.moves.size.toLong + 1 &&
            player.color == (if (m.moveNumber % 2 == 1) "WHITE" else "BLACK"), "Invalid move order/color")
          _ <- check(pieces(normalized(m.piece)) && m.from.matches("[a-h][1-8]") &&
            m.to.matches("[a-h][1-8]") && m.from != m.to, "Invalid piece or squares")
          _ <- check(m.decisionTimeMs >= 0 && m.clockBeforeMs > 0 && m.clockAfterMs >= 0 &&
            m.clockBeforeMs == s.clocks(id) && m.decisionTimeMs < m.clockBeforeMs &&
            BigInt(m.clockAfterMs) == BigInt(m.clockBeforeMs) - m.decisionTimeMs + increment,
            "Invalid clock or decision time")
        } yield s.copy(clocks = s.clocks.updated(id, m.clockAfterMs), moves = s.moves :+ (e -> m))
        case "PIECE_CAPTURED" => for {
          c <- JsonCodec.payload[ChessCapture](e)
          _ <- check(s.moves.exists(m => m._1.eventId == c.moveEventId && m._1.playerId.contains(id)),
            "Capture must reference a prior move by its player")
          _ <- check(!s.captures.exists(_._2.moveEventId == c.moveEventId), "Duplicate capture for move")
          _ <- check(pieces(normalized(c.capturedPiece)) && normalized(c.capturedPiece) != "KING" &&
            players.exists(p => p.playerId == c.capturedPlayerId.trim && p.playerId != id), "Invalid captured piece/player")
        } yield s.copy(captures = s.captures :+ (e -> c))
        case other => Left(s"Unexpected chess event: $other")
      }
    } yield next

  // Each overlapping window of three OWN moves is reported once. Opponent moves are absent here.
  private[gamestats] def pressureWindows(moves: Vector[(Event, ChessMove)]): Vector[PressureWindow] =
    moves.foldLeft((Vector.empty[String], Vector.empty[PressureWindow])) { case ((run, windows), (e, m)) =>
      val next = if (m.clockBeforeMs < 60000) (run :+ e.eventId).takeRight(3) else Vector.empty
      (next, if (next.size == 3) windows :+ PressureWindow(next) else windows)
    }._2

  // Stateless aggregate: callers supply completed matches; replays of the same identity count once.
  def winsByColor(results: Vector[ChessResult]): Either[String, Vector[ColorStats]] = {
    val groups = results.groupBy(r => (r.gameId, r.gameVersion, r.matchId))
    check(groups.values.forall(_.distinct.size == 1), "Conflicting results for the same match").map { _ =>
      val unique = groups.values.map(_.head).toVector
      Vector("WHITE", "BLACK").map { color =>
        val wins = unique.count(_.winnerColor.contains(color))
        ColorStats(color, wins, unique.size, Option.when(unique.nonEmpty)(wins * 100.0 / unique.size))
      }
    }
  }
}
