package gamestats

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import io.circe.syntax.*
import JsonCodec.given
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty || args.sameElements(Array("serve"))) {
      AnalysisServer.start(); return
    }
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: gamestats.Main <match.json> [result.json]")
      sys.exit(2)
    }
    try {
      val analysis = for {
        batch <- JsonCodec.decodeBatch(Files.readString(Path.of(args(0)), UTF_8))
        result <- Analysis.analyze(batch)
      } yield result.spaces2
      analysis match {
        case Left(error) => System.err.println(s"Analysis failed: $error"); sys.exit(1)
        case Right(json) =>
          if (args.length == 2) {
            val output = Path.of(args(1))
            Option(output.getParent).foreach(p => Files.createDirectories(p))
            Files.writeString(output, json + "\n", UTF_8)
          }
          println(json)
      }
    } catch {
      case NonFatal(error) => System.err.println(s"File error: ${error.getMessage}"); sys.exit(1)
    }
  }
}
