name := "akka-presentation"

version := "1.0"

scalaVersion := "2.11.8"

val akka = "2.4.8"

libraryDependencies ++= {
  Seq(
    // akka
    "com.typesafe.akka"          %%  "akka-actor"                 % akka,
    "com.typesafe.akka"          %%  "akka-persistence"           % akka,
    "com.typesafe.akka"          %%  "akka-cluster"               % akka,
    "com.typesafe.akka"          %%  "akka-cluster-sharding"      % akka,
    "com.typesafe.akka"          %%  "akka-cluster-tools"         % akka,
    "com.typesafe.akka"          %%  "akka-contrib"               % akka,

    // persistence
    "com.github.dnvriend"        %% "akka-persistence-inmemory"   % "1.2.11"

  )
}