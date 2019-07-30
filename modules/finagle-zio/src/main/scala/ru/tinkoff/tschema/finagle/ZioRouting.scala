package ru.tinkoff.tschema.finagle
import cats.Monad
import cats.syntax.semigroup._
import com.twitter
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Service, http}
import com.twitter.util.{Future, Promise}
import ru.tinkoff.tschema.finagle.ZioRouting.{OtherFail, Rejected, ZIOHttp, ZIORoute}
import ru.tinkoff.tschema.utils.SubString
import zio.{Exit, ZIO}

final case class ZioRouting[+R](
    request: http.Request,
    path: CharSequence,
    matched: Int,
    embedded: R
)

object ZioRouting extends ZioRoutedImpl {
  sealed trait Fail[+E]

  final case class Rejected(rej: Rejection) extends Fail[Nothing]
  final case class OtherFail[E](err: E)     extends Fail[E]

  type ZIOHttp[-R, +E, +A] = ZIO[ZioRouting[R], Fail[E], A]
  type ZIORoute[+A]        = ZIOHttp[Any, Nothing, A]

  implicit def zioRouted[R, E]: RoutedPlus[ZIOHttp[R, E, *]] with LiftHttp[ZIOHttp[R, E, *], ZIO[R, E, *]] =
    zioRoutedAny.asInstanceOf[ZioRoutedInstance[R, E]]

  def zioConvertService[R, E](f: Throwable => Fail[E]): ConvertService[ZIOHttp[R, E, *]] =
    new ConvertService[ZIOHttp[R, E, *]] {
      def convertService[A](svc: Service[http.Request, A]): ZIOHttp[R, E, A] =
        ZIO.accessM(r =>
          ZIO.effectAsync(cb =>
            svc(r.request).respond {
              case twitter.util.Return(a) => cb(ZIO.succeed(a))
              case twitter.util.Throw(ex) => cb(ZIO.fail(f(ex)))
          }))
    }

  implicit def zioRunnable[R, E <: Throwable](
      implicit
      rejectionHandler: Rejection.Handler = Rejection.defaultHandler): Runnable[ZIOHttp[R, E, *], ZIO[R, E, *]] =
    zioResponse => ZIO.runtime[R].flatMap(runtime => ZIO.effectTotal(execResponse(runtime, zioResponse, _)))

  private[this] def execResponse[R, E <: Throwable](runtime: zio.Runtime[R],
                                                    zioResponse: ZIOHttp[R, E, Response],
                                                    request: Request)(implicit handler: Rejection.Handler): Future[Response] = {
    val promise = Promise[Response]
    runtime.unsafeRunAsync(
      zioResponse.provideSome[R](r => ZioRouting(request, SubString(request.path), 0, r)).catchAll {
        case Rejected(rejection) => ZIO.succeed(handler(rejection))
        case OtherFail(e)        => ZIO.fail(e)
      }
    ) {
      case Exit.Success(resp) => promise.setValue(resp)
      case Exit.Failure(cause) =>
        val resp = Response(Status.InternalServerError)
        resp.setContentString(cause.squash.getMessage)
        promise.setValue(resp)
    }
    promise
  }
}
private[finagle] class ZioRoutedImpl {

  protected trait ZioRoutedInstance[R, E] extends RoutedPlus[ZIOHttp[R, E, *]] with LiftHttp[ZIOHttp[R, E, *], ZIO[R, E, *]] {
    private type F[a] = ZIOHttp[R, E, a]
    implicit private[this] val self: RoutedPlus[F] = this
    implicit private[this] val monad: Monad[F]     = zio.interop.catz.ioInstances

    def matched: ZIORoute[Int] = ZIO.access(_.matched)

    def withMatched[A](m: Int, fa: ZIOHttp[R, E, A]): ZIOHttp[R, E, A] = fa.provideSome(_.copy(matched = m))

    def path: ZIORoute[CharSequence]    = ZIO.access(_.path)
    def request: ZIORoute[http.Request] = ZIO.access(_.request)
    def reject[A](rejection: Rejection): ZIOHttp[R, E, A] =
      Routed.unmatchedPath[F].flatMap(path => throwRej(rejection withPath path.toString))

    def combineK[A](x: ZIOHttp[R, E, A], y: ZIOHttp[R, E, A]): ZIOHttp[R, E, A] =
      catchRej(x)(xrs => catchRej(y)(yrs => throwRej(xrs |+| yrs)))

    def apply[A](fa: ZIO[R, E, A]): ZIOHttp[R, E, A] = fa.mapError(OtherFail(_)).provideSome(_.embedded)
  }

  protected[this] object zioRoutedAny extends ZioRoutedInstance[Any, Nothing]

  @inline private[this] def catchRej[R, E, A](z: ZIOHttp[R, E, A])(f: Rejection => ZIOHttp[R, E, A]): ZIOHttp[R, E, A] =
    z.catchSome { case Rejected(xrs) => f(xrs) }

  @inline private[this] def throwRej[R, E, A](map: Rejection): ZIOHttp[R, E, A] = ZIO.fail(Rejected(map))
}
