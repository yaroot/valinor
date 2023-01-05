package valinor

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, RequestEntity, StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import io.bullet.borer.compat.akkaHttp.*
import akka.stream.scaladsl.*
import com.typesafe.config.{Config, ConfigFactory}
import valinor.lib.WebsocketOverHttp as WOH

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

object Main {
  val logger          = org.log4s.getLogger
  val SendBuffer: Int = 256

  @nowarn
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem                = ActorSystem("valinor")
    implicit val executionContext: ExecutionContext = system.dispatcher
    implicit val mat: Materializer                  = SystemMaterializer(system).materializer

    val client   = httpClient(system)
    val config   = ConfigFactory.load()
    val bus      = buildBus()
    val handlers = handlerConfig(config)

    val route = {
      path("publish") {
        post {
          entity(as[WOH.Publication]) { pub =>
            Source
              .single(pub)
              .to(bus.sink)
              .run()
            logger.info(s"Accepting publish ${pub.messages.size} messages")
            complete(HttpResponse(status = StatusCodes.Accepted))
          }
        }
      } ~
        pathPrefix("connect" / Segment) { cluster =>
          handlers.get(cluster) match {
            case Some(c) =>
              logger.info(s"Accepting connect to [$cluster]")
              extractRequest { orig =>
                extractWebSocketUpgrade { upgrade =>
                  val f = clusterHandler(Some(c), orig, client)
                  complete(upgrade.handleMessages(handlerFlow(10, f, bus, defaultSubscription)))
                }
              }
            case None    =>
              complete(HttpResponse(status = StatusCodes.NotFound))
          }
        } ~
        complete(HttpResponse(status = StatusCodes.NotFound))
    }

    val bindf = Http(system)
      .newServerAt(config.getString("valinor.interface"), config.getInt("valinor.port"))
      .bind(route)

    val bind = Await.result(bindf, Duration.Inf)
    logger.info(s"Serving on $bind")
    Await.result(bind.whenTerminated, Duration.Inf)
  }

  val defaultSubscription: Set[String] = Set("test")

  def clusterHandler(c: Option[HandlerConfig], orig: HttpRequest, http: HttpRequest => Future[HttpResponse])(implicit
    mat: Materializer,
    ec: ExecutionContext
  ): WOH.Request => Future[WOH.Response] = {
    // `timeout-access` is a akka-http artificial header
    val headers = orig.headers
      .filter(_.isNot("timeout-access"))
      .map(h => Vector(h.name(), h.value()))
      .toVector

    val empty: WOH.Request => Future[WOH.Response] = { (_: WOH.Request) =>
      Future.successful(WOH.Response(Vector.empty[WOH.ControlMessage]))
    }

    def buildHandler(c: HandlerConfig) = { (req: WOH.Request) =>
      val req0 = req.copy(headers = headers)
      Marshal(req0)
        .to[RequestEntity]
        .flatMap { ent =>
          val hreq = HttpRequest(
            method = HttpMethods.POST,
            uri    = Uri(c.url)
          ).withEntity(ent)
          http(hreq)
        }
        .flatMap { resp =>
          Unmarshal(resp).to[WOH.Response]
        }
    }

    c.fold(empty)(buildHandler)
  }

  def handlerFlow(
    inputBatchSize: Int,
    wohHandler: WOH.Request => Future[WOH.Response],
    bus: Bus,
    defaultSubscription: Set[String]
  )(implicit
    mat: Materializer,
    ec: ExecutionContext
  ): Flow[Message, Message, NotUsed] = {
    val ref     = new AtomicReference[Set[String]](defaultSubscription)
    val reqFlow =
      Flow[Message]
        .mapAsync(inputBatchSize) {
          case m: TextMessage => m.toStrict(1.second).map(x => List(x.text))
          case _              => Future.successful(List.empty[String])
        }
        .mapConcat(identity)
        .buffer(inputBatchSize, OverflowStrategy.backpressure)
        .groupedWithin(inputBatchSize, 50.millis)
        .mapAsync(1) { inMsgs =>
          // collect the incoming messages and send to handler
          val msgs = inMsgs.map(WOH.ClientMessage).toVector
          val req  = WOH.Request(ref.get().toVector, Vector.empty, msgs)
          wohHandler(req).map { rp =>
            // roll over the subscribe/unsubscribe control message
            val sub0 = rp.messages.foldLeft(ref.get()) { (subs, cm) =>
              cm match {
                case WOH.ControlMessage(Some(sub), _, _)   => subs.incl(sub)
                case WOH.ControlMessage(_, Some(ubsub), _) => subs.excl(ubsub)
                case _                                     => subs
              }
            }
            ref.set(sub0)
            rp.messages
              .collect { case WOH.ControlMessage(_, _, Some(x)) => x }
              .collect {
                case WOH.SendMessage(_, Some(text), _)   => TextMessage(text): Message
                case WOH.SendMessage(_, _, Some(binary)) => BinaryMessage(binary): Message
              }
          }
        }
        .mapConcat(identity)
        .buffer(SendBuffer, OverflowStrategy.fail)

    val bcastFlow = bus.source
      .map { pub =>
        val subs = ref.get()
        pub.messages
          .collect {
            case x @ WOH.SendMessage(Some(chan), _, _) if subs(chan) => x
          }
          .collect {
            case WOH.SendMessage(_, Some(text), _)   => TextMessage(text): Message
            case WOH.SendMessage(_, _, Some(binary)) => BinaryMessage(binary): Message
          }
      }
      .mapConcat(identity)
      .buffer(SendBuffer, OverflowStrategy.fail)

    reqFlow.merge(bcastFlow)
  }

  def buildBus()(implicit mat: Materializer): Bus = {
    val (sink, source) =
      MergeHub
        .source[WOH.Publication]
        .toMat(BroadcastHub.sink)(Keep.both)
        .run()

    // ignore
    locally {
      source.runWith(Sink.ignore)
    }
    Bus(sink, source)
  }

  def httpClient(system: ActorSystem): HttpRequest => Future[HttpResponse] = {
    val http = Http(system)

    { (req: HttpRequest) => http.singleRequest(req) }
  }

  def handlerConfig(config: Config): Map[String, HandlerConfig] = {
    import scala.jdk.javaapi.CollectionConverters.*
    val hs      = config.getConfig("valinor.handlers")
    val default = config.getConfig("valinor.handler-defaults")
    val keys    = asScala(hs.root().entrySet()).toVector.map(_.getKey)
    logger.info(s"Parsing configs for: ${keys.mkString(", ")}")

    keys.map { key =>
      val c = hs.getConfig(key).withFallback(default)
      key -> HandlerConfig(
        c.getString("url"),
        c.getInt("batch-size")
      )
    }.toMap
  }
}

case class Bus(
  sink: Sink[WOH.Publication, NotUsed],
  source: Source[WOH.Publication, NotUsed]
)

case class HandlerConfig(
  url: String,
  batchSize: Int
)
