package example.repoanalyzer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream.Materializer
import akka.stream.io.Framing
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString
import scala.concurrent.Future

class LogStreamer(implicit system: ActorSystem, materializer: Materializer) {
  val ipResolver = new FreeGeoIPResolver()
  import system.dispatcher

  def requestLogLines(): Future[Source[String, Any]] = {
    val request = HttpRequest(uri = "http://[::1]:9002/logs")
    Http().singleRequest(request).map(extractLinesFromRequest)
  }

  def extractLinesFromRequest(response: HttpResponse): Source[String, Any] =
    response.entity.dataBytes
      .via(Gzip.decoderFlow)
      .via(Framing.delimiter(ByteString("\n"), 10000, true))
      .map(_.utf8String)

  def requestParsedLogLines(): Future[Source[LogEntry, Any]] =
    requestLogLines().map(_.via(parseLogLines))

  def requestSemanticLogLines(): Future[Source[AccessEntryWithGroup, Any]] =
    requestParsedLogLines().map(
      _.via(analyzeSemantically).collect {
        case a: AccessEntryWithGroup ⇒ a
      })

  def parseLogLines: Flow[String, LogEntry, Any] =
    Flow[String].map(RepoLogEntry.parseFromLine)

  def analyzeSemantically: Flow[LogEntry, AccessEntry, Any] =
    Flow[LogEntry]
      .collect {
        case entry: RepoLogEntry ⇒ entry
        // ignore unreadable entries
      }
      .mapAsync(4) { repo ⇒
        getIpInfoForEntry(repo).map(info ⇒ RepositorySearchEntry.fromLogEntry(repo, info))
      }

  def getIpInfoForEntry(entry: RepoLogEntry): Future[IPInfo] =
    ipResolver.infoFor(entry.ip)

  case class GroupCountUpdate(groupId: String, updatedCount: Long)
  object GroupCountUpdate {
    import spray.json.DefaultJsonProtocol._
    implicit val updateFormat = jsonFormat2(GroupCountUpdate.apply _)
  }

  def groupCountUpdates: Flow[AccessEntryWithGroup, GroupCountUpdate, Unit] = {
    case class CountState(
        lastKey: Option[String],
        counts: Map[String, Long]) {
      def increment(key: String): CountState =
        new CountState(Some(key),
          counts.updated(key, counts(key) + 1))
    }
    def initialState: CountState = CountState(None, Map.empty.withDefaultValue(0L))

    Flow[AccessEntryWithGroup]
      .scan(initialState)(_ increment _.groupId)
      .collect {
        case CountState(Some(lastKey), counts) ⇒ GroupCountUpdate(lastKey, counts(lastKey))
      }
  }
}