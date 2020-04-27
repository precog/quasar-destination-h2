# quasar-destination-h2 [![Discord](https://img.shields.io/discord/373302030460125185.svg?logo=discord)](https://discord.gg/QNjwCg6)

## Usage

```sbt
libraryDependencies += "com.precog" %% "quasar-destination-h2" % <version>
```

## Configuration

Configuration for the H2 destination has the following JSON format

```json
{
  "connectionUri": String
  [, "server": {
    [ "init": Object }]
    [, "tcp": { "args": Array String }]
    [, "pg": { "args": Array String }]
    }
  ]
}
```

* `connectionUri`: the [H2 connection URL](https://h2database.com/html/features.html#database_url) _without the leading `jdbc:`_.
* `server`: (optional) the H2 server configuration: 
  * `init`: (optional) specifies how to initialize the H2 server. Object has the following format: `{ "url": String, "user": String, "password": String, "script": String }` where url is the connection URL to use to initialize the database _including the leading `jdbc:`_, user is the username to use, password is the password to use and script is the SQL script to initialize the database with.
  * `tcp`: (optional) if included a tcp server will be started with the specified
  args. Please refer to [H2 Server docs](http://www.h2database.com/javadoc/org/h2/tools/Server.html) for the supported args.
  * `pg`: (optional) if included a postgres server will be started with the specified
  args. Please refer to [H2 Server docs](http://www.h2database.com/javadoc/org/h2/tools/Server.html) for the supported args.