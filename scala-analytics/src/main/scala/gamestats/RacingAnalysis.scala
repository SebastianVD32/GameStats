package gamestats

import java.time.{Duration, Instant, OffsetDateTime}
import scala.util.Try
import JsonCodec.given

object RacingAnalysis {
  private case class Stop(entry: Event, data: PitEntry, exit: Option[(Event, PitExit)] = None)
  private case class State(laps: Vector[(Event, Lap)] = Vector.empty,
    stops: Vector[Stop] = Vector.empty, penalties: Vector[(Event, Penalty)] = Vector.empty)
  private def check(ok: Boolean, error: String): Either[String, Unit] = Either.cond(ok, (), error)
  private def time(e: Event): Either[String, Instant] =
    Try(OffsetDateTime.parse(e.timestamp)).toEither.left.map(_ => s"${e.eventId}: invalid timestamp")
      .flatMap(t => check(t.getOffset.getTotalSeconds == 0, s"${e.eventId}: timestamp must be UTC").map(_ => t.toInstant))

  def analyze(batch: MatchBatch): Either[String, RacingResult] = {
    val es = batch.events
    for {
      _ <- check(batch.gameId == "RACING" && batch.matchId.trim.nonEmpty && batch.gameVersion.trim.nonEmpty, "Invalid racing identity")
      _ <- check(es.size >= 2, "Complete match required")
      _ <- check(es.head.`type` == "MATCH_STARTED" && es.last.`type` == "MATCH_FINISHED" &&
        es.head.playerId.isEmpty && es.last.playerId.isEmpty, "Invalid start/finish")
      _ <- check(es.map(_.eventId).distinct.size == es.size && es.zipWithIndex.forall { (e, i) =>
        e.sequence == i.toLong + 1 && e.gameId == batch.gameId && e.gameVersion == batch.gameVersion &&
        e.matchId == batch.matchId && e.eventId.trim.nonEmpty && e.data.isObject && e.playerId.forall(_.trim.nonEmpty)
      }, "Invalid envelope, duplicate or sequence")
      times <- es.foldLeft[Either[String, Vector[Instant]]](Right(Vector.empty)) { (acc, e) =>
        for { ts <- acc; t <- time(e) } yield ts :+ t
      }
      _ <- check(times.zip(times.drop(1)).forall((a, b) => !b.isBefore(a)), "Timestamps go backwards")
      start <- JsonCodec.payload[RacingStart](es.head)
      _ <- check(start.plannedLaps > 0 && start.driverIds.size >= 2 && start.driverIds.forall(_.trim.nonEmpty) &&
        start.driverIds.distinct.size == start.driverIds.size, "Invalid drivers/planned laps")
      state <- es.slice(1, es.size - 1).foldLeft[Either[String, State]](Right(State())) { (acc, e) =>
        acc.flatMap(s => action(s, e, start))
      }
      _ <- check(start.driverIds.forall(id => state.laps.count(_._1.playerId.contains(id)) == start.plannedLaps), "Incomplete laps")
      _ <- check(state.stops.forall(_.exit.nonEmpty), "Unclosed pit stop")
      finish <- JsonCodec.payload[RacingFinish](es.last)
      _ <- check(finish.classification.size == start.driverIds.size &&
        finish.classification.map(_.playerId).toSet == start.driverIds.toSet &&
        finish.classification.map(_.position).toSet == (1 to start.driverIds.size).toSet, "Invalid final classification")
      result = calculate(batch, start, state, Duration.between(times.head, times.last).toMillis)
      _ <- check(result.classification == finish.classification.sortBy(_.position),
        s"Classification inconsistency: calculated ${result.classification.mkString(", ")}; reported ${finish.classification.sortBy(_.position).mkString(", ")}")
    } yield result
  }

