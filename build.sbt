val Http4sVersion             = "0.18.8"
val Specs2Version             = "4.0.3"
val LogbackVersion            = "1.2.3"
val fs2ReactiveStreamsVersion = "0.5.1"
val typesafeNettyVersion      = "2.0.0"

val nettyVersion = "4.1.24.Final"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.http4s"            %% "http4s-server"               % Http4sVersion,
    "org.http4s"            %% "http4s-testing"               % Http4sVersion % "test",
    "org.http4s"            %% "http4s-dsl"                  % Http4sVersion,
    "org.specs2"            %% "specs2-core"                 % Specs2Version % "test",
    "ch.qos.logback"        % "logback-classic"              % LogbackVersion,
    "io.netty"              % "netty-handler"                % nettyVersion,
    "io.netty"              % "netty-codec-http"             % nettyVersion,
    "com.typesafe.netty"    % "netty-reactive-streams-http"  % typesafeNettyVersion,
    "io.netty"              % "netty-transport-native-epoll" % nettyVersion classifier "linux-x86_64",
    "com.github.zainab-ali" %% "fs2-reactive-streams"        % fs2ReactiveStreamsVersion
  ),
  organization := "io.github.jmcardon",
  crossScalaVersions in ThisBuild := Seq("2.11.12", "2.12.5"),
  scalaVersion := "2.12.6",
  fork in test := true,
  fork in run := true,
  parallelExecution in test := false,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.5"),
  scalacOptions := Seq(
    "-unchecked",
    "-feature",
    "-deprecation",
    "-encoding",
    "utf8",
    "-Ywarn-adapted-args",
    "-Ywarn-inaccessible",
    "-Ywarn-nullary-override",
    "-Ypartial-unification",
    "-language:higherKinds",
    "-language:implicitConversions"
  )
)

lazy val miku = (project in file("miku"))
  .settings(commonSettings)
  .settings(
    name := "miku",
    version := "0.0.1-SNAPSHOT",
  )

lazy val examples = (project in file("examples"))
  .settings(commonSettings)
  .settings(libraryDependencies += "org.http4s" %% "http4s-circe" % Http4sVersion)
  .dependsOn(miku)

lazy val queuefun = (project in file("queuefun"))
  .settings(commonSettings)
  .settings(
    organization := "http4s-miku",
    name := "queuefun",
    version := "0.0.1-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.http4s"          %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s"          %% "http4s-circe"        % Http4sVersion,
      "com.conversantmedia" % "disruptor"            % "1.2.11",
      "org.jctools"         % "jctools-core"         % "2.1.1"
    )
  )
