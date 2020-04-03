/*
 * Copyright 2020 Precog Data
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

package quasar.destination.h2
package server

import slamdata.Predef._

import argonaut._, Argonaut._

final case class InitConfig(
    url: String,
    user: String,
    password: String,
    script: String) {

  def sanitized = InitConfig(url, user, Redacted, Redacted)
}

final case class TcpConfig(args: List[String])

final case class PgConfig(args: List[String])

final case class ServerConfig(
    init: Option[InitConfig],
    tcp: Option[TcpConfig],
    pg: Option[PgConfig]) {

  def sanitized: ServerConfig = ServerConfig(init.map(_.sanitized), tcp, pg)
}

object ServerConfig {
  implicit val codecJsonInitConfig: CodecJson[InitConfig] =
    casecodec4(InitConfig.apply, InitConfig.unapply)("url", "user", "password", "script")

  implicit val codecJsonTcpConfig: CodecJson[TcpConfig] =
    casecodec1(TcpConfig.apply, TcpConfig.unapply)("args")

  implicit val codecJsonPgConfig: CodecJson[PgConfig] =
    casecodec1(PgConfig.apply, PgConfig.unapply)("args")

  implicit val codecJson: CodecJson[ServerConfig] =
    casecodec3(ServerConfig.apply, ServerConfig.unapply)("init", "tcp", "pg")
}