  private def action(s: State, e: Event, start: RacingStart): Either[String, State] = for {
    id <- e.playerId.toRight(s"${e.eventId}: action requires playerId")
    _ <- check(start.driverIds.contains(id), s"${e.eventId}: unknown driver")
    next <- {
      def lap(n: Int) = check(n >= 1 && n <= start.plannedLaps, s"${e.eventId}: invalid lap number")
      e.`type` match {
        case "LAP_COMPLETED" => for {
          d <- JsonCodec.payload[Lap](e); _ <- lap(d.lapNumber)
          _ <- check(d.lapNumber == s.laps.count(_._1.playerId.contains(id)) + 1 && d.lapTimeMs > 0 &&
            d.position >= 1 && d.position <= start.driverIds.size &&
            !s.laps.exists(x => x._2.lapNumber == d.lapNumber && x._2.position == d.position), s"${e.eventId}: invalid lap/order/position")
        } yield s.copy(laps = s.laps :+ (e -> d))
        case "PIT_ENTERED" => for {
          d <- JsonCodec.payload[PitEntry](e); _ <- lap(d.lapNumber)
          _ <- check(d.stopId.trim.nonEmpty && d.reason.trim.nonEmpty && !s.stops.exists(_.data.stopId == d.stopId), s"${e.eventId}: invalid/duplicate pit entry")
        } yield s.copy(stops = s.stops :+ Stop(e, d))
        case "PIT_EXITED" => for {
          d <- JsonCodec.payload[PitExit](e); _ <- lap(d.lapNumber)
          stop <- s.stops.find(_.data.stopId == d.stopId).toRight(s"${e.eventId}: missing pit entry")
          _ <- check(stop.exit.isEmpty && stop.entry.playerId.contains(id) && d.lapNumber >= stop.data.lapNumber &&
            d.tireCompound.trim.nonEmpty, s"${e.eventId}: invalid pit exit")
        } yield s.copy(stops = s.stops.map(p => if (p.data.stopId == d.stopId) p.copy(exit = Some(e -> d)) else p))
        case "PENALTY_APPLIED" => for {
          raw <- JsonCodec.payload[Penalty](e); _ <- lap(raw.lapNumber)
          d = raw.copy(penaltyType = raw.penaltyType.trim.toUpperCase(java.util.Locale.ROOT))
          _ <- check(Set("TIME", "POSITION")(d.penaltyType) && d.value > 0 && d.reason.trim.nonEmpty, s"${e.eventId}: invalid penalty")
        } yield s.copy(penalties = s.penalties :+ (e -> d))
        case other => Left(s"${e.eventId}: unexpected racing event $other")
      }
    }
  } yield next

  private def calculate(batch: MatchBatch, start: RacingStart, s: State, duration: Long): RacingResult = {
    val laps = s.laps.groupBy(_._1.playerId.get)
    def total(id: String): BigInt = laps(id).map(x => BigInt(x._2.lapTimeMs)).sum
    def penalty(id: String, kind: String): BigInt = s.penalties
      .filter(x => x._1.playerId.contains(id) && x._2.penaltyType == kind).map(x => BigInt(x._2.value)).sum
    def order(adjusted: Boolean) = start.driverIds.sortBy(id =>
      (total(id) + (if (adjusted) penalty(id, "TIME") else BigInt(0)), laps(id).last._2.position))
    val original = order(false)
    // Apply each position penalty to the current order; do not aggregate displacements.
    val finalOrder = s.penalties.filter(_._2.penaltyType == "POSITION").sortBy(_._1.sequence)
      .foldLeft(order(true)) { case (current, (e, p)) =>
        val id = e.playerId.get
        val from = current.indexOf(id)
        val to = (BigInt(from) + p.value).min(BigInt(current.size - 1)).toInt
        val remaining = current.patch(from, Vector.empty[String], 1)
        remaining.patch(to, Vector(id), 0)
      }
    def classification(ids: Vector[String]) = ids.zipWithIndex.map((id, i) => ClassificationEntry(id, i + 1))
    val drivers = start.driverIds.map { id =>
      val own = laps(id).map(_._2)
      val stops = s.stops.filter(_.entry.playerId.contains(id)).map { stop =>
        val (exit, d) = stop.exit.get
        PitStopStats(stop.data.stopId, stop.entry.eventId, exit.eventId, stop.data.lapNumber, d.lapNumber,
          Duration.between(OffsetDateTime.parse(stop.entry.timestamp), OffsetDateTime.parse(exit.timestamp)).toMillis)
      }
      val before = original.indexOf(id) + 1
      val after = finalOrder.indexOf(id) + 1
      RacingDriverStats(id, own.size, own.map(_.lapTimeMs).min, total(id).toDouble / own.size,
        total(id), stops.size, stops, penalty(id, "TIME"), penalty(id, "POSITION"), total(id) + penalty(id, "TIME"),
        before, after, before - after, after == 1 && own.exists(_.position == start.driverIds.size))
    }
    val fastest = s.laps.map(_._2.lapTimeMs).min
    RacingResult(batch.gameId, batch.gameVersion, batch.matchId, duration, drivers, classification(original),
      classification(finalOrder), s.laps.filter(_._2.lapTimeMs == fastest).map { (e, l) =>
        FastestLap(e.playerId.get, e.eventId, l.lapNumber, l.lapTimeMs)
      })
  }
}
