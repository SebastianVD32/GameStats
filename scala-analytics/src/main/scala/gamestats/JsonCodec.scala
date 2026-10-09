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
  def payload[A: Decoder](event: Event): Either[String, A] =
    event.data.as[A].left.map(error => s"${event.eventId}: ${error.message}")
}
