$env:JAVA_HOME = "C:\Users\20979\.jdks\ms-17.0.18"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not $env:DB_URL) {
  $env:DB_URL = "jdbc:opengauss://x6.sjcmc.cn:34003/postgres?user=sht&password=@Sht20051229"
}

java -version
mvn -Dmaven.repo.local="D:\develop\apache-maven-3.9.11\mvn_repo" spring-boot:run
