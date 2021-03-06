package bunyod.fp.domain.tokens

import bunyod.fp.effekts.GenUUID
import bunyod.fp.utils.cfg.Configuration.UserJwtCfg
import cats.effect.Sync
import cats.implicits._
import io.circe.syntax._
import pdi.jwt._
import dev.profunktor.auth.jwt._
import pdi.jwt.JwtClaim
import scala.concurrent.duration.FiniteDuration

class TokenSyncService[F[_]: GenUUID: Sync](
  config: UserJwtCfg,
  exp: FiniteDuration
) extends TokensAlgebra[F] {

  def create: F[JwtToken] =
    Sync[F].delay(java.time.Clock.systemUTC).flatMap { implicit clock =>
      for {
        uuid <- GenUUID[F].make
        claim <- Sync[F].delay(JwtClaim(uuid.asJson.noSpaces).issuedNow.expiresIn(exp.toMillis))
        secretKey = JwtSecretKey(config.secretKey.value)
        token <- jwtEncode[F](claim, secretKey, JwtAlgorithm.HS256)
      } yield token
    }
}
