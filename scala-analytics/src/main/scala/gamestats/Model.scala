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

final case class BasketballTeam(teamId: String, playerIds: Vector[String])
final case class BasketballStart(teams: Vector[BasketballTeam], periods: Int)
final case class Shot(teamId: String, period: Int, periodTimeRemainingMs: Long, shotValue: Int, made: Boolean)
final case class Assist(teamId: String, period: Int, scorerId: String, shotEventId: String)
final case class Rebound(teamId: String, period: Int, reboundType: String, shotEventId: String)
final case class Turnover(teamId: String, period: Int, reason: String)
final case class Foul(teamId: String, period: Int, affectedPlayerId: String, reason: String)
final case class TeamScore(teamId: String, points: Long)
final case class BasketballFinish(scores: Vector[TeamScore])
final case class ShootingStats(made: Int, attempted: Int, percentage: Option[Double])
final case class FifthFoul(eventId: String, sequence: Long, timestamp: String, period: Int)
final case class BasketballPlayerStats(playerId: String, teamId: String, points: Long,
  assists: Int, rebounds: Int, offensiveRebounds: Int, defensiveRebounds: Int,
  turnovers: Int, fouls: Int, fieldGoals: ShootingStats, threePointers: ShootingStats,
  freeThrows: ShootingStats, assistedBasketPoints: Long, pointsCreatedByAssists: Long,
  excluded: Boolean, fifthFoul: Option[FifthFoul])
final case class BasketballTeamStats(teamId: String, points: Long, assistedBasketPoints: Long,
  fieldGoals: ShootingStats, threePointers: ShootingStats, freeThrows: ShootingStats)
final case class ScoringRun(teamId: String, points: Long, shotEventIds: Vector[String],
  startEventId: String, endEventId: String, thresholdEventId: String)
final case class BasketballResult(gameId: String, gameVersion: String, matchId: String,
  durationMs: Long, players: Vector[BasketballPlayerStats], teams: Vector[BasketballTeamStats],
  scoringRuns: Vector[ScoringRun])
