$ErrorActionPreference = "Stop"

$javaHome = "C:\Users\20979\.jdks\ms-17.0.18"
$dbUrl = "jdbc:opengauss://x6.sjcmc.cn:34003/postgres?user=sht&password=@Sht20051229"
$driverUrl = $dbUrl.Replace("jdbc:opengauss://", "jdbc:postgresql://")

$env:DB_URL = $dbUrl
$env:DRIVER_URL = $driverUrl

$jshell = Join-Path $javaHome "bin\jshell.exe"
$driverJar = "D:\develop\apache-maven-3.9.11\mvn_repo\org\opengauss\opengauss-jdbc\6.0.0\opengauss-jdbc-6.0.0.jar"

if (-not (Test-Path $jshell)) {
  throw "jshell not found: $jshell"
}
if (-not (Test-Path $driverJar)) {
  throw "openGauss jdbc jar not found: $driverJar"
}

Write-Host "java_home=$javaHome"
Write-Host "db_url=$dbUrl"
Write-Host "driver_url=$driverUrl"

$script = @(
  'import java.sql.*;',
  'var url = System.getenv("DB_URL");',
  'var driverUrl = System.getenv("DRIVER_URL");',
  'var driver = new org.postgresql.Driver();',
  'System.out.println("runtime_java=" + System.getProperty("java.version"));',
  'System.out.println("accepts_opengauss_url=" + driver.acceptsURL(url));',
  'System.out.println("accepts_translated_url=" + driver.acceptsURL(driverUrl));',
  'var conn = DriverManager.getConnection(driverUrl);',
  'var st = conn.createStatement();',
  'var rs = st.executeQuery("select current_database(), current_schema()");',
  'while (rs.next()) System.out.println("db=" + rs.getString(1) + ", schema=" + rs.getString(2));',
  'rs = st.executeQuery("select username, role_code, status, password_text from user_account where username in (''admin'', ''teacher1'', ''student1'') order by username");',
  'while (rs.next()) System.out.println("account=" + rs.getString("username") + ", role=" + rs.getString("role_code") + ", status=" + rs.getString("status") + ", password=" + rs.getString("password_text"));',
  'var ps = conn.prepareStatement("select user_id, username, role_code, display_name from user_account where username = ? and password_text = ? and status = ''enabled''");',
  'ps.setString(1, "admin");',
  'ps.setString(2, "123456");',
  'rs = ps.executeQuery();',
  'if (rs.next()) System.out.println("login_ok=true, user_id=" + rs.getObject("user_id") + ", role=" + rs.getObject("role_code") + ", name=" + rs.getObject("display_name")); else System.out.println("login_ok=false");',
  'conn.close();',
  '/exit'
)

$script | & $jshell --class-path $driverJar
