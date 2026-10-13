package gamestats

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.parse

object JsonCodec {
  private def strict[A](decoder: Decoder[A], fields: String*): Decoder[A] =
    Decoder.instance { cursor =>
      if (!cursor.keys.exists(_.toSet == fields.toSet))
        Left(DecodingFailure(s"Expected exactly: ${fields.mkString(", ")}", cursor.history))
      else decoder(cursor)
    }

  given Decoder[Event] = strict(deriveDecoder[Event], "eventId", "timestamp", "gameId",
    "gameVersion", "matchId", "sequence", "playerId", "type", "data")
  given Decoder[MatchBatch] = strict(deriveDecoder[MatchBatch], "gameId", "gameVersion", "matchId", "events")
  given Decoder[Participant] = strict(deriveDecoder[Participant], "playerId", "color")
  given Decoder[ChessStart] = strict(deriveDecoder[ChessStart], "participants", "initialClockMs", "incrementMs")
  given Decoder[ChessMove] = strict(deriveDecoder[ChessMove], "moveNumber", "piece", "from", "to",
    "decisionTimeMs", "clockBeforeMs", "clockAfterMs")
  given Decoder[ChessCapture] = strict(deriveDecoder[ChessCapture], "moveEventId", "capturedPiece", "capturedPlayerId")
  given Decoder[ChessFinish] = strict(deriveDecoder[ChessFinish], "winnerPlayerId", "reason")
  given Encoder[Event] = deriveEncoder
  given Encoder[MatchBatch] = deriveEncoder
  given Encoder[PressureWindow] = deriveEncoder
  given Encoder[PlayerStats] = deriveEncoder
  given Encoder[ChessResult] = deriveEncoder
  given Encoder[ColorStats] = deriveEncoder

  def decodeBatch(text: String): Either[String, MatchBatch] =
    parse(text).left.map(_.message).flatMap(_.as[MatchBatch].left.map(_.message))
  given Decoder[BasketballTeam] = strict(deriveDecoder[BasketballTeam], "teamId", "playerIds")
  given Decoder[BasketballStart] = strict(deriveDecoder[BasketballStart], "teams", "periods")
  given Decoder[Shot] = strict(deriveDecoder[Shot], "teamId", "period", "periodTimeRemainingMs", "shotValue", "made")
  given Decoder[Assist] = strict(deriveDecoder[Assist], "teamId", "period", "scorerId", "shotEventId")
  given Decoder[Rebound] = strict(deriveDecoder[Rebound], "teamId", "period", "reboundType", "shotEventId")
  given Decoder[Turnover] = strict(deriveDecoder[Turnover], "teamId", "period", "reason")
  given Decoder[Foul] = strict(deriveDecoder[Foul], "teamId", "period", "affectedPlayerId", "reason")
  given Decoder[TeamScore] = strict(deriveDecoder[TeamScore], "teamId", "points")
  given Decoder[BasketballFinish] = strict(deriveDecoder[BasketballFinish], "scores")
  given Encoder[ShootingStats] = deriveEncoder
  given Encoder[FifthFoul] = deriveEncoder
  given Encoder[BasketballPlayerStats] = deriveEncoder
  given Encoder[BasketballTeamStats] = deriveEncoder
  given Encoder[ScoringRun] = deriveEncoder
  given Encoder[BasketballResult] = deriveEncoder
  given Decoder[RacingStart] = strict(deriveDecoder[RacingStart], "driverIds", "plannedLaps")
  given Decoder[Lap] = strict(deriveDecoder[Lap], "lapNumber", "lapTimeMs", "position")
  given Decoder[PitEntry] = strict(deriveDecoder[PitEntry], "stopId", "lapNumber", "reason")
  given Decoder[PitExit] = strict(deriveDecoder[PitExit], "stopId", "lapNumber", "tireCompound")
  given Decoder[Penalty] = strict(deriveDecoder[Penalty], "penaltyType", "value", "reason", "lapNumber")
  given Decoder[ClassificationEntry] = strict(deriveDecoder[ClassificationEntry], "playerId", "position")
  given Decoder[RacingFinish] = strict(deriveDecoder[RacingFinish], "classification")
  given Encoder[ClassificationEntry] = deriveEncoder
  given Encoder[PitStopStats] = deriveEncoder
  given Encoder[FastestLap] = deriveEncoder
  given Encoder[RacingDriverStats] = deriveEncoder
  given Encoder[RacingResult] = deriveEncoder
  given Decoder[PacmanStart] = strict(deriveDecoder[PacmanStart], "playerId", "initialLives", "initialLevel")
  given Decoder[PacmanFinish] = strict(deriveDecoder[PacmanFinish], "finalScore", "levelsCompleted", "livesRemaining", "reason")
  given Decoder[Pellet] = strict(deriveDecoder[Pellet], "level", "pelletId")
  given Decoder[PowerPellet] = strict(deriveDecoder[PowerPellet], "level", "pelletId", "durationMs")
  given Decoder[Ghost] = strict(deriveDecoder[Ghost], "level", "ghostId", "powerUpEventId")
  given Decoder[LifeLost] = strict(deriveDecoder[LifeLost], "level", "cause", "livesRemaining")
  given Decoder[LevelCompleted] = strict(deriveDecoder[LevelCompleted], "level")
  given Encoder[PowerEffect] = deriveEncoder
  given Encoder[PacmanResult] = deriveEncoder
  given Encoder[PacmanPlayerStats] = deriveEncoder
  def payload[A: Decoder](event: Event): Either[String, A] =
    event.data.as[A].left.map(error => s"${event.eventId}: ${error.message}")
}
