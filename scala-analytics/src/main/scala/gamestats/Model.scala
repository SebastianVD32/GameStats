package gamestats

import io.circe.Json

// The names and nullability mirror go-ingestion/internal/domain/event.go.
final case class Event(eventId: String, timestamp: String, gameId: String,
  gameVersion: String, matchId: String, sequence: Long, playerId: Option[String],
  `type`: String, data: Json)
final case class MatchBatch(gameId: String, gameVersion: String, matchId: String,
  events: Vector[Event])
final case class Participant(playerId: String, color: String)
final case class ChessStart(participants: Vector[Participant], initialClockMs: Long, incrementMs: Long)
final case class ChessMove(moveNumber: Long, piece: String, from: String, to: String,
  decisionTimeMs: Long, clockBeforeMs: Long, clockAfterMs: Long)
final case class ChessCapture(moveEventId: String, capturedPiece: String, capturedPlayerId: String)
final case class ChessFinish(winnerPlayerId: Option[String], reason: String)

final case class PressureWindow(moveEventIds: Vector[String])
final case class PlayerStats(playerId: String, color: String, moves: Int, captures: Int,
  totalDecisionTimeMs: Long, averageDecisionTimeMs: Option[Double],
  averageCaptureDecisionTimeMs: Option[Double], timePressure: Boolean,
  pressureWindows: Vector[PressureWindow])
final case class ChessResult(gameId: String, gameVersion: String, matchId: String,
  winnerPlayerId: Option[String], winnerColor: Option[String], reason: String,
  durationMs: Long, totalMoves: Int, totalCaptures: Int, players: Vector[PlayerStats])
final case class ColorStats(color: String, wins: Int, completedMatches: Int, winPercentage: Option[Double])
