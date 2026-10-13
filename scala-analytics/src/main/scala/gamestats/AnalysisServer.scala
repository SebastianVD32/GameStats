package gamestats

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import io.circe.{Json, parser}
import io.circe.syntax.*
import JsonCodec.given
import scala.util.control.NonFatal

object Analysis {
  def analyze(b: MatchBatch): Either[String, Json] = b.gameId match {
    case "CHESS" => ChessAnalysis.analyze(b).map(_.asJson)
    case "BASKETBALL" => BasketballAnalysis.analyze(b).map(_.asJson)
    case "RACING" => RacingAnalysis.analyze(b).map(_.asJson)
    case "PACMAN" => PacmanAnalytics.analyze(b).map(_.asJson)
    case other => Left(s"Unsupported game: $other")
  }
}

// One process owns a directory. A monitor serializes requests, while a filesystem lock
// prevents a second process from sharing it. Readers see only complete atomic records.
final class ResultStore(directory: Path) extends AutoCloseable {
  Files.createDirectories(directory)
  private val lockChannel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
  private val lock = Option(lockChannel.tryLock()).getOrElse(throw new IllegalStateException("Result directory already in use"))
  private def path(id: String): Path = directory.resolve(MessageDigest.getInstance("SHA-256")
    .digest(id.getBytes(UTF_8)).map(b => f"${b & 255}%02x").mkString + ".json")
  private def read(p: Path): Json = parser.parse(Files.readString(p, UTF_8)).fold(e => throw e, identity)
  private def records: Vector[Json] = {
    val stream = Files.list(directory)
    try { import scala.jdk.CollectionConverters.*
      stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).map(read).toVector
    } finally stream.close()
  }
  // Fail startup on damaged records instead of silently losing accumulated statistics.
  records.foreach { record =>
    val batch = record.hcursor.get[MatchBatch]("batch").fold(e => throw e, identity)
    val result = Analysis.analyze(batch).fold(e => throw new IllegalStateException(e), identity)
    require(record.hcursor.get[Json]("result").toOption.contains(result), "Damaged stored result")
  }
  def save(batch: MatchBatch, result: Json): Either[String, Json] = synchronized {
    val target = path(batch.matchId)
    if (Files.exists(target)) {
      val existing = read(target)
      if (existing.hcursor.get[MatchBatch]("batch").toOption.contains(batch)) Right(existing.hcursor.get[Json]("result").toOption.get)
      else Left("matchId already exists with different content")
    } else {
      val tmp = Files.createTempFile(directory, ".pending-", ".tmp")
      try {
        val bytes = Json.obj("batch" -> batch.asJson, "result" -> result).noSpaces.getBytes(UTF_8)
        val channel = FileChannel.open(tmp, StandardOpenOption.WRITE)
        try {
          val buffer = ByteBuffer.wrap(bytes)
          while (buffer.hasRemaining) channel.write(buffer)
          channel.force(true)
        } finally channel.close()
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        // Linux supports directory fsync, required before acknowledging a durable write.
        if (!System.getProperty("os.name").startsWith("Windows")) {
          val dir = FileChannel.open(directory, StandardOpenOption.READ)
          try dir.force(true) finally dir.close()
        }
        Right(result)
      } finally Files.deleteIfExists(tmp)
    }
  }
  def get(id: String): Option[Json] = synchronized {
    val p = path(id)
    Option.when(Files.exists(p))(read(p).hcursor.get[Json]("result").toOption.get)
  }
  def player(id: String): Json = synchronized {
    val scores = records.flatMap { r =>
      val c = r.hcursor.downField("result")
      if (c.get[String]("gameId").contains("PACMAN") && c.get[String]("playerId").contains(id))
        c.get[BigInt]("totalScore").toOption else None
    }
    Json.obj("playerId" -> Json.fromString(id), "completedMatches" -> Json.fromInt(scores.size),
      "totalScore" -> scores.sum.asJson, "bestScore" -> scores.maxOption.asJson)
  }
  def healthy: Boolean = synchronized { lock.isValid && Files.isWritable(directory) }
  def close(): Unit = { lock.release(); lockChannel.close() }
}

object AnalysisServer {
  def start(): Unit = {
    val store = new ResultStore(Path.of(sys.env.getOrElse("RESULTS_DIR", "output/results")))
    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", sys.env.getOrElse("PORT", "8081").toInt), 64)
    val executor = Executors.newFixedThreadPool(8)
    server.setExecutor(executor)
    def respond(x: HttpExchange, code: Int, json: Json): Unit = {
      val bytes = json.noSpaces.getBytes(UTF_8)
      x.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
      x.sendResponseHeaders(code, bytes.length)
      x.getResponseBody.write(bytes)
    }
    def error(message: String) = Json.obj("error" -> Json.fromString(message))
    server.createContext("/", (x: HttpExchange) => {
      try {
        val p = x.getRequestURI.getRawPath
        (x.getRequestMethod, p) match {
          case ("GET", "/healthz") => respond(x, if (store.healthy) 200 else 503, Json.obj("status" -> Json.fromString(if (store.healthy) "ok" else "unavailable")))
          case ("POST", "/api/v1/matches") =>
            val bytes = x.getRequestBody.readNBytes(4 * 1024 * 1024 + 1)
            if (bytes.length > 4 * 1024 * 1024) respond(x, 413, error("Request too large"))
            else JsonCodec.decodeBatch(new String(bytes, UTF_8)).flatMap(b => Analysis.analyze(b).map(r => b -> r)) match {
              case Left(e) => respond(x, 422, error(e))
              case Right((b, r)) => store.save(b, r) match {
                case Left(e) => respond(x, 409, error(e))
                case Right(saved) => respond(x, 200, saved)
              }
            }
          case ("GET", route) if route.startsWith("/api/v1/matches/") =>
            store.get(URLDecoder.decode(route.stripPrefix("/api/v1/matches/"), UTF_8)) match {
              case Some(r) => respond(x, 200, r)
              case None => respond(x, 404, error("Match not found"))
            }
          case ("GET", route) if route.startsWith("/api/v1/pacman/players/") =>
            respond(x, 200, store.player(URLDecoder.decode(route.stripPrefix("/api/v1/pacman/players/"), UTF_8)))
          case _ => respond(x, 404, error("Route not found"))
        }
      } catch {
        case NonFatal(e) => System.err.println(s"Request failed: ${e.getMessage}"); respond(x, 500, error("Storage or service failure"))
      } finally x.close()
    })
    Runtime.getRuntime.addShutdownHook(new Thread(() => { server.stop(1); executor.shutdown(); store.close() }))
    server.start()
    println("Scala analysis listening on 0.0.0.0:" + server.getAddress.getPort)
  }
}
