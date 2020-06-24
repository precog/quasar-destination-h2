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
import quasar.destination.h2.{H2DestinationModule => H2DM}
import quasar.destination.h2.server._

import argonaut._, Argonaut._
import cats.syntax.option._
import org.specs2.mutable.Specification

class H2DestinationModuleSpec extends Specification {

  private def initCfgToJson(cfg: InitConfig): Json = {
    ("url" := jString(cfg.url)) ->:
    ("user" := jString(cfg.user)) ->:
    ("password" := jString(cfg.password)) ->:
    ("script" := jString(cfg.script)) ->:
    jEmptyObject
  }

  private def serverCfgToJson(cfg: ServerConfig, stripNulls: Boolean): Json = {
    def argsToJson(ss : List[String]): Json =
      ("args" := jArray(ss.map(jString))) ->:
      jEmptyObject

    val js =
      ("init" := cfg.init.fold(jNull)(initCfgToJson)) ->:
      ("tcp" := cfg.tcp.fold(jNull)(tcp => argsToJson(tcp.args))) ->:
      ("pg" := cfg.pg.fold(jNull)(pg => argsToJson(pg.args))) ->:
      jEmptyObject

    if (stripNulls)
      js.withObject(j => JsonObject.fromIterable(j.toList.filter(!_._2.isNull)))
    else
      js
  }

  private def cfgToJson(cfg: Config, stripNulls: Boolean): Json = {
    val js =
      ("connectionUri" := jString(cfg.connectionUri.toString())) ->:
      ("server" := cfg.server.fold(jNull)(serverCfgToJson(_, stripNulls))) ->:
      jEmptyObject

    if (stripNulls)
      js.withObject(j => JsonObject.fromIterable(j.toList.filter(!_._2.isNull)))
    else
      js
  }

  "sanitize config" >> {
    "does not redact connectionUri if there are no properties" >> {
      val js = cfgToJson(Config("jdbc:h2:file:/data/sample", None), stripNulls = false)
      H2DM.sanitizeDestinationConfig(js) must_=== js
    }

    "redacts properties in connectionUri (server field is json null)" >> {
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123", None), stripNulls = false)
      H2DM.sanitizeDestinationConfig(js) must_===
        cfgToJson(Config("h2:file:~/sample;<REDACTED>", None), stripNulls = false)
    }

    "redacts properties in connectionUri (without server field)" >> {
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123", None), stripNulls = true)
      H2DM.sanitizeDestinationConfig(js) must_===
        cfgToJson(Config("h2:file:~/sample;<REDACTED>", None), stripNulls = false)
    }

    "redacts config with server field" >> {
      val serverCfg = ServerConfig(
        InitConfig("someUrl", "someUser", "somePassword", "someScript").some,
        TcpConfig(List("a", "b")).some,
        PgConfig(List("c", "d")).some
      )
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123", serverCfg.some), stripNulls = false)

      H2DM.sanitizeDestinationConfig(js) must_===
        cfgToJson(
          Config(
            "h2:file:~/sample;<REDACTED>",
            serverCfg.copy(init = InitConfig("someUrl", "someUser", "<REDACTED>", "<REDACTED>").some).some),
        stripNulls = false)
    }

    "redacts config with server field having null tcp field" >> {
      val serverCfg = ServerConfig(
        InitConfig("someUrl", "someUser", "somePassword", "someScript").some,
        None,
        PgConfig(List("c", "d")).some
      )
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123", serverCfg.some), stripNulls = false)

      H2DM.sanitizeDestinationConfig(js) must_===
        cfgToJson(
          Config(
            "h2:file:~/sample;<REDACTED>",
            serverCfg.copy(init = InitConfig("someUrl", "someUser", "<REDACTED>", "<REDACTED>").some).some),
        stripNulls = false)
    }

    "redacts config with server field with missing tcp field" >> {
      val serverCfg = ServerConfig(
        InitConfig("someUrl", "someUser", "somePassword", "someScript").some,
        None,
        PgConfig(List("c", "d")).some
      )
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123", serverCfg.some), stripNulls = true)

      H2DM.sanitizeDestinationConfig(js) must_===
        cfgToJson(
          Config(
            "h2:file:~/sample;<REDACTED>",
            serverCfg.copy(init = InitConfig("someUrl", "someUser", "<REDACTED>", "<REDACTED>").some).some),
        stripNulls = false)
    }
  }
}
