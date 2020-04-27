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

import slamdata.Predef._
import quasar.destination.h2.server.ServerConfig

import argonaut._, Argonaut._
import cats.implicits._

final case class Config(connectionUri: String, server: Option[ServerConfig]) {

  private def sanitizeUri(uri: String) = {
    // Just redacting all uri settings, not really worth figuring out how to parse all this...
    val semiColon = uri.indexOf(";")
    if (semiColon === -1)
      uri
    else
      uri.substring(0, semiColon) + s";$Redacted"
  }

  def sanitized: Config =
    Config(sanitizeUri(connectionUri), server.map(_.sanitized))
}

object Config {
  implicit val codecJson: CodecJson[Config] =
    casecodec2(Config.apply, Config.unapply)("connectionUri", "server")
}
