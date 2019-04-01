/*
 * Copyright 2018 Fluence Labs Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fluence.node.workers

import cats.data.EitherT
import cats.syntax.flatMap._
import cats.effect.Sync
import fluence.effects.tendermint.rpc._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import slogging.LazyLogging

import scala.language.higherKinds

object WorkersHttp extends LazyLogging {

  /**
   * Routes for Workers API.
   *
   * @param pool Workers pool to get workers from
   * @param dsl Http4s DSL to build routes with
   */
  def routes[F[_]: Sync](pool: WorkersPool[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    object QueryPath extends QueryParamDecoderMatcher[String]("path")
    object QueryData extends OptionalQueryParamDecoderMatcher[String]("data")
    object QueryId extends OptionalQueryParamDecoderMatcher[String]("id")

    /** Helper: runs a function iff a worker is in a pool, unwraps EitherT into different response types, renders errors */
    def withTendermint(appId: Long)(fn: TendermintRpc[F] ⇒ EitherT[F, RpcError, String]): F[Response[F]] =
      pool.withWorker(appId, _.withServices(_.tendermint)(fn(_).value)).flatMap {
        case None ⇒
          logger.debug(s"Requested app $appId, but there's no such worker in the pool")
          NotFound("App not found on the node")

        case Some(res) ⇒
          res match {
            case Right(result) ⇒
              logger.trace(s"Responding with OK: $result")
              Ok(result)

            case Left(RpcRequestFailed(err)) ⇒
              logger.warn(s"RPC request failed: $err", err)
              InternalServerError(err.getMessage)

            case Left(err: RpcRequestErrored) ⇒
              logger.warn(s"RPC request errored: $err", err)
              InternalServerError(err.error)

            case Left(RpcBodyMalformed(err)) ⇒
              logger.debug(s"RPC body malformed: $err", err)
              BadRequest(err.getMessage)
          }
      }

    // Routes comes there
    HttpRoutes.of {
      case GET -> Root / LongVar(appId) / "query" :? QueryPath(path) +& QueryData(data) +& QueryId(id) ⇒
        logger.debug(s"TendermintRpc query request. appId: $appId, path: $path, data: $data")
        withTendermint(appId)(_.query(path, data.getOrElse(""), id = id.getOrElse("dontcare")))

      case GET -> Root / LongVar(appId) / "status" ⇒
        logger.trace(s"TendermintRpc status. appId: $appId")
        withTendermint(appId)(_.status)

      case GET -> Root / LongVar(appId) / "p2pPort" ⇒
        logger.debug(s"Worker p2pPort. appId: $appId")
        pool.get(appId).flatMap {
          case Some(worker) ⇒
            logger.debug(s"Worker p2pPort = ${worker.p2pPort}. appId: $appId")
            Ok(worker.p2pPort.toString)

          case None ⇒
            logger.debug(s"Requested app $appId, but there's no such worker in the pool")
            NotFound("App not found on the node")
        }

      case req @ POST -> Root / LongVar(appId) / "tx" :? QueryId(id) ⇒
        req.decode[String] { tx ⇒
          logger.debug(s"TendermintRpc broadcastTxSync request: \ntx: $tx,\n id: $id")
          withTendermint(appId)(_.broadcastTxSync(tx, id.getOrElse("dontcare")))
        }
    }
  }
}
