version := "1.0"

lazy val commonSettings = Seq(
  organization := "sevts.platform5",
  version := "1.0",
  scalaVersion := Dependencies.Versions.scala,
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  ),
  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
  resolvers ++= Seq(
    Resolver.bintrayRepo("naftoligug", "maven"),
    Resolver.sonatypeRepo("snapshots"))
)


lazy val terminal = (project in file("./"))
  .settings( name := "platform5-terminal",
    organization := "sevts.platform5",
    version := "1.0"
  )
  .settings(
    scriptClasspath := Seq("*"),
    bashScriptConfigLocation := Some("${app_home}/../CLIENT_TERMINAL_config.txt"),
    mainClass in Compile := Some("sevts.terminal.Platform5Terminal"),
    topLevelDirectory := Some("platform5-server")
  )
  .enablePlugins(JavaAppPackaging)
  .settings(
    mappings in Universal <+= (packageBin in Compile, sourceDirectory ) map { (_, src) =>
      val conf = src / "main" / "resources" / "application.conf"
      conf -> "conf/application.conf"
    }
  )
  .settings( commonSettings: _* )
  .settings( libraryDependencies := Dependencies.terminal.value )