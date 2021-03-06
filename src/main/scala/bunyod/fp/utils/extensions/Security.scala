package bunyod.fp.utils.extensions

import bunyod.fp.domain.auth.AuthPayloads._
import bunyod.fp.domain.auth._
import bunyod.fp.domain.crypto.CryptoService
import bunyod.fp.domain.tokens.TokenSyncService
import bunyod.fp.domain.users.UsersPayloads._
import bunyod.fp.effekts.ApThrow
import bunyod.fp.infrastructure.redis._
import bunyod.fp.infrastructure.postgres.UsersRepository
import bunyod.fp.utils.cfg.Configuration.Config
import cats.effect._
import cats.implicits._
import dev.profunktor.auth.jwt._
import dev.profunktor.redis4cats.RedisCommands
import io.circe.parser._
import pdi.jwt._
import skunk.Session

import java.util.UUID

object Security {

  def make[F[_]: Sync](
    cfg: Config,
    sessionPool: Resource[F, Session[F]],
    redis: RedisCommands[F, String, String]
  ): F[Security[F]] = {
    val adminJwtAuth: AdminJwtAuth =
      AdminJwtAuth(
        JwtAuth
          .hmac(
            cfg.adminJwt.secretKey.value,
            JwtAlgorithm.HS256
          )
      )

    val userJwtAuth: UserJwtAuth =
      UserJwtAuth(
        JwtAuth
          .hmac(
            cfg.userJwt.secretKey.value,
            JwtAlgorithm.HS256
          )
      )

    val adminToken = JwtToken(cfg.adminJwt.adminToken.value)
    for {
      adminClaim <- jwtDecode[F](adminToken, adminJwtAuth.value)
      content <- ApThrow[F].fromEither(decode[ClaimContent](adminClaim.content))
      adminUser = AdminUser(User(UserId(UUID.fromString(content.claim)), UserName("admin")))
      tokensService = new TokenSyncService[F](cfg.userJwt, cfg.tokenExpiration.value)
      token <- tokensService.create
      adminAuthRepo = new LiveAdminAuthRepository[F](token, adminUser)
      userAuthRepo = new LiveUserAuthRepository[F](redis)
      adminsAuthService = new UsersService[F, AdminUser](adminAuthRepo)
      usersAuthService = new UsersService[F, CommonUser](userAuthRepo)
      crypto <- CryptoService.make[F](cfg.passwordSalt)
      usersRepo = new UsersRepository[F](sessionPool, crypto)
      authRepo <- LiveAuthRepository.make[F](cfg.tokenExpiration, tokensService, usersRepo, redis)
      authService = new AuthService[F](authRepo)
    } yield new Security[F](authService, adminsAuthService, usersAuthService, adminJwtAuth, userJwtAuth)

  }
}

final class Security[F[_]] private (
  val authService: AuthService[F],
  val adminsAuthService: UsersService[F, AdminUser],
  val usersAuthService: UsersService[F, CommonUser],
  val adminJwtAuth: AdminJwtAuth,
  val userJwtAuth: UserJwtAuth
) {}
